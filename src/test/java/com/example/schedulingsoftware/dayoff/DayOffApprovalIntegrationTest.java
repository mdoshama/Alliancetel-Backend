package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayOffImpact;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ImpactLevel;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Approving a day off after the week's schedule was made, and the staffing check shown before approving. */
@SpringBootTest
@Transactional
class DayOffApprovalIntegrationTest {

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
    DayOffRequestRepository dayOffRequestRepository;

    Long area;
    Long store;
    Long anna;
    Long ben;
    Long cara;
    LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(1);

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Approval Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Main", "MAIN", null, 2, true, null, null)).id();
        anna = employee("A-1", "Anna");
        ben = employee("A-2", "Ben");
        cara = employee("A-3", "Cara");
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void approvingAfterTheScheduleWasMadeTakesThemOffThatDay() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(7, row(s, anna).scheduledWorkDays(), "no automatic days off");
        DayCell workDay = workDayWithFullStaff(s, anna);

        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null, null)).getFirst().id();
        DayOffResponse approved = dayOffService.approve(request, null);

        assertNotNull(approved.scheduleUpdate());
        assertTrue(approved.scheduleUpdate().contains("is now off"), approved.scheduleUpdate());
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, day(after, anna, workDay.date()).type());
        assertNull(day(after, anna, workDay.date()).storeId());
        // The approved day is simply her day off: no other day changes.
        assertEquals(6, row(after, anna).scheduledWorkDays());
        assertTrue(!approved.scheduleUpdate().contains("regular"), approved.scheduleUpdate());

        // Auto-fill and manual edits can't put her back to work that day.
        ScheduleResponse filled = scheduleService.autoAssignStores(after.id());
        assertEquals(DayType.REQUESTED_OFF, day(filled, anna, workDay.date()).type());
        Long entry = day(filled, anna, workDay.date()).entryId();
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(after.id(), entry, DayType.WORK, store));
        assertTrue(e.getMessage().contains("approved day off"), e.getMessage());
    }

    @Test
    void approvingWithoutAScheduleChangesNothingYet() {
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(monday.plusDays(2)), null, null)).getFirst().id();
        assertNull(dayOffService.approve(request, null).scheduleUpdate());
        // Generating later honours it.
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.REQUESTED_OFF, day(s, anna, monday.plusDays(2)).type());
    }

    @Test
    void impactShowsWhetherStoresStayCovered() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate date = workDayWithFullStaff(s, anna).date();
        Long annaRequest = dayOffService.create(new CreateDayOffRequest(anna, List.of(date), null, null)).getFirst().id();
        // A normal request for the same day at the same store is refused; an emergency one is allowed.
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(date), null, null)));
        dayOffService.create(new CreateDayOffRequest(ben, List.of(date), "Family emergency", true));

        // 3 working, minimum 2: after approving, 2 are left for 2 needed.
        DayOffImpact tight = scheduleService.dayOffImpact(annaRequest);
        assertEquals(ImpactLevel.TIGHT, tight.level());
        assertEquals(3, tight.workingBefore());
        assertEquals(2, tight.workingAfter());
        assertEquals(2, tight.neededThatDay());
        assertNull(tight.replacementWorkDay(), "no regular day off is given back");
        assertEquals(1, tight.alsoRequested().size(), "Ben also asked for that day");
        assertEquals(1, tight.sameStoreOff().size(), "Ben is at the same store");
        assertTrue(tight.newShortages().isEmpty());

        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 3, true, null, null));
        DayOffImpact shortImpact = scheduleService.dayOffImpact(annaRequest);
        assertEquals(ImpactLevel.SHORT, shortImpact.level());
        assertTrue(shortImpact.newShortages().stream().anyMatch(g -> g.date().equals(date) && g.scheduled() == 2));
        assertTrue(shortImpact.summary().contains("Main"), shortImpact.summary());

        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 1, true, null, null));
        assertEquals(ImpactLevel.OK, scheduleService.dayOffImpact(annaRequest).level());

        // Checking the impact never changes the schedule.
        assertEquals(DayType.WORK, day(scheduleService.find(area, monday).orElseThrow(), anna, date).type());
    }

    @Test
    void aDayThatIsShortEitherWayIsNotCalledCovered() {
        // Minimum 4 with only 3 people: every day is short whatever happens.
        storeService.update(store, new StoreRequest(area, "Main", "MAIN", null, 4, true, null, null));
        ScheduleResponse s = dayOff(scheduleService.generate(area, monday), anna, 0);
        LocalDate offDay = row(s, anna).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow().date();
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(offDay), null, null)).getFirst().id();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        // It is already her day off, so approving changes nothing, but the day is still short.
        assertTrue(impact.newShortages().isEmpty());
        assertTrue(impact.existingShortages() > 0);

        LocalDate workDay = workDayWithFullStaff(s, anna).date();
        // A normal request is refused (the day is full); an emergency one still goes to the manager.
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay), null, null)));
        Long second = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay), null, true)).getFirst().id();
        DayOffImpact worse = scheduleService.dayOffImpact(second);
        assertEquals(ImpactLevel.SHORT, worse.level(), "3 of 4 becoming 2 of 4 is worse, even though it was short already");
    }

    @Test
    void onlyOnePersonPerStoreCanBeOffOnADay() {
        for (int i = 4; i <= 7; i++) {
            employee("A-" + i, "Extra" + i); // enough people that the area limit isn't what stops anyone here
        }
        LocalDate wed = monday.plusDays(2);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null));

        // Ben is at the same store: refused, with a message naming who has it.
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, null)));
        assertTrue(e.getMessage().contains("Anna already has"), e.getMessage());
        assertEquals(1, dayOffService.takenDays(ben, monday, monday.plusDays(6)).size());

        // Another day is fine; so is the same day at another store or for a floater.
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wed.plusDays(1)), null, null));
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 1, true, null, null)).id();
        employeeService.changeHomeStore(cara, other);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(wed), null, null));
        Long floater = employeeService.create(new EmployeeRequest("A-9", "Fay", "Floater", "a-9@test.example", null,
                Role.EMPLOYEE, area, null, 6, true, null)).employee().id();
        dayOffService.create(new CreateDayOffRequest(floater, List.of(wed), null, null));
        assertTrue(dayOffService.takenDays(floater, monday, monday.plusDays(6)).isEmpty());

        // A rejected request frees the day again.
        Long annaThu = dayOffService.create(new CreateDayOffRequest(anna, List.of(wed.plusDays(2)), null, null))
                .getFirst().id();
        dayOffService.reject(annaThu, "Busy");
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wed.plusDays(2)), null, null));
    }

    @Test
    void aDayIsFullWhenTheAreaCannotSpareAnotherPerson() {
        // Main needs 2; Cara moves to a store with no minimum. 3 people, so one can be off each day.
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 0, true, null, null)).id();
        employeeService.changeHomeStore(cara, other);
        LocalDate wed = monday.plusDays(2);
        Long annaWed = dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null)).getFirst().id();

        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(cara, List.of(wed), null, null)));
        assertTrue(e.getMessage().contains("is full") && e.getMessage().contains("Anna"), e.getMessage());
        var taken = dayOffService.takenDays(cara, monday, monday.plusDays(6));
        assertEquals(1, taken.size());
        assertTrue(taken.getFirst().full());
        assertEquals(wed, taken.getFirst().date());

        // Other days are open, an emergency still goes through, and a rejected request frees the day.
        dayOffService.create(new CreateDayOffRequest(cara, List.of(wed.plusDays(1)), null, null));
        dayOffService.reject(annaWed, "Busy");
        dayOffService.create(new CreateDayOffRequest(cara, List.of(wed), null, null));
        BusinessRuleException again = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, null)));
        assertTrue(again.getMessage().contains("is full"), again.getMessage());
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, true)).size());
    }

    @Test
    void withAScheduleTheLimitCountsWhoIsAlreadyOff() {
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 0, true, null, null)).id();
        employeeService.changeHomeStore(cara, other);
        // Anna is given Wednesday off on the schedule, so only Ben and Cara work then: nobody else can go.
        ScheduleResponse s = dayOff(dayOff(scheduleService.generate(area, monday), anna, 2), cara, 4);
        LocalDate busy = monday.plusDays(2);
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(cara, List.of(busy), null, null)));
        assertTrue(e.getMessage().contains("is full"), e.getMessage());
        // Asking for a day you already have off in the schedule is always fine.
        LocalDate caraOff = row(s, cara).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow().date();
        dayOffService.create(new CreateDayOffRequest(cara, List.of(caraOff), null, null));
    }

    @Test
    void emergencyRequestsSkipTheStoreRuleAndWeeklyLimitButNeedApproval() {
        LocalDate tue = monday.plusDays(1);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(tue, tue.plusDays(1)), null, null)); // her 2 for the week
        DayOffResponse urgent = dayOffService.create(
                new CreateDayOffRequest(anna, List.of(tue.plusDays(2)), "Hospital", true)).getFirst();
        assertTrue(urgent.emergency());
        assertEquals(DayOffStatus.PENDING, urgent.status(), "still needs a manager");
        // Doesn't use up the limit either: Ben's emergency on Anna's day is allowed too.
        assertTrue(dayOffService.create(new CreateDayOffRequest(ben, List.of(tue), null, true)).getFirst().emergency());
        // A normal one for Anna that week is still over the limit.
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(tue.plusDays(4)), null, null)));
        assertTrue(scheduleService.dayOffImpact(urgent.id()).emergency());
    }

    @Test
    void impactWorksBeforeTheWeekIsScheduled() {
        Long request = dayOffService.create(new CreateDayOffRequest(cara, List.of(monday.plusDays(4)), null, null)).getFirst().id();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        assertNull(impact.scheduleStatus());
        assertNotNull(impact.summary());
        assertEquals(2, impact.neededThatDay());
    }

    /** A day she works on which all three people are working. */
    @Test
    void approveManyApprovesWhatFitsAndLeavesTheRestPending() {
        for (int i = 4; i <= 5; i++) {
            employee("A-" + i, "Extra" + i);
        }
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 0, true, null, null)).id();
        employeeService.changeHomeStore(cara, other, true);
        // Main needs 2 and has 4 people (Anna, Ben, Extra4, Extra5); Cara is primary at a store with no minimum,
        // so she is never sent to cover Main.
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate tue = monday.plusDays(1);
        LocalDate wed = monday.plusDays(2);
        Long annaTue = dayOffService.create(new CreateDayOffRequest(anna, List.of(tue), null, null)).getFirst().id();
        Long caraTue = dayOffService.create(new CreateDayOffRequest(cara, List.of(tue), null, null)).getFirst().id();
        // Ben and Extra4 both ask for Wednesday (emergency, past the one-per-store rule): only one of them fits.
        Long benWed = dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, true)).getFirst().id();
        Long ex = employeeIdByName(s, "Extra4");
        Long exWed = dayOffService.create(new CreateDayOffRequest(ex, List.of(wed), null, true)).getFirst().id();
        Long extra5 = employeeIdByName(s, "Extra5");
        Long ex5Wed = dayOffService.create(new CreateDayOffRequest(extra5, List.of(wed), null, true)).getFirst().id();

        ScheduleResponse withRequests = scheduleService.find(area, monday).orElseThrow();
        assertEquals(5, withRequests.requests().size());
        assertTrue(withRequests.requests().getFirst().canReview());

        var result = dayOffService.approveMany(List.of(ex5Wed, exWed, benWed, caraTue, annaTue), true);
        // Tuesday: both fit. Wednesday: Ben first (lowest id), then Extra4 leaves 2 of 2; Extra5 would leave Main short.
        assertEquals(List.of(annaTue, caraTue, benWed, exWed),
                result.approved().stream().map(DayOffResponse::id).toList());
        assertEquals(1, result.skipped().size());
        assertEquals(ex5Wed, result.skipped().getFirst().id());
        assertTrue(result.skipped().getFirst().reason().contains("Main"), result.skipped().getFirst().reason());

        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, day(after, anna, tue).type());
        assertEquals(DayType.WORK, day(after, extra5, wed).type(), "skipped: still working, still pending");
        assertEquals(1, after.requests().size());
        assertEquals(after.requests().getFirst().id(), day(after, extra5, wed).request().id());

        // Without "only if covered", it is approved anyway; done ones are reported, not approved twice.
        var forced = dayOffService.approveMany(List.of(ex5Wed, annaTue), false);
        assertEquals(1, forced.approved().size());
        assertTrue(forced.skipped().getFirst().reason().startsWith("Already approved"));
    }

    @Test
    void anApprovedDayOffAlwaysShowsAsOffOnTheSchedule() {
        LocalDate thu = monday.plusDays(3);
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(thu), null, null)).getFirst().id();
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.WORK, day(s, anna, thu).type(), "pending: still working");

        // Approved without going through approve() (e.g. data from before this rule): opening the week fixes it.
        DayOffRequest r = dayOffRequestRepository.findById(request).orElseThrow();
        r.setStatus(DayOffStatus.APPROVED);
        ScheduleResponse opened = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, day(opened, anna, thu).type());
        assertNull(day(opened, anna, thu).storeId());

        // It can't be changed by hand, and nobody can be marked "approved off" without a request.
        Long entry = day(opened, anna, thu).entryId();
        assertThrows(BusinessRuleException.class, () -> scheduleService.updateEntry(opened.id(), entry, DayType.OFF, null));
        Long benThu = day(opened, ben, thu).entryId();
        assertThrows(BusinessRuleException.class,
                () -> scheduleService.updateEntry(opened.id(), benThu, DayType.REQUESTED_OFF, null));
    }

    /** Schedules start with everyone working; a manager gives days off on the schedule. */
    private ScheduleResponse dayOff(ScheduleResponse s, Long employeeId, int day) {
        return scheduleService.updateEntry(s.id(), row(s, employeeId).days().get(day).entryId(), DayType.OFF, null);
    }

    private static Long employeeIdByName(ScheduleResponse s, String firstName) {
        return s.rows().stream().filter(r -> r.employeeName().startsWith(firstName + " ")).findFirst().orElseThrow()
                .employeeId();
    }

    private static DayCell workDayWithFullStaff(ScheduleResponse s, Long employeeId) {
        return row(s, employeeId).days().stream()
                .filter(c -> c.type() == DayType.WORK)
                .filter(c -> s.dailyHeadCount().get(s.dates().indexOf(c.date())) == 3)
                .findFirst().orElseThrow();
    }

    private Long employee(String code, String name) {
        return employeeService.create(new EmployeeRequest(code, name, "Tester", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, null)).employee().id();
    }

    private static EmployeeRow row(ScheduleResponse s, Long employeeId) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow();
    }

    private static DayCell day(ScheduleResponse s, Long employeeId, LocalDate date) {
        return row(s, employeeId).days().stream().filter(c -> c.date().equals(date)).findFirst().orElseThrow();
    }
}
