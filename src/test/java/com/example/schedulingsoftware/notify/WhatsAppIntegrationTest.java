package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AuthService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** WhatsApp messages queued for published weeks, changes, day-off decisions and announcements, and the sender. */
@SpringBootTest
@Transactional
@org.springframework.test.context.TestPropertySource(properties = {
        // WhatsApp "connected" (to an address nothing listens on: tests never send; they call the sender directly).
        "alliancetel.whatsapp.enabled=true", "alliancetel.whatsapp.phone-number-id=TEST",
        "alliancetel.whatsapp.access-token=TEST", "alliancetel.whatsapp.api-base-url=http://127.0.0.1:9" })
class WhatsAppIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    NotificationService notificationService;
    @Autowired
    AuthService authService;
    @Autowired
    com.example.schedulingsoftware.schedule.WeeklyScheduleRepository scheduleRepository;

    Long area;
    Long store;
    Long ana;   // phone + agreed
    Long ben;   // phone, not agreed
    Long cara;  // no phone
    Long dev;   // phone + agreed
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Message Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Harbor", "HBR", null, 1, true, null, null)).id();
        ana = person("W-1", "Ana", "(619) 555-0101", true);
        ben = person("W-2", "Ben", "619-555-0102", false);
        cara = person("W-3", "Cara", null, null);
        dev = person("W-4", "Dev", "+977 981 234 5678", true);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String name, String phone, Boolean optIn) {
        return employeeService.create(new EmployeeRequest(code, name, "Tester", code.toLowerCase() + "@test.example",
                phone, Role.EMPLOYEE, area, store, 6, true, null, optIn)).employee().id();
    }

    /** WhatsApp messages for one person (each message also goes out by email). */
    private List<Notification> sentTo(Long employeeId) {
        return notifications.findAll().stream().filter(n -> n.getEmployee().getId().equals(employeeId))
                .filter(n -> n.getChannel() == NotificationChannel.WHATSAPP).toList();
    }

    private List<Notification> emailsTo(Long employeeId) {
        return notifications.findAll().stream().filter(n -> n.getEmployee().getId().equals(employeeId))
                .filter(n -> n.getChannel() == NotificationChannel.EMAIL).toList();
    }

    @Test
    void publishingSendsEachPersonTheirWeek() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertTrue(notifications.findAll().isEmpty(), "nothing is sent for a draft");
        scheduleService.publish(s.id());

        Notification a = sentTo(ana).getFirst();
        assertEquals(NotificationKind.WEEK_PUBLISHED, a.getKind());
        assertEquals(NotificationStatus.QUEUED, a.getStatus());
        assertEquals("+16195550101", a.getPhone());
        assertEquals("schedule_published", a.getTemplate());
        assertEquals(4, a.paramList().size());
        assertEquals("Ana", a.paramList().get(0));
        assertTrue(a.paramList().get(2).contains("Harbor 10am–8pm"), a.paramList().get(2));
        assertTrue(a.getPreview().startsWith("Hi Ana, your AllianceTel schedule for "), a.getPreview());
        assertFalse(a.getParams().contains("\n"));

        assertEquals(NotificationStatus.NOT_SENT, sentTo(ben).getFirst().getStatus());
        assertEquals("Hasn't agreed to WhatsApp messages yet.", sentTo(ben).getFirst().getError());
        assertEquals("No phone number on file.", sentTo(cara).getFirst().getError());
        assertEquals("+9779812345678", sentTo(dev).getFirst().getPhone());
    }

    @Test
    void republishingTellsOnlyThePeopleWhoseDaysChanged() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        scheduleService.publish(s.id());
        notifications.deleteAll();

        scheduleService.revertToDraft(s.id());
        Long wed = s.rows().stream().filter(r -> r.employeeId().equals(ana)).findFirst().orElseThrow().days().get(2).entryId();
        scheduleService.updateEntry(s.id(), wed, DayType.OFF, null);
        scheduleService.publish(s.id());

        List<Notification> all = notifications.findAll().stream()
                .filter(n -> n.getChannel() == NotificationChannel.WHATSAPP).toList();
        assertEquals(1, all.size(), "only Ana's week changed");
        assertEquals(1, emailsTo(ana).size());
        assertTrue(emailsTo(ana).getFirst().getBody().contains("• Wed"), emailsTo(ana).getFirst().getBody());
        Notification n = all.getFirst();
        assertEquals(NotificationKind.WEEK_CHANGED, n.getKind());
        assertTrue(n.paramList().get(2).contains("now Off (was Harbor 10am–8pm)"), n.paramList().get(2));
    }

    @Test
    void weeksPublishedBeforeMessagesExistedStartTrackingQuietly() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        scheduleService.publish(s.id());
        notifications.deleteAll();
        // As if published by the previous version: no record of what was sent.
        scheduleRepository.findById(s.id()).orElseThrow().getEntries().forEach(e -> e.setPublishedLabel(null));
        Long request = dayOffService.create(new CreateDayOffRequest(ana, List.of(monday.plusDays(3)), null, true))
                .getFirst().id();
        dayOffService.approve(request, null);
        assertTrue(notifications.findAll().stream().noneMatch(n -> n.getKind() == NotificationKind.WEEK_CHANGED),
                "nobody gets their whole week as 'changed'");
        assertEquals(1, sentTo(ana).size(), "Ana still hears about her day off");
    }

    @Test
    void dayOffDecisionsGoToThePersonWhoAsked() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        scheduleService.publish(s.id());
        notifications.deleteAll();
        LocalDate thu = monday.plusDays(3);
        Long request = dayOffService.create(new CreateDayOffRequest(ana, List.of(thu), null, null)).getFirst().id();
        dayOffService.approve(request, "Have fun");

        List<Notification> forAna = sentTo(ana);
        assertEquals(1, forAna.size(), "the decision only; no separate 'schedule changed' message");
        assertEquals(NotificationKind.DAY_OFF_DECISION, forAna.getFirst().getKind());
        assertTrue(forAna.getFirst().getPreview().contains("was approved. Note from your manager: Have fun"),
                forAna.getFirst().getPreview());

        Long second = dayOffService.create(new CreateDayOffRequest(dev, List.of(monday.plusDays(4)), null, null)).getFirst().id();
        dayOffService.reject(second, null);
        assertTrue(sentTo(dev).getLast().getPreview().contains("was not approved"));
    }

    @Test
    void announcementsReachTheWholeAreaAndManagersOnlyTheirOwn() {
        var result = notificationService.announce(area, "Store meeting Friday 9am at Harbor");
        assertEquals(4, result.recipients());
        assertEquals(4, result.reachable(), "everyone gets it by email; Ana and Dev also on WhatsApp");
        assertTrue(sentTo(dev).getFirst().getPreview().contains("for the AllianceTel Message Area team: Store meeting"));

        Long other = areaService.create(new AreaRequest("Other Message Area", null, true)).id();
        TestUsers.signInAs(ana, Role.MANAGER, area);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> notificationService.announce(other, "Hi"));
    }

    @Test
    void agreeingNeedsAPhoneNumberAndPeopleCanChangeTheirOwnChoice() {
        assertThrows(BusinessRuleException.class, () -> employeeService.update(cara,
                new EmployeeRequest("W-3", "Cara", "Tester", "w-3@test.example", null, Role.EMPLOYEE, area, store, 6,
                        true, null, true)));
        TestUsers.signInAs(ben, Role.EMPLOYEE, area);
        assertTrue(authService.setWhatsapp(true).whatsappOptIn());
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        assertThrows(BusinessRuleException.class, () -> authService.setWhatsapp(true));
        // Removing the phone number also stops messages.
        TestUsers.signInAsAdmin();
        employeeService.update(ana, new EmployeeRequest("W-1", "Ana", "Tester", "w-1@test.example", " ", Role.EMPLOYEE,
                area, store, 6, true, null, null));
        assertFalse(employeeService.findById(ana).whatsappOptIn());
    }

    @Test
    void theSenderSendsRetriesAndGivesUp() {
        scheduleService.publish(scheduleService.generate(area, monday).id());
        List<String> sent = new ArrayList<>();
        WhatsAppSettings connected = new WhatsAppSettings(true, "123", "token", "v22.0", "en_US", "https://alliancetel.com",
                "schedule_published", "schedule_changed", "day_off_update", "team_announcement", "https://graph.facebook.com");
        EmailSettings mailOff = new EmailSettings(false, "", "", "", "https://api.resend.com");
        EmailClient noMail = (to, subject, text, html, key) -> null;

        // Not connected: queued messages are recorded as not sent.
        WhatsAppSettings off = new WhatsAppSettings(false, "", "", "v22.0", "en_US", "", "a", "b", "c", "d", "https://graph.facebook.com");
        // 2 WhatsApp (Ana, Dev) + 4 emails, all recorded as not sent.
        assertEquals(6, new WhatsAppDispatcher(notifications, off, (to, t, l, p) -> null, mailOff, noMail).dispatch());
        assertTrue(sentTo(ana).getFirst().getError().contains("isn't connected"));

        // Connected: Ana's goes through; Dev's hits a temporary problem, then a permanent one.
        sentTo(ana).getFirst().setStatus(NotificationStatus.QUEUED);
        sentTo(dev).getFirst().setStatus(NotificationStatus.QUEUED);
        WhatsAppClient fake = (to, template, language, params) -> {
            sent.add(to);
            return to.startsWith("+977") ? new SendResult(false, null, "Rate limited", sent.size() < 3)
                    : new SendResult(true, "wamid.1", null, false);
        };
        WhatsAppDispatcher dispatcher = new WhatsAppDispatcher(notifications, connected, fake, mailOff, noMail);
        dispatcher.dispatch();
        assertEquals(NotificationStatus.SENT, sentTo(ana).getFirst().getStatus());
        assertEquals("wamid.1", sentTo(ana).getFirst().getProviderMessageId());
        Notification d = sentTo(dev).getFirst();
        assertEquals(NotificationStatus.QUEUED, d.getStatus(), "tried again later");
        assertEquals(1, d.getAttempts());
        d.setNextAttemptAt(java.time.Instant.now().minusSeconds(1));
        dispatcher.dispatch();
        assertEquals(NotificationStatus.FAILED, d.getStatus());
        assertEquals("Rate limited", d.getError());

        // Trying again from the Messages page queues it once more.
        assertEquals(NotificationStatus.QUEUED, notificationService.retry(d.getId()).status());
    }
}
