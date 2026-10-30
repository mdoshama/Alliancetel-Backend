package com.example.schedulingsoftware.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Schedule emails: what is queued, who opts out, and how the sender sends them (WhatsApp not connected here). */
@SpringBootTest
@Transactional
class EmailIntegrationTest {

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

    Long area;
    Long ana;
    Long ben;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Mail Area", null, true)).id();
        Long store = storeService.create(new StoreRequest(area, "Harbor", "HBR", null, 1, true, null, null)).id();
        ana = employeeService.create(new EmployeeRequest("M-1", "Ana", "Mailer", "ana.mailer@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
        ben = employeeService.create(new EmployeeRequest("M-2", "Ben", "Mailer", "ben.mailer@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, null, null, false)).employee().id();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private List<Notification> to(Long employeeId) {
        return notifications.findAll().stream().filter(n -> n.getEmployee().getId().equals(employeeId)).toList();
    }

    @Test
    void publishingEmailsEachPersonTheirWeekAndSkipsWhatsAppUntilConnected() {
        scheduleService.publish(scheduleService.generate(area, monday).id());

        List<Notification> forAna = to(ana);
        assertEquals(1, forAna.size(), "email only: WhatsApp isn't connected");
        Notification mail = forAna.getFirst();
        assertEquals(NotificationChannel.EMAIL, mail.getChannel());
        assertEquals(NotificationStatus.QUEUED, mail.getStatus());
        assertEquals("ana.mailer@test.example", mail.getEmail());
        assertTrue(mail.getSubject().startsWith("Your schedule for "), mail.getSubject());
        assertTrue(mail.getBody().startsWith("Hi Ana,\n\nYour AllianceTel schedule for "), mail.getBody());
        assertEquals(7, mail.getBody().lines().filter(l -> l.startsWith("• ")).count(), "one line per day");
        assertTrue(mail.getBody().contains("• Mon " + monday.getMonthValue() + "/" + monday.getDayOfMonth() + " Harbor 10am–8pm"));

        assertEquals(NotificationStatus.NOT_SENT, to(ben).getFirst().getStatus(), "Ben turned email off");
        assertEquals("Turned off email updates.", to(ben).getFirst().getError());
    }

    @Test
    void dayOffDecisionsAndAnnouncementsAreEmailedToo() {
        Long r = dayOffService.create(new CreateDayOffRequest(ana, List.of(monday.plusDays(2)), null, null)).getFirst().id();
        dayOffService.approve(r, "Have fun");
        Notification decision = to(ana).getFirst();
        assertTrue(decision.getSubject().matches("Your day off on \\w+, \\w+ \\d+ was approved"), decision.getSubject());
        assertTrue(decision.getBody().endsWith("Note from your manager: Have fun"), decision.getBody());

        var result = notificationService.announce(area, "Store meeting Friday 9am");
        assertEquals(2, result.recipients());
        assertEquals(1, result.reachable(), "Ben turned email off and has no WhatsApp");
        Notification note = to(ana).getLast();
        assertEquals("Message from Alex Admin (Mail Area)", note.getSubject().replaceAll("from .+ \\(", "from Alex Admin ("));
        assertTrue(note.getBody().contains("Store meeting Friday 9am"));
    }

    @Test
    void peopleCanTurnEmailOffAndOnThemselves() {
        TestUsers.signInAs(ana, Role.EMPLOYEE, area);
        assertFalse(authService.setEmailUpdates(false).emailOptIn());
        assertTrue(authService.setEmailUpdates(true).emailOptIn());
    }

    @Test
    void theSenderEmailsWithAKeyThatPreventsDuplicates() {
        scheduleService.publish(scheduleService.generate(area, monday).id());
        Notification mail = to(ana).getFirst();
        List<String> keys = new ArrayList<>();
        List<String> html = new ArrayList<>();
        EmailClient fake = (address, subject, text, body, key) -> {
            keys.add(key);
            html.add(body);
            return new SendResult(true, "re_123", null, false);
        };
        EmailSettings mailOn = new EmailSettings(true, "re_test", "AllianceTel <schedule@alliancetel.example>", "",
                "https://api.resend.com");
        WhatsAppSettings waOff = new WhatsAppSettings(false, "", "", "v22.0", "en_US", "", "a", "b", "c", "d",
                "https://graph.facebook.com");
        new WhatsAppDispatcher(notifications, waOff, (t, a, b, c) -> null, mailOn, fake).dispatch();

        assertEquals(NotificationStatus.SENT, mail.getStatus());
        assertEquals("re_123", mail.getProviderMessageId());
        assertEquals(List.of("alliancetel-notification-" + mail.getId()), keys);
        assertTrue(html.getFirst().contains("Harbor 10am–8pm") && html.getFirst().contains("<h1"), html.getFirst());
    }

    @Test
    void htmlIsEscapedAndLinksBecomeAButton() {
        String html = EmailHtml.render("Hi <you>", "Hello & <b>bold</b>\n\n• Mon Off\n\nSee it anytime: https://alliancetel.example/schedules?week=1");
        assertTrue(html.contains("Hi &lt;you&gt;"));
        assertTrue(html.contains("Hello &amp; &lt;b&gt;bold&lt;/b&gt;"));
        assertTrue(html.contains("href=\"https://alliancetel.example/schedules?week=1\""));
        assertFalse(html.contains("See it anytime"), "the link line becomes the button");
        assertEquals("The example.com domain is not verified.",
                ResendEmailClient.errorText("{\"statusCode\":403,\"name\":\"validation_error\",\"message\":\"The example.com domain is not verified.\"}", 403));
    }
}
