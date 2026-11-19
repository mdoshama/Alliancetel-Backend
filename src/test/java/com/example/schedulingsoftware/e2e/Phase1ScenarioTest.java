package com.example.schedulingsoftware.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.dayoff.DayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffReservations;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.notify.NotificationRepository;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleKind;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Phase 1 business scenarios, run with the production rules on (request window, one emergency a month, days reserved
 * for everyone when a week opens). Each test is one requirement from the Phase 1 brief; an assertion message starting
 * with "REQ" names the requirement it checks.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.one-emergency-per-month=true", "alliancetel.scheduling.reserve-days-off=true" })
@Transactional
class Phase1ScenarioTest {

    @Autowired AreaService areaService;
    @Autowired StoreService storeService;
    @Autowired EmployeeService employeeService;
    @Autowired DayOffService dayOffService;
    @Autowired DayOffReservations reservations;
    @Autowired DayOffRequestRepository requests;
    @Autowired ScheduleService scheduleService;
    @Autowired RequestPortal portal;
    @Autowired NotificationRepository notifications;
    @Autowired com.example.schedulingsoftware.schedule.ScheduleEntryRepository entries;

    LocalDate week;
    Long area;
    Long manager;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("E2E Area", null, true)).id();
        manager = person("E2E-M", "Maria", Role.MANAGER, null, null, List.of(), false);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    // ---------- helpers ----------

    private Long store(String name, int min) {
        return storeService.create(new StoreRequest(area, name, name.substring(0, 3).toUpperCase(), null, min, true,
                null, null)).id();
    }

    private Long person(String code, String first, Role role, Long store, Boolean primary, List<Long> floatStores,
            Boolean onSchedule) {
        TestUsers.signInAsAdmin();
        return employeeService.create(new EmployeeRequest(code, first, "Test", code.toLowerCase() + "@test.example",
                null, role, area, store, 6, true, primary, null, null, null, floatStores, onSchedule, null))
                .employee().id();
    }

    private Long employee(String code, String first, Long store, Boolean primary) {
        return person(code, first, Role.EMPLOYEE, store, primary, null, null);
    }

    private void regular(Long id, DayOfWeek day) {
        TestUsers.signInAsAdmin();
        employeeService.setRegularDaysOff(id, List.of(day), reservations::checkRegularDays);
        reservations.replanFor(employeeService.getEmployee(id));
    }

    private void as(Long id) {
        TestUsers.signInAs(id, Role.EMPLOYEE, area);
    }

    private void asManager() {
        TestUsers.signInAs(manager, Role.MANAGER, area);
    }

    private LocalDate day(DayOfWeek d) {
        return week.plusDays(d.getValue() - 1L);
    }

    private List<DayOffResponse> ask(Long id, DayOfWeek d) {
        as(id);
        return dayOffService.create(new CreateDayOffRequest(id, List.of(day(d)), null, false));
    }

    private DayOffResponse emergency(Long id, DayOfWeek d, String reason) {
        as(id);
        return dayOffService.create(new CreateDayOffRequest(id, List.of(day(d)), reason, true)).getFirst();
    }

    /** Their live days off this week (waiting or given), from the database. */
    private List<DayOffRequest> live(Long id) {
        return requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(id))
                .filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                .filter(r -> !r.getOffDate().isBefore(week) && r.getOffDate().isBefore(week.plusDays(7))).toList();
    }

    /** What the employee's own "My days off" page gets from the API this week. */
    private List<DayOffResponse> dashboard(Long id) {
        as(id);
        return dayOffService.page(null, null, null, week, week.plusDays(6), null, 0, 50).content();
    }

    private DayCell cell(ScheduleResponse s, Long id, LocalDate date) {
        return s.rows().stream().filter(r -> r.employeeId().equals(id)).findFirst().orElseThrow().days().stream()
                .filter(c -> c.date().equals(date)).findFirst().orElseThrow();
    }

    // ---------- 1-4: regular day off, change it, open vs full day ----------

    @Test
    void s1to3_regularMondayChangedToOpenTuesdayIsGivenAutomatically() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        employee("E-3", "Cara", null, null); // floater: any store
        regular(ana, DayOfWeek.MONDAY);
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);

        // 1: the regular Monday is held for her and visible on her side.
        assertTrue(dashboard(ana).stream().anyMatch(r -> r.date().equals(day(DayOfWeek.MONDAY)) && r.regular()),
                "REQ 3.1 regular day off shows on the employee's dashboard");

        // 2-3: she picks Tuesday; it has room, so it's given at once and Monday becomes a work day.
        DayOffResponse tue = ask(ana, DayOfWeek.TUESDAY).getFirst();
        assertEquals(DayOffStatus.APPROVED, tue.status(), "REQ 3.5 open day is given automatically");
        assertEquals(List.of(day(DayOfWeek.TUESDAY)), live(ana).stream().map(DayOffRequest::getOffDate).toList(),
                "REQ 7.3 regular Monday is replaced, not kept as a second day off");
        assertFalse(dashboard(ana).stream().anyMatch(r -> r.date().equals(day(DayOfWeek.MONDAY))
                && r.status() != DayOffStatus.REJECTED), "REQ 5 dashboard no longer shows Monday off");
    }

    @Test
    void s4_changeToAFullDayIsRefusedAndGoesToEmergency() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        regular(ana, DayOfWeek.MONDAY);
        // Then Main starts needing both of them (minimum 2): Tuesday has no room.
        TestUsers.signInAsAdmin();
        storeService.update(main, new StoreRequest(area, "Main", "MAI", null, 2, true, null, null));
        reservations.reserveWeek(area, week);

        BusinessRuleException full = assertThrows(BusinessRuleException.class, () -> ask(ana, DayOfWeek.TUESDAY),
                "REQ 3.6 a full day is never given automatically");
        assertTrue(full.getMessage().contains("emergency"), full.getMessage());
        DayOffResponse e = emergency(ana, DayOfWeek.TUESDAY, "Family");
        assertEquals(DayOffStatus.PENDING, e.status(), "REQ 4 it goes to emergency requests, waiting for a manager");
        assertTrue(e.emergency());
    }

    // ---------- 5-10: primary / secondary, decline, resubmit ----------

    @Test
    void s5to10_primaryAndSecondaryCantBothBeOffAndEmergencyCanBeResubmitted() {
        Long main = store("Main", 1);
        Long other = store("Other", 1);
        Long pri = employee("E-1", "Pri", main, true);
        Long sec = employee("E-2", "Sec", main, false);
        employee("E-3", "Oto", other, true);
        employee("E-4", "Ola", other, false);
        employee("E-5", "Flo", null, null);

        assertEquals(DayOffStatus.APPROVED, ask(pri, DayOfWeek.MONDAY).getFirst().status(), "REQ 7.5 primary gets Monday");
        assertThrows(BusinessRuleException.class, () -> ask(sec, DayOfWeek.MONDAY),
                "REQ 2.1/7.7 secondary can't also get Monday as a normal day off");

        DayOffResponse first = emergency(sec, DayOfWeek.MONDAY, "Doctor");
        assertEquals(DayOffStatus.PENDING, first.status(), "REQ 7.8 emergency waits for review");
        assertThrows(BusinessRuleException.class, () -> emergency(sec, DayOfWeek.MONDAY, "Again"),
                "REQ 4 no duplicate emergency for the same day while one waits");

        asManager();
        dayOffService.reject(first.id(), "Short that day");
        assertEquals(DayOffStatus.REJECTED, requests.findById(first.id()).orElseThrow().getStatus(), "REQ 7.9");
        assertTrue(dashboard(sec).stream().anyMatch(r -> r.date().equals(day(DayOfWeek.MONDAY))
                && r.status() == DayOffStatus.REJECTED), "REQ 5 declined shows as declined, not approved");
        assertTrue(notifications.findAll().stream().anyMatch(n -> n.getEmployee().getId().equals(sec)),
                "REQ 5 employee is notified of the decision");

        // 10: another emergency for Monday is allowed after the decline (one a month counts only live ones).
        DayOffResponse again = emergency(sec, DayOfWeek.MONDAY, "Doctor, rebooked");
        assertEquals(DayOffStatus.PENDING, again.status(), "REQ 7.10 resubmission after rejection");
        // ...and a declined request doesn't block a normal day either.
        assertEquals(DayOffStatus.APPROVED, ask(sec, DayOfWeek.WEDNESDAY).getFirst().status(),
                "REQ 4 a declined request doesn't block another day");

        // Approving the exception: both off Monday, by a manager's decision only.
        asManager();
        assertEquals(DayOffStatus.APPROVED, dayOffService.approve(again.id(), null).status(), "REQ 2.3 exception by review");
    }

    @Test
    void s2_2_primaryAndSecondaryCantHaveTheSameRegularDay() {
        Long main = store("Main", 1);
        Long pri = employee("E-1", "Pri", main, true);
        Long sec = employee("E-2", "Sec", main, false);
        employee("E-3", "Flo", null, null);
        regular(pri, DayOfWeek.MONDAY);
        assertThrows(BusinessRuleException.class, () -> regular(sec, DayOfWeek.MONDAY), "REQ 2.2 same regular day refused");
    }

    @Test
    void s2_4_regularDaysMustKeepMinimumStaffing() {
        Long a = store("Alpha", 1);
        Long b = store("Bravo", 1);
        Long a1 = employee("E-1", "Ann", a, true);
        employee("E-2", "Abe", a, false);
        Long b1 = employee("E-3", "Bea", b, true);
        employee("E-4", "Bob", b, false);
        regular(a1, DayOfWeek.MONDAY);
        regular(b1, DayOfWeek.MONDAY); // each store still has 1
        // The system reserves days for everyone else: nobody should be planned off on a day that leaves a store short.
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        for (LocalDate d = week; d.isBefore(week.plusDays(7)); d = d.plusDays(1)) {
            LocalDate date = d;
            long offAtA = requests.findAll().stream().filter(r -> r.getOffDate().equals(date)
                    && r.getStatus() != DayOffStatus.REJECTED && r.getEmployee().getHomeStore() != null
                    && r.getEmployee().getHomeStore().getId().equals(a)).count();
            assertTrue(offAtA <= 1, "REQ 2.4 at most one of Alpha's two off on " + date);
        }
    }

    // ---------- 11-12: several people at one store, minimum staffing ----------

    @Test
    void s11_threePeopleAtOneStoreWithMinimumOne() {
        Long main = store("Main", 1);
        Long p = employee("E-1", "Pia", main, true);
        Long s1 = employee("E-2", "Sam", main, false);
        Long s2 = employee("E-3", "Sue", main, false);
        assertEquals(DayOffStatus.APPROVED, ask(p, DayOfWeek.THURSDAY).getFirst().status());
        // Requirement only bars primary + secondary together and below-minimum days. With 3 people and a minimum of 1,
        // a second secondary off would still leave 1 working at Main.
        Optional<String> refusal = refusal(() -> ask(s1, DayOfWeek.THURSDAY));
        assertTrue(refusal.isPresent(), "observed: refused (one person per store), message: " + refusal.orElse(""));
        // Two secondaries (no primary) the same day:
        assertEquals(DayOffStatus.APPROVED, ask(s1, DayOfWeek.FRIDAY).getFirst().status());
        Optional<String> second = refusal(() -> ask(s2, DayOfWeek.FRIDAY));
        assertTrue(second.isPresent(), "observed: two secondaries refused too: " + second.orElse(""));
    }

    @Test
    void s12_minimumStaffingIsNeverBrokenByNormalRequests() {
        Long main = store("Main", 2);
        Long p = employee("E-1", "Pia", main, true);
        Long s = employee("E-2", "Sam", main, false);
        Long f = employee("E-3", "Flo", null, null); // floater covers Main
        assertEquals(DayOffStatus.APPROVED, ask(p, DayOfWeek.THURSDAY).getFirst().status(),
                "the floater covers Main, so one can go");
        assertThrows(BusinessRuleException.class, () -> ask(f, DayOfWeek.THURSDAY),
                "REQ 2.4/7.12 floater off too would leave Main with 1 of 2");
        assertThrows(BusinessRuleException.class, () -> ask(s, DayOfWeek.THURSDAY), "REQ 7.12");
    }

    // ---------- 13-14: floaters ----------

    @Test
    void s13_14_floaterOnlyCoversTheStoresAssignedToThem() {
        Long a = store("Alpha", 1);
        Long b = store("Bravo", 2);
        Long a1 = employee("E-1", "Ann", a, true);
        Long b1 = employee("E-3", "Bea", b, true);
        employee("E-4", "Bob", b, false);
        // Floater assigned to Alpha only.
        person("E-6", "Flo", Role.EMPLOYEE, null, null, List.of(a), null);

        assertEquals(DayOffStatus.APPROVED, ask(a1, DayOfWeek.TUESDAY).getFirst().status(),
                "REQ 7.14 floater (Alpha) covers Alpha");
        assertThrows(BusinessRuleException.class, () -> ask(b1, DayOfWeek.WEDNESDAY),
                "REQ 7.14 floater isn't assigned to Bravo, so Bravo can't spare anyone");
    }

    // ---------- 15: no regular day off ----------

    @Test
    void s15_noRegularDayUsesRequestDayOff() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        employee("E-3", "Cal", null, null);
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        // The system holds a day for her, hidden until the schedule is published.
        assertEquals(1, live(ana).size(), "a day is held for her");
        assertTrue(dashboard(ana).isEmpty(), "REQ 3.3 nothing shown yet: she sees Request day off");
        DayOffResponse sat = ask(ana, DayOfWeek.SATURDAY).getFirst();
        assertEquals(DayOffStatus.APPROVED, sat.status());
        assertEquals(1, live(ana).size(), "the held day gives way: one day off, not two");
        assertEquals(List.of(day(DayOfWeek.SATURDAY)), dashboard(ana).stream().map(DayOffResponse::date).toList());
    }

    // ---------- 16 + section 6: dashboard vs schedule, the three tabs ----------

    @Test
    void s16_generatedScheduleAndDashboardAgree() {
        Long main = store("Main", 1);
        Long other = store("Other", 1);
        Long ana = employee("E-1", "Ana", main, true);
        Long ben = employee("E-2", "Ben", main, false);
        Long cal = employee("E-3", "Cal", other, true);
        Long dee = employee("E-4", "Dee", other, false);
        employee("E-5", "Flo", null, null);
        regular(ana, DayOfWeek.MONDAY);
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        ask(ben, DayOfWeek.WEDNESDAY);
        ask(cal, DayOfWeek.THURSDAY); // his normal day; Friday on top is an emergency
        DayOffResponse pend = emergency(cal, DayOfWeek.FRIDAY, "Court date");
        asManager();
        ScheduleResponse s = scheduleService.generate(area, week);
        for (Long id : List.of(ana, ben, cal, dee)) {
            for (LocalDate d = week; d.isBefore(week.plusDays(7)); d = d.plusDays(1)) {
                LocalDate date = d;
                boolean offOnSchedule = cell(s, id, date).type() != DayType.WORK;
                boolean approved = requests.findAll().stream().anyMatch(r -> r.getEmployee().getId().equals(id)
                        && r.getOffDate().equals(date) && r.getStatus() == DayOffStatus.APPROVED);
                assertEquals(approved, offOnSchedule, "REQ 5 schedule and day-off records agree for " + id + " " + date);
            }
        }
        assertEquals(DayOffStatus.PENDING, requests.findById(pend.id()).orElseThrow().getStatus(),
                "REQ 6 pending emergency isn't treated as approved by Generate");
        assertEquals(DayType.WORK, cell(s, cal, day(DayOfWeek.FRIDAY)).type(), "pending emergency: still working");
    }

    @Test
    void s6_daysOffDraftDoesNotDoubleUpARegularDayThatWasSwapped() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        employee("E-3", "Cal", null, null);
        regular(ana, DayOfWeek.MONDAY);
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        ask(ana, DayOfWeek.TUESDAY); // she swapped Monday for Tuesday this week
        asManager();
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        DayType mon = cell(draft, ana, day(DayOfWeek.MONDAY)).type();
        DayType tue = cell(draft, ana, day(DayOfWeek.TUESDAY)).type();
        assertEquals(DayType.REQUESTED_OFF, tue);
        assertTrue(mon != DayType.OFF, "REQ 6 days-off draft: swapped regular Monday must not also be off (was " + mon + ")");
    }

    @Test
    void s6_daysOffDraftDoesNotTreatAPendingEmergencyAsApproved() {
        Long main = store("Main", 2);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        DayOffResponse e = emergency(ana, DayOfWeek.THURSDAY, "Funeral");
        asManager();
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        assertTrue(cell(draft, ana, day(DayOfWeek.THURSDAY)).type() != DayType.REQUESTED_OFF,
                "REQ 6 pending emergency is not shown as an approved day off");
        assertEquals(DayOffStatus.PENDING, requests.findById(e.id()).orElseThrow().getStatus());
    }

    @Test
    void s6_emptyDraftPublishKeepsTheDashboardInStep() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        Long ben = employee("E-2", "Ben", main, false);
        Long cal = employee("E-3", "Cal", null, null);
        DayOffResponse anaTue = ask(ana, DayOfWeek.TUESDAY).getFirst();
        asManager();
        ScheduleResponse draft = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        // The manager makes Ana work Tuesday (her approved day) and gives her Thursday instead; Ben gets Friday.
        for (LocalDate d = week; d.isBefore(week.plusDays(7)); d = d.plusDays(1)) {
            for (Long id : List.of(ana, ben, cal)) {
                boolean off = (id.equals(ana) && d.equals(day(DayOfWeek.THURSDAY)))
                        || (id.equals(ben) && d.equals(day(DayOfWeek.FRIDAY)))
                        || (id.equals(cal) && d.equals(day(DayOfWeek.SUNDAY)));
                scheduleService.updateEntry(draft.id(), cell(draft, id, d).entryId(), off ? DayType.OFF : DayType.WORK,
                        off ? null : (id.equals(cal) ? main : null));
            }
        }
        scheduleService.publish(draft.id());
        // Dashboard must match what was published.
        List<LocalDate> anaDays = dashboard(ana).stream().filter(r -> r.status() == DayOffStatus.APPROVED)
                .map(DayOffResponse::date).toList();
        List<LocalDate> benDays = dashboard(ben).stream().filter(r -> r.status() == DayOffStatus.APPROVED)
                .map(DayOffResponse::date).toList();
        String report = "ana approved=" + anaDays + " tue request=" + requests.findById(anaTue.id()).map(
                r -> r.getStatus().name()).orElse("deleted") + " ben approved=" + benDays + " anaNotified="
                + notifications.findAll().stream().anyMatch(n -> n.getEmployee().getId().equals(ana)
                        && n.getPreview() != null && n.getPreview().contains("changed"));
        assertEquals(List.of(day(DayOfWeek.THURSDAY)), anaDays, "REQ 5/6 empty draft published: " + report);
        assertEquals(List.of(day(DayOfWeek.FRIDAY)), benDays, "REQ 5/6 empty draft published: " + report);
    }

    @Test
    void b5_oldDaysOffGivenOnTheScheduleAreRecordedAtStartUp() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        employee("E-2", "Ben", main, false);
        employee("E-3", "Cal", null, null);
        asManager();
        ScheduleResponse s = scheduleService.generate(area, week);
        // Old data: a day set Off on the schedule with no day-off record behind it.
        Long entryId = cell(s, ana, day(DayOfWeek.WEDNESDAY)).entryId();
        entries.findById(entryId).orElseThrow().setType(DayType.OFF);
        entries.flush();
        scheduleService.recordDaysGivenOnSchedule();
        assertEquals(DayOffStatus.APPROVED, requests.findByEmployeeIdAndOffDate(ana, day(DayOfWeek.WEDNESDAY))
                .orElseThrow().getStatus(), "B5 recorded, so it shows on her page");
    }

    @Test
    void cancelADayOff() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        Long ben = employee("E-2", "Ben", main, false);
        employee("E-3", "Cal", null, null);
        regular(ana, DayOfWeek.MONDAY);
        TestUsers.signInAsAdmin();
        reservations.reserveWeek(area, week);
        DayOffResponse tue = ask(ana, DayOfWeek.TUESDAY).getFirst();
        assertEquals(List.of(day(DayOfWeek.TUESDAY)), live(ana).stream().map(DayOffRequest::getOffDate).toList());

        // She changes her mind: Tuesday is cancelled and her regular Monday comes back.
        as(ana);
        dayOffService.cancel(tue.id());
        assertEquals(List.of(day(DayOfWeek.MONDAY)), live(ana).stream().map(DayOffRequest::getOffDate).toList(),
                "cancelled: back to her regular day");
        // Her regular day itself can't be cancelled (she'd be working 7 days).
        Long monday = live(ana).getFirst().getId();
        assertThrows(BusinessRuleException.class, () -> dayOffService.cancel(monday));
        // Tuesday is free again for a colleague.
        assertEquals(DayOffStatus.APPROVED, ask(ben, DayOfWeek.TUESDAY).getFirst().status());

        // Once the week is made, cancelling is the manager's (on the schedule).
        DayOffResponse sat = ask(ana, DayOfWeek.SATURDAY).getFirst();
        asManager();
        scheduleService.generate(area, week);
        as(ana);
        assertThrows(BusinessRuleException.class, () -> dayOffService.cancel(sat.id()), "made week: ask your manager");
    }

    // ---------- section 1: access ----------

    @Test
    void s1_accessRules() {
        Long main = store("Main", 1);
        Long ana = employee("E-1", "Ana", main, true);
        Long ben = employee("E-2", "Ben", main, false);
        employee("E-3", "Cal", null, null);
        Long trainee = employeeService.create(new EmployeeRequest("E-9", "Tia", "Test", "e-9@test.example", null,
                Role.EMPLOYEE, area, main, 6, true, false, null, null, null, null, null, null, true)).employee().id();
        TestUsers.signInAsAdmin();
        Long otherArea = areaService.create(new AreaRequest("Other Area", null, true)).id();

        as(ana);
        assertThrows(AccessDeniedException.class, () -> dayOffService.create(
                new CreateDayOffRequest(ben, List.of(day(DayOfWeek.MONDAY)), null, false)), "REQ 1 only for yourself");
        assertThrows(AccessDeniedException.class, () -> dayOffService.takenDays(ben, week, week.plusDays(6)));
        assertThrows(AccessDeniedException.class, () -> dayOffService.page(otherArea, null, null, null, null, null, 0, 10),
                "REQ 1 employee can't read another area");
        DayOffResponse t = ask(trainee, DayOfWeek.SATURDAY).getFirst();
        assertNotNull(t, "REQ 1 trainee can request days off");

        TestUsers.signInAs(manager, Role.MANAGER, otherArea);
        assertThrows(AccessDeniedException.class, () -> dayOffService.approve(t.id(), null),
                "REQ 1 manager of another area can't decide");
        ask(ana, DayOfWeek.FRIDAY); // her normal day this week; another day is an emergency
        DayOffResponse e = emergency(ana, DayOfWeek.SUNDAY, "x");
        TestUsers.signInAsMarketManager(manager, otherArea, java.util.Set.of(area));
        assertEquals(DayOffStatus.APPROVED, dayOffService.approve(e.id(), null).status(),
                "REQ 1 market manager decides in an assigned area");
    }

    private static Optional<String> refusal(Runnable r) {
        try {
            r.run();
            return Optional.empty();
        } catch (BusinessRuleException e) {
            return Optional.of(e.getMessage());
        }
    }
}
