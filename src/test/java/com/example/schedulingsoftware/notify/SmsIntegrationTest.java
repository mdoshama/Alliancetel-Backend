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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AuthService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Text messages: who gets them (only people who agreed), what they say, and how the sender sends them. */
@SpringBootTest
@Transactional
@TestPropertySource(properties = {
        // Texting "connected" to an address nothing listens on: tests call the sender directly with a fake.
        "alliancetel.sms.enabled=true", "alliancetel.sms.twilio.account-sid=ACtest",
        "alliancetel.sms.twilio.auth-token=TEST", "alliancetel.sms.twilio.from-number=+16195550100",
        "alliancetel.sms.twilio.api-base-url=http://127.0.0.1:9" })
class SmsIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    NotificationRepository notifications;
    @Autowired
    NotificationService notificationService;
    @Autowired
    AuthService authService;
    @Autowired
    SmsSettings smsSettings;

    Long area;
    Long store;
    Long ana;
    Long ben;
    Long cam;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Text Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Harbor", "HBR", null, 1, true, null, null)).id();
        ana = person("S-1", "Ana", "(619) 555-0142");
        ben = person("S-2", "Ben", "619-555-0143");
        cam = person("S-3", "Cam", null);
        TestUsers.signInAs(ana, Role.EMPLOYEE, area);
        assertTrue(authService.setSms(true).smsOptIn(), "Ana agrees to texts");
        TestUsers.signInAsAdmin();
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, String phone) {
        return employeeService.create(new EmployeeRequest(code, first, "Texter", first.toLowerCase() + ".texter@test.example",
                phone, Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
    }

    private Notification sms(Long employeeId) {
        return notifications.findAll().stream()
                .filter(n -> n.getEmployee().getId().equals(employeeId) && n.getChannel() == NotificationChannel.SMS)
                .reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void publishingTextsOnlyPeopleWhoAgreed() {
        scheduleService.publish(scheduleService.generate(area, monday).id());

        Notification toAna = sms(ana);
        assertEquals(NotificationStatus.QUEUED, toAna.getStatus());
        assertEquals("+16195550142", toAna.getPhone());
        assertTrue(toAna.getBody().startsWith("AllianceTel: Hi Ana, your "), toAna.getBody());
        assertTrue(toAna.getBody().endsWith(" schedule is posted. See it in the AllianceTel app.\nReply STOP to stop"),
                toAna.getBody());
        assertTrue(toAna.getBody().length() <= 160, "one SMS part: " + toAna.getBody().length());
        assertTrue(toAna.getBody().chars().allMatch(c -> c == '\n' || (c >= 0x20 && c <= 0x7E)),
                "basic characters only, so each part holds 160 characters");

        assertEquals(NotificationStatus.NOT_SENT, sms(ben).getStatus());
        assertEquals("Hasn't agreed to text messages yet.", sms(ben).getError());
        assertEquals("No phone number on file.", sms(cam).getError());
    }

    @Test
    void onlyThePersonCanAgreeAndANewNumberNeedsAgreeingAgain() {
        TestUsers.signInAs(cam, Role.EMPLOYEE, area);
        assertThrows(BusinessRuleException.class, () -> authService.setSms(true), "no phone on file");

        TestUsers.signInAsAdmin();
        var current = employeeService.findById(ana);
        employeeService.update(ana, new EmployeeRequest(current.employeeCode(), "Ana", "Texter",
                current.email(), "(619) 555-0199", Role.EMPLOYEE, area, store, 6, true, null));
        assertFalse(employeeRepository.findById(ana).orElseThrow().isSmsOptIn(), "new number: ask again");

        TestUsers.signInAs(ana, Role.EMPLOYEE, area);
        assertTrue(authService.setSms(true).smsOptIn());
        assertTrue(employeeRepository.findById(ana).orElseThrow().getSmsConsentAt() != null, "when they agreed is kept");
        assertFalse(authService.setSms(false).smsOptIn());
    }

    @Autowired
    ScheduleNotifier notifier;

    @Test
    void agreeingSendsTheOptInConfirmationOnce() {
        notifier.smsOptInConfirmation(ana);
        Notification confirmation = sms(ana);
        assertEquals(NotificationKind.SMS_OPT_IN, confirmation.getKind());
        assertEquals(ScheduleNotifier.SMS_OPT_IN_TEXT, confirmation.getBody());
        assertTrue(confirmation.getBody().length() <= 160);
        assertTrue(confirmation.getBody().contains("Msg&data rates may apply") && confirmation.getBody().contains("STOP"));
        long before = notifications.count();
        notifier.smsOptInConfirmation(ben);
        assertEquals(before, notifications.count(), "Ben hasn't agreed, so nothing is sent");
    }

    @Test
    void announcementsCountPeopleReachableByText() {
        TestUsers.signInAs(ben, Role.EMPLOYEE, area);
        authService.setEmailUpdates(false);
        TestUsers.signInAsAdmin();
        var result = notificationService.announce(area, "Store meeting Friday 9am");
        assertEquals(3, result.recipients());
        assertEquals(2, result.reachable(), "Ana and Cam by email (Ana by text too); Ben turned email off, no texts");
        assertTrue(sms(ana).getBody().contains("Store meeting Friday 9am"));
        assertTrue(sms(ana).getBody().startsWith("AllianceTel Text Area ("), sms(ana).getBody());
        assertTrue(notificationService.status().smsConnected());
    }

    @Test
    void theSenderTextsTheMessageAndRecordsTwiliosId() {
        scheduleService.publish(scheduleService.generate(area, monday).id());
        Notification text = sms(ana);
        List<String> sent = new ArrayList<>();
        SmsClient fake = (to, body) -> {
            sent.add(to + "|" + body);
            return new SendResult(true, "SM123", null, false);
        };
        EmailSettings mailOff = new EmailSettings(false, "", "", "", "https://api.resend.com");
        WhatsAppSettings waOff = new WhatsAppSettings(false, "", "", "v22.0", "en_US", "", "a", "b", "c", "d",
                "https://graph.facebook.com");
        new WhatsAppDispatcher(notifications, waOff, (t, a, b, c) -> null, mailOff, (a, b, c, d, e) -> null,
                smsSettings, fake).dispatch();

        assertEquals(NotificationStatus.SENT, text.getStatus());
        assertEquals("SM123", text.getProviderMessageId());
        assertEquals(1, sent.size(), "only Ana agreed");
        assertTrue(sent.getFirst().startsWith("+16195550142|AllianceTel: Hi Ana"));
    }

    @Test
    void twilioErrorsAreShownWithTheirCode() {
        assertEquals("Attempt to send to unsubscribed recipient (21610)", TwilioSmsClient.errorText(
                "{\"code\": 21610, \"message\": \"Attempt to send to unsubscribed recipient\", \"status\": 400}", 400)
                .replaceAll("\\. They replied.*", ""));
        assertEquals("HTTP 503", TwilioSmsClient.errorText("", 503));
        String longText = "x".repeat(1000);
        assertEquals(160, ScheduleNotifier.fitSms(longText).length(), "cut to exactly one part");
        assertTrue(ScheduleNotifier.fitSms(longText).endsWith("...\nReply STOP to stop"));
        assertEquals("a - b, c 'd' \"e\"...\nReply STOP to stop",
                ScheduleNotifier.fitSms("a – b· c ‘d’ “e”…"));
        String week = ScheduleNotifier.smsShort(NotificationKind.WEEK_PUBLISHED, List.of("Ana", "Nov 9–15",
                "Mon 11/9 Oceanside 10am–8pm · Tue 11/10 Off", "https://atdirect.app/schedules?week=2026-11-09"));
        assertEquals("AllianceTel: Hi Ana, your Nov 9-15 schedule is posted. https://atdirect.app/schedules?week=2026-11-09"
                + "\nReply STOP to stop", week);
    }
}
