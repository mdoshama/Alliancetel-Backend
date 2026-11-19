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
    com.example.schedulingsoftware.schedule.WeekRequests weekRequests;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    DayOffRequestRepository dayOffRequestRepository;
    @Autowired
    com.example.schedulingsoftware.notify.NotificationRepository notifications;

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

        // The week is made: a day off a manager or admin enters is their decision, given straight away.
        DayOffResponse approved = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null, null)).getFirst();
        assertEquals(DayOffStatus.APPROVED, approved.status());

        assertNotNull(approved.scheduleUpdate());
        assertTrue(approved.scheduleUpdate().contains("is now off"), approved.scheduleUpdate());
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, day(after, anna, workDay.date()).type());
        assertEquals("MANAGER", day(after, anna, workDay.date()).offSource());
        assertNull(day(after, anna, workDay.date()).storeId());
        // The approved day is simply her day off: no other day changes.
        assertEquals(6, row(after, anna).scheduledWorkDays());
        assertTrue(!approved.scheduleUpdate().contains("regular"), approved.scheduleUpdate());

        // Auto-fill keeps it; the manager can still put her back to work (the day off is then turned down).
        ScheduleResponse filled = scheduleService.autoAssignStores(after.id());
        assertEquals(DayType.REQUESTED_OFF, day(filled, anna, workDay.date()).type());
        Long entry = day(filled, anna, workDay.date()).entryId();
        assertEquals(DayType.WORK, day(scheduleService.updateEntry(after.id(), entry, DayType.WORK, store), anna,
                workDay.date()).type());
        DayOffRequest takenBack = dayOffRequestRepository.findById(approved.id()).orElseThrow();
        assertEquals(DayOffStatus.REJECTED, takenBack.getStatus(), "she works it: the day off is taken back");
        assertEquals(DayOffStatus.APPROVED, takenBack.getPreviousStatus());
        assertTrue(ScheduleService.wasTakenBack(takenBack), takenBack.getDecisionChangeReason());
        assertTrue(notifications.findAll().stream().anyMatch(n -> n.getEmployee().getId().equals(anna)
                && n.getPreview() != null && n.getPreview().contains("was changed")), "she's told");

        // The manager changes their mind: Off again gives her the day back.
        assertEquals(DayType.REQUESTED_OFF, day(scheduleService.updateEntry(after.id(), entry, DayType.OFF, null), anna,
                workDay.date()).type());
        assertEquals(DayOffStatus.APPROVED, dayOffRequestRepository.findById(approved.id()).orElseThrow().getStatus());
    }

    @Test
    void aDayOffTakenBackOnTheScheduleCanBeAskedForAgainAsAnEmergency() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell workDay = workDayWithFullStaff(s, anna);
        DayOffResponse given = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null, null)).getFirst();
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        scheduleService.updateEntry(after.id(), day(after, anna, workDay.date()).entryId(), DayType.WORK, store);

        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        // A normal request isn't possible (the week is made), and an emergency needs a reason.
        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(workDay.date()), null, false)));
        BusinessRuleException noReason = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(workDay.date()), "  ", true)));
        assertTrue(noReason.getMessage().contains("reason"), noReason.getMessage());

        DayOffResponse again = dayOffService.create(
                new CreateDayOffRequest(anna, List.of(workDay.date()), "Doctor", true)).getFirst();
        assertEquals(DayOffStatus.PENDING, again.status());
        assertTrue(again.emergency());
        assertTrue(dayOffRequestRepository.findById(given.id()).isEmpty(), "the taken-back day is replaced");
        // Asking for a taken-back day doesn't use up the month's emergency request.
        assertTrue(dayOffRequestRepository.findById(again.id()).orElseThrow().isAfterTakenBack());
        assertTrue(!dayOffService.window().emergencyUsedThisMonth()
                || !monday.getMonth().equals(LocalDate.now().getMonth()), "still one emergency left this month");
    }

    @Test
    void movingADayOffOnTheScheduleTellsThemTheNewDay() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        DayCell workDay = workDayWithFullStaff(s, anna);
        DayOffResponse given = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay.date()), null, null)).getFirst();
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        // The manager gives her another day off instead: the old one goes back to work.
        DayCell other = after.rows().stream().filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow()
                .days().stream().filter(d -> d.type() == DayType.WORK && !d.date().equals(workDay.date())).findFirst()
                .orElseThrow();
        ScheduleResponse moved = scheduleService.updateEntry(after.id(), other.entryId(), DayType.OFF, null);
        assertEquals(DayType.WORK, day(moved, anna, workDay.date()).type());
        DayOffRequest old = dayOffRequestRepository.findById(given.id()).orElseThrow();
        assertEquals(DayOffStatus.REJECTED, old.getStatus());
        assertTrue(old.getDecisionChangeReason().contains("instead"), old.getDecisionChangeReason());
        // The new day is hers on her side too, not only on the schedule.
        DayOffRequest now = dayOffRequestRepository.findByEmployeeIdAndOffDate(anna, other.date()).orElseThrow();
        assertEquals(DayOffStatus.APPROVED, now.getStatus());
        assertEquals(DayType.OFF, day(moved, anna, other.date()).type());
    }

    @Test
    void eachDayOffSaysWhereItCameFrom() {
        LocalDate tue = monday.plusDays(1);
        LocalDate thu = monday.plusDays(3);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(tue), null, null));
        Long emergency = dayOffService.create(new CreateDayOffRequest(ben, List.of(thu), "Sick", true)).getFirst().id();
        dayOffService.approve(emergency, null);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(monday.plusDays(5)), null, null, true));
        ScheduleResponse s = scheduleService.generate(area, monday, true);
        assertEquals("REQUESTED", day(s, anna, tue).offSource());
        assertEquals("EMERGENCY", day(s, ben, thu).offSource());
        assertEquals("MANAGER", day(s, cara, monday.plusDays(5)).offSource());
        assertNull(day(s, anna, monday).offSource(), "work days have none");
    }

    @Test
    void theWeekAtAGlanceFollowsTheScheduleOnceItIsMade() {
        LocalDate wed = monday.plusDays(2);
        // Before the week is made: the requests count (Anna asked for Wednesday).
        dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null));
        var before = weekRequests.capacity(area, monday).days();
        assertEquals(2, before.get(2).working());
        assertEquals(3, before.get(1).working());

        // Made: a day given off by hand on the schedule shows up straight away.
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(2, weekRequests.capacity(area, monday).days().get(2).working());
        dayOff(s, ben, 4);
        var after = weekRequests.capacity(area, monday).days();
        assertEquals(2, after.get(4).working(), "Ben's day off on the schedule counts");
        assertEquals(1, after.get(4).off());
        assertEquals(3, after.get(1).working());
    }

    @Test
    void approvingWithoutAScheduleChangesNothingYet() {
        DayOffResponse given = dayOffService.create(new CreateDayOffRequest(anna, List.of(monday.plusDays(2)), null, null)).getFirst();
        assertEquals(DayOffStatus.APPROVED, given.status(), "given automatically");
        assertNull(given.scheduleUpdate());
        // Generating later honours it.
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.REQUESTED_OFF, day(s, anna, monday.plusDays(2)).type());
    }

    @Test
    void impactShowsWhetherStoresStayCovered() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate date = workDayWithFullStaff(s, anna).date();
        // An emergency waits for a decision (normal requests are given or refused straight away).
        Long annaRequest = dayOffService.create(new CreateDayOffRequest(anna, List.of(date), "Urgent", true)).getFirst().id();
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
        // The day off the manager gave on the schedule is already on record as hers.
        Long request = dayOffRequestRepository.findByEmployeeIdAndOffDate(anna, offDay).orElseThrow().getId();
        DayOffImpact impact = scheduleService.dayOffImpact(request);
        // It is already her day off, so approving changes nothing, but the day is still short.
        assertTrue(impact.newShortages().isEmpty());
        assertTrue(impact.existingShortages() > 0);

        LocalDate workDay = workDayWithFullStaff(s, anna).date();
        // An emergency one still goes to the manager.
        Long second = dayOffService.create(new CreateDayOffRequest(anna, List.of(workDay), "Urgent", true)).getFirst().id();
        DayOffImpact worse = scheduleService.dayOffImpact(second);
        assertEquals(ImpactLevel.SHORT, worse.level(), "3 of 4 becoming 2 of 4 is worse, even though it was short already");
    }

    @Test
    void onlyOnePersonPerStoreCanBeOffOnADay() {
        List<Long> extras = new java.util.ArrayList<>();
        for (int i = 4; i <= 7; i++) {
            extras.add(employee("A-" + i, "Extra" + i)); // enough people that the area limit isn't what stops anyone here
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

        // A rejected request frees the day again (6-day workers get one normal day a week, so two colleagues).
        Long fri = dayOffService.create(new CreateDayOffRequest(extras.get(0), List.of(wed.plusDays(2)), null, null))
                .getFirst().id();
        dayOffService.changeDecision(fri, DayOffStatus.REJECTED, "Busy");
        dayOffService.create(new CreateDayOffRequest(extras.get(1), List.of(wed.plusDays(2)), null, null));
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
        assertTrue(dayOffService.takenDays(cara, monday, monday.plusDays(6)).stream()
                .noneMatch(t -> t.date().equals(wed.plusDays(1))), "Thursday is still open for her");
        dayOffService.changeDecision(annaWed, DayOffStatus.REJECTED, "Busy");
        dayOffService.create(new CreateDayOffRequest(cara, List.of(wed), null, null));
        BusinessRuleException again = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, null)));
        assertTrue(again.getMessage().contains("is full"), again.getMessage());
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), "Urgent", true)).size());
    }

    @Test
    void withAScheduleTheLimitCountsWhoIsAlreadyOff() {
        Long other = storeService.create(new StoreRequest(area, "Other", "OTH", null, 0, true, null, null)).id();
        employeeService.changeHomeStore(cara, other);
        // Anna is given Wednesday off on the schedule, so only Ben and Cara work then: nobody else can go.
        ScheduleResponse s = dayOff(dayOff(scheduleService.generate(area, monday), anna, 2), cara, 4);
        LocalDate busy = monday.plusDays(2);
        // The week is made: Cara can't be given a day by the system any more; she asks her manager (or an emergency).
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(cara, List.of(busy), null, null)));
        assertTrue(e.getMessage().contains("already made"), e.getMessage());
        assertEquals(DayOffStatus.PENDING,
                dayOffService.create(new CreateDayOffRequest(cara, List.of(busy), "Urgent", true)).getFirst().status());
        // The manager decides: given straight away.
        TestUsers.signInAsAdmin();
        LocalDate caraOff = row(s, cara).days().stream().filter(c -> c.type() == DayType.OFF).findFirst().orElseThrow().date();
        assertEquals(DayOffStatus.APPROVED,
                dayOffRequestRepository.findByEmployeeIdAndOffDate(cara, caraOff).orElseThrow().getStatus(),
                "the day off the manager gave on the schedule is hers on her side too");
    }

    @Test
    void emergencyRequestsSkipTheStoreRuleAndWeeklyLimitButNeedApproval() {
        LocalDate tue = monday.plusDays(1);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(tue), null, null)); // works 6 days: her 1 for the week
        DayOffResponse urgent = dayOffService.create(
                new CreateDayOffRequest(anna, List.of(tue.plusDays(2)), "Hospital", true)).getFirst();
        assertTrue(urgent.emergency());
        assertEquals(DayOffStatus.PENDING, urgent.status(), "still needs a manager");
        // Doesn't use up the limit either: Ben's emergency on Anna's day is allowed too.
        assertTrue(dayOffService.create(new CreateDayOffRequest(ben, List.of(tue), "Urgent", true)).getFirst().emergency());
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
        // Only emergencies wait for a decision now.
        Long annaTue = dayOffService.create(new CreateDayOffRequest(anna, List.of(tue), "Urgent", true)).getFirst().id();
        Long caraTue = dayOffService.create(new CreateDayOffRequest(cara, List.of(tue), "Urgent", true)).getFirst().id();
        // Ben and Extra4 both ask for Wednesday (emergency, past the one-per-store rule): only one of them fits.
        Long benWed = dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), "Urgent", true)).getFirst().id();
        Long ex = employeeIdByName(s, "Extra4");
        Long exWed = dayOffService.create(new CreateDayOffRequest(ex, List.of(wed), "Urgent", true)).getFirst().id();
        Long extra5 = employeeIdByName(s, "Extra5");
        Long ex5Wed = dayOffService.create(new CreateDayOffRequest(extra5, List.of(wed), "Urgent", true)).getFirst().id();

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
    void aMadeWeekIsTheManagersAndTheSystemDoesntChangeIt() {
        LocalDate thu = monday.plusDays(3);
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(thu), "Urgent", true)).getFirst().id();
        ScheduleResponse s = scheduleService.generate(area, monday);
        assertEquals(DayType.WORK, day(s, anna, thu).type(), "pending emergency: still working");

        // Approved without a manager's decision (e.g. old data): opening the week doesn't change it by itself.
        DayOffRequest r = dayOffRequestRepository.findById(request).orElseThrow();
        r.setStatus(DayOffStatus.APPROVED);
        ScheduleResponse opened = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.WORK, day(opened, anna, thu).type());

        // The manager decides: Off on her approved day keeps it as that day off; Off for Ben is a plain day off.
        Long entry = day(opened, anna, thu).entryId();
        assertEquals(DayType.REQUESTED_OFF,
                day(scheduleService.updateEntry(opened.id(), entry, DayType.OFF, null), anna, thu).type());
        Long benThu = day(opened, ben, thu).entryId();
        assertEquals(DayType.OFF,
                day(scheduleService.updateEntry(opened.id(), benThu, DayType.REQUESTED_OFF, null), ben, thu).type());
    }

    @Test
    void managerCanRevokeAnApprovedDayOffWithAReason() {
        ScheduleResponse s = scheduleService.generate(area, monday);
        LocalDate date = workDayWithFullStaff(s, anna).date();
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(date), null, null)).getFirst().id();
        // The schedule cell knows which request gave the day off, so it can be revoked right there.
        assertEquals(request, day(scheduleService.find(area, monday).orElseThrow(), anna, date).approvedRequestId());

        TestUsers.signInAs(ben, Role.MANAGER, area);
        DayOffResponse changed = dayOffService.changeDecision(request, DayOffStatus.REJECTED, "Store event that day");

        assertEquals(DayOffStatus.REJECTED, changed.status());
        assertEquals(DayOffStatus.APPROVED, changed.previousStatus());
        assertEquals("Store event that day", changed.decisionChangeReason());
        assertNotNull(changed.decisionChangedBy());
        assertNotNull(changed.decisionChangedAt());
        assertTrue(changed.scheduleUpdate().contains("back to work"), changed.scheduleUpdate());
        TestUsers.signInAsAdmin();
        ScheduleResponse after = scheduleService.find(area, monday).orElseThrow();
        assertEquals(DayType.WORK, day(after, anna, date).type());
        assertEquals(store, day(after, anna, date).storeId());

        // And an admin can change it back.
        DayOffResponse back = dayOffService.changeDecision(request, DayOffStatus.APPROVED, "Event moved");
        assertEquals(DayOffStatus.APPROVED, back.status());
        assertEquals(DayOffStatus.REJECTED, back.previousStatus());
        assertEquals(DayType.REQUESTED_OFF, day(scheduleService.find(area, monday).orElseThrow(), anna, date).type());
    }

    @Test
    void changingADecisionNeedsAReasonAndADecidedRequest() {
        Long request = dayOffService.create(new CreateDayOffRequest(anna, List.of(monday.plusDays(1)), "Urgent", true))
                .getFirst().id();
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.changeDecision(request, DayOffStatus.REJECTED, "Too early"), "still pending");
        dayOffService.approve(request, null);
        assertThrows(BusinessRuleException.class, () -> dayOffService.changeDecision(request, DayOffStatus.REJECTED, " "));
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.changeDecision(request, DayOffStatus.APPROVED, "Same"), "already approved");

        // A manager of another area can't.
        TestUsers.signInAs(ben, Role.MANAGER, area + 999);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.changeDecision(request, DayOffStatus.REJECTED, "Not mine"));
        // Nor can an employee.
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.changeDecision(request, DayOffStatus.REJECTED, "Nope"));
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
