package com.example.schedulingsoftware.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

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
import com.example.schedulingsoftware.dayoff.DayOffReservations;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.employee.StoreRole;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleKind;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * The weekly schedule's four tabs, each on its own: Schedule by System (built and kept current by the system until
 * someone edits it), Schedule (Generate, then the manager's), Empty draft (blank, by hand) and Day-off draft (days off
 * filled in; primaries and secondaries on request; floaters by hand).
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true", "alliancetel.scheduling.reserve-days-off=true" })
@Transactional
class FourTabsTest {

    @Autowired AreaService areaService;
    @Autowired StoreService storeService;
    @Autowired EmployeeService employeeService;
    @Autowired DayOffService dayOffService;
    @Autowired DayOffReservations reservations;
    @Autowired ScheduleService scheduleService;
    @Autowired RequestPortal portal;

    LocalDate week;
    Long area;
    Long manager;
    Long alpha;
    Long bravo;
    Long ann; // primary Alpha
    Long abe; // secondary Alpha
    Long bea; // primary Bravo
    Long bob; // secondary Bravo
    Long flo; // floater, Alpha and Bravo

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Tabs Area", null, true)).id();
        alpha = storeService.create(new StoreRequest(area, "Alpha", "ALP", null, 1, true, null, null)).id();
        bravo = storeService.create(new StoreRequest(area, "Bravo", "BRA", null, 1, true, null, null)).id();
        manager = person("T-M", "Maria", Role.MANAGER, null, null, null, false);
        ann = person("T-1", "Ann", Role.EMPLOYEE, alpha, true, null, null);
        abe = person("T-2", "Abe", Role.EMPLOYEE, alpha, false, null, null);
        bea = person("T-3", "Bea", Role.EMPLOYEE, bravo, true, null, null);
        bob = person("T-4", "Bob", Role.EMPLOYEE, bravo, false, null, null);
        flo = person("T-5", "Flo", Role.EMPLOYEE, null, null, List.of(alpha, bravo), null);
        employeeService.setRegularDaysOff(ann, List.of(DayOfWeek.MONDAY), reservations::checkRegularDays);
        reservations.reserveWeek(area, week);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long person(String code, String first, Role role, Long store, Boolean primary, List<Long> floats,
            Boolean onSchedule) {
        return employeeService.create(new EmployeeRequest(code, first, "Tabs", code.toLowerCase() + "@test.example",
                null, role, area, store, 6, true, primary, null, null, null, floats, onSchedule, null)).employee().id();
    }

    private void asManager() {
        TestUsers.signInAs(manager, Role.MANAGER, area);
    }

    private LocalDate day(DayOfWeek d) {
        return week.plusDays(d.getValue() - 1L);
    }

    private static EmployeeRow row(ScheduleResponse s, Long id) {
        return s.rows().stream().filter(r -> r.employeeId().equals(id)).findFirst().orElseThrow();
    }

    private static DayCell cell(ScheduleResponse s, Long id, LocalDate date) {
        return row(s, id).days().stream().filter(c -> c.date().equals(date)).findFirst().orElseThrow();
    }

    private ScheduleResponse system() {
        return scheduleService.systemSchedule(area, week).orElseThrow();
    }

    // ---------- Tab 1: Schedule by System ----------

    @Test
    void systemTabBuildsTheWeekWithTheRules() {
        asManager();
        ScheduleResponse s = system();
        assertEquals(ScheduleKind.SYSTEM, s.kind());
        assertEquals(0, s.rows().stream().flatMap(r -> r.days().stream()).filter(c -> c.type() == DayType.UNSET).count(),
                "every day decided");
        assertTrue(cell(s, ann, day(DayOfWeek.MONDAY)).type() != DayType.WORK, "Ann's regular Monday is her day off");
        for (EmployeeRow r : s.rows()) {
            assertEquals(r.expectedWorkDays(), r.scheduledWorkDays(), r.employeeName() + " works their usual days");
        }
        assertTrue(s.coverageGaps().isEmpty(), "every store meets its minimum: " + s.coverageGaps());
        for (LocalDate d = week; d.isBefore(week.plusDays(7)); d = d.plusDays(1)) {
            LocalDate date = d;
            boolean annOff = cell(s, ann, date).type() != DayType.WORK;
            boolean abeOff = cell(s, abe, date).type() != DayType.WORK;
            assertTrue(!(annOff && abeOff), "Alpha's primary and secondary aren't both off on " + date);
            DayCell f = cell(s, flo, date);
            assertTrue(f.type() != DayType.WORK || f.storeId() == null || List.of(alpha, bravo).contains(f.storeId()),
                    "the floater only works at their stores");
        }
        // The week isn't "made": requests keep working as usual.
        assertTrue(scheduleService.find(area, week).isEmpty(), "the Schedule tab is untouched");
    }

    @Test
    void systemTabKeepsAManagersChangeAndShowsTheOriginal() {
        asManager();
        ScheduleResponse s = system();
        DayCell work = row(s, abe).days().stream().filter(c -> c.type() == DayType.WORK && alpha.equals(c.storeId()))
                .findFirst().orElseThrow();
        ScheduleResponse edited = scheduleService.updateEntry(s.id(), work.entryId(), DayType.WORK, bravo);
        assertEquals(bravo, cell(edited, abe, work.date()).storeId());

        // Something changes that would rebuild an untouched week: a new day off.
        TestUsers.signInAs(bob, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(bob, List.of(day(DayOfWeek.SATURDAY)), null, false));
        asManager();
        ScheduleResponse again = system();
        assertEquals(s.id(), again.id(), "same schedule, not rebuilt");
        DayCell kept = cell(again, abe, work.date());
        assertEquals(bravo, kept.storeId(), "the manager's change stays after a refresh");
        assertNotNull(kept.systemLabel(), "the system's original is kept");
        assertTrue(kept.systemLabel().startsWith("Alpha"), kept.systemLabel());
    }

    @Test
    void anUntouchedSystemTabStaysCurrent() {
        asManager();
        ScheduleResponse before = system();
        LocalDate sat = day(DayOfWeek.SATURDAY);
        TestUsers.signInAs(bob, Role.EMPLOYEE, area);
        DayOffResponse r = dayOffService.create(new CreateDayOffRequest(bob, List.of(sat), null, false)).getFirst();
        assertEquals(DayOffStatus.APPROVED, r.status());
        asManager();
        ScheduleResponse after = system();
        assertEquals(DayType.REQUESTED_OFF, cell(after, bob, sat).type(), "Bob's new day off is in");
        assertNull(cell(after, bob, sat).systemLabel());
        assertNotEquals(null, before);
    }

    @Test
    void publishingTheSystemTabMakesItTheSchedule() {
        asManager();
        ScheduleResponse s = system();
        ScheduleResponse published = scheduleService.publish(s.id());
        assertEquals(ScheduleKind.MAIN, published.kind());
        assertEquals(cell(s, ann, day(DayOfWeek.MONDAY)).type() != DayType.WORK,
                cell(published, ann, day(DayOfWeek.MONDAY)).type() != DayType.WORK);
    }

    // ---------- Tab 2: Schedule ----------

    @Test
    void scheduleTabIsTheManagersAfterGenerate() {
        asManager();
        ScheduleResponse s = scheduleService.generate(area, week);
        DayCell work = row(s, abe).days().stream().filter(c -> c.type() == DayType.WORK).findFirst().orElseThrow();
        scheduleService.updateEntry(s.id(), work.entryId(), DayType.WORK, bravo);
        // Background work the system does on its own:
        TestUsers.signInAsAdmin();
        reservations.reserveUpcoming();
        scheduleService.recordDaysGivenOnSchedule();
        asManager();
        ScheduleResponse after = scheduleService.find(area, week).orElseThrow();
        assertEquals(bravo, cell(after, abe, work.date()).storeId(), "the manager's change survives");
        assertTrue(cell(after, abe, work.date()).manuallyEdited());
    }

    // ---------- Tab 3: Empty draft ----------

    @Test
    void emptyDraftIsBlankAndPlacedByHand() {
        asManager();
        ScheduleResponse d = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        assertTrue(d.rows().stream().flatMap(r -> r.days().stream()).allMatch(c -> c.type() == DayType.UNSET),
                "starts blank");
        // Drop Flo on Alpha (whole week), then move her to Bravo, then take one day back out.
        ScheduleResponse placed = scheduleService.moveForWeek(d.id(), flo, alpha);
        assertEquals(6, row(placed, flo).days().stream().filter(c -> c.type() == DayType.WORK && alpha.equals(c.storeId())).count(),
                "her 6 work days; one stays blank for her day off");
        ScheduleResponse moved = scheduleService.moveForWeek(d.id(), flo, bravo);
        assertTrue(row(moved, flo).days().stream().filter(c -> c.type() == DayType.WORK).allMatch(c -> bravo.equals(c.storeId())));
        LocalDate tue = day(DayOfWeek.TUESDAY);
        ScheduleResponse removed = scheduleService.updateEntry(d.id(), cell(moved, flo, tue).entryId(), DayType.UNSET, null);
        assertEquals(DayType.UNSET, cell(removed, flo, tue).type());
        // Nothing else was placed by the system, and it's still there when reopened.
        ScheduleResponse reopened = scheduleService.findDraft(area, week, ScheduleKind.EMPTY).orElseThrow();
        assertTrue(row(reopened, ann).days().stream().allMatch(c -> c.type() == DayType.UNSET));
        assertEquals(bravo, cell(reopened, flo, day(DayOfWeek.WEDNESDAY)).storeId());
        assertEquals(DayType.UNSET, cell(reopened, flo, day(DayOfWeek.SUNDAY)).type(), "her 7th day stays blank");
        // The other tabs are untouched.
        assertTrue(scheduleService.find(area, week).isEmpty());
    }

    // ---------- Tab 4: Day-off draft ----------

    @Test
    void dayOffDraftShowsApprovedDaysAndLetsTheManagerFillByRole() {
        // Abe asks for Thursday (given), Bob's emergency waits, Bea's request is turned down.
        TestUsers.signInAs(abe, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(abe, List.of(day(DayOfWeek.THURSDAY)), null, false));
        TestUsers.signInAs(bob, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(bob, List.of(day(DayOfWeek.FRIDAY)), null, false));
        DayOffResponse emergency = dayOffService.create(
                new CreateDayOffRequest(bob, List.of(day(DayOfWeek.SUNDAY)), "Funeral", true)).getFirst();
        TestUsers.signInAs(bea, Role.EMPLOYEE, area);
        DayOffResponse beaWed = dayOffService.create(
                new CreateDayOffRequest(bea, List.of(day(DayOfWeek.WEDNESDAY)), null, false)).getFirst();
        asManager();
        dayOffService.changeDecision(beaWed.id(), DayOffStatus.REJECTED, "Inventory day");

        ScheduleResponse d = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        assertEquals(DayType.REQUESTED_OFF, cell(d, abe, day(DayOfWeek.THURSDAY)).type(), "approved request shows");
        assertEquals(DayType.REQUESTED_OFF, cell(d, bob, day(DayOfWeek.FRIDAY)).type());
        assertTrue(cell(d, ann, day(DayOfWeek.MONDAY)).type() != DayType.UNSET
                && cell(d, ann, day(DayOfWeek.MONDAY)).type() != DayType.WORK, "regular day off shows");
        assertNotEquals(DayType.REQUESTED_OFF, cell(d, bob, day(DayOfWeek.SUNDAY)).type(), "pending emergency isn't approved");
        assertNotEquals(DayType.REQUESTED_OFF, cell(d, bea, day(DayOfWeek.WEDNESDAY)).type(), "rejected isn't approved");
        assertEquals(DayOffStatus.PENDING, emergency.status());

        ScheduleResponse prim = scheduleService.fillRole(d.id(), StoreRole.PRIMARY);
        for (DayCell c : row(prim, ann).days()) {
            if (c.type() == DayType.WORK) {
                assertEquals(alpha, c.storeId(), "primary at home");
            }
        }
        assertTrue(row(prim, abe).days().stream().noneMatch(c -> c.type() == DayType.WORK), "secondaries not yet");
        ScheduleResponse sec = scheduleService.fillRole(d.id(), StoreRole.SECONDARY);
        assertTrue(row(sec, abe).days().stream().anyMatch(c -> c.type() == DayType.WORK && alpha.equals(c.storeId())));
        assertTrue(row(sec, flo).days().stream().noneMatch(c -> c.type() == DayType.WORK), "floaters stay manual");
        assertThrows(BusinessRuleException.class, () -> scheduleService.fillRole(d.id(), StoreRole.FLOATER));
        // "Fill the rest" doesn't place floaters here either.
        ScheduleResponse rest = scheduleService.fillBlanks(d.id());
        assertTrue(row(rest, flo).days().stream().noneMatch(c -> c.type() == DayType.WORK), "floaters stay manual");
        // The manager places the floater by hand.
        ScheduleResponse floated = scheduleService.moveForWeek(d.id(), flo, bravo);
        assertTrue(row(floated, flo).days().stream().anyMatch(c -> c.type() == DayType.WORK && bravo.equals(c.storeId())));
    }

    // ---------- Access ----------

    @Test
    void onlyThisAreasManagersAndAdminsUseTheTabs() {
        TestUsers.signInAsAdmin();
        Long other = areaService.create(new AreaRequest("Other Tabs Area", null, true)).id();
        TestUsers.signInAs(manager, Role.MANAGER, other);
        assertThrows(AccessDeniedException.class, () -> scheduleService.systemSchedule(area, week));
        assertThrows(AccessDeniedException.class, () -> scheduleService.createDraft(area, week, ScheduleKind.EMPTY));
        TestUsers.signInAs(ann, Role.EMPLOYEE, area);
        assertThrows(AccessDeniedException.class, () -> scheduleService.systemSchedule(area, week));
        TestUsers.signInAsMarketManager(manager, other, java.util.Set.of(area));
        assertNotNull(system());
    }

    // ---------- Every button acts on its own tab only ----------

    private java.util.Map<ScheduleKind, com.example.schedulingsoftware.schedule.ScheduleDtos.DraftSummary> tabs() {
        return scheduleService.drafts(area, week).stream()
                .collect(java.util.stream.Collectors.toMap(d -> d.kind(), d -> d));
    }

    @Test
    void eachTabsButtonsLeaveTheOtherTabsAlone() {
        asManager();
        ScheduleResponse sys = system();
        ScheduleResponse main = scheduleService.generate(area, week, true);
        ScheduleResponse empty = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        ScheduleResponse off = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        assertEquals(4, tabs().size());

        // Save draft: only that tab is saved.
        scheduleService.saveDraft(empty.id());
        assertTrue(tabs().get(ScheduleKind.EMPTY).saved());
        assertTrue(!tabs().get(ScheduleKind.MAIN).saved() && !tabs().get(ScheduleKind.SYSTEM).saved()
                && !tabs().get(ScheduleKind.DAYS_OFF).saved());

        // Delete the day-off draft: the others stay.
        scheduleService.reset(off.id());
        assertEquals(java.util.Set.of(ScheduleKind.SYSTEM, ScheduleKind.MAIN, ScheduleKind.EMPTY), tabs().keySet());

        // Delete "By system": it stays deleted (not rebuilt on the next visit) until "Build now".
        scheduleService.reset(sys.id());
        assertTrue(scheduleService.systemSchedule(area, week).isEmpty());
        assertEquals(java.util.Set.of(ScheduleKind.MAIN, ScheduleKind.EMPTY), tabs().keySet());
        assertTrue(scheduleService.find(area, week).isPresent());
        sys = scheduleService.rebuildSystem(area, week);
        assertEquals(ScheduleKind.SYSTEM, system().kind());
        List<String> sysBefore = system().rows().stream().flatMap(r -> r.days().stream())
                .map(c -> c.type() + ":" + c.storeId()).toList();

        // Publish the empty draft: it becomes what staff see, and stays on its own tab; "By system" isn't touched.
        scheduleService.fillBlanks(empty.id());
        ScheduleResponse published = scheduleService.publish(empty.id());
        assertEquals(com.example.schedulingsoftware.schedule.ScheduleStatus.PUBLISHED, published.status());
        assertEquals(empty.id(), scheduleService.findDraft(area, week, ScheduleKind.EMPTY).orElseThrow().id());
        assertTrue(tabs().get(ScheduleKind.EMPTY).saved());
        assertEquals(sysBefore, system().rows().stream().flatMap(r -> r.days().stream())
                .map(c -> c.type() + ":" + c.storeId()).toList());

        // Save "By system": it's pinned (stays as left, even when its sources change).
        scheduleService.saveDraft(sys.id());
        employeeService.setRegularDaysOff(abe, List.of(DayOfWeek.TUESDAY), reservations::checkRegularDays);
        assertEquals(sys.id(), system().id());
        assertTrue(tabs().get(ScheduleKind.SYSTEM).saved(), "still the saved one, not rebuilt");

        // Delete the week's schedule: the drafts stay.
        scheduleService.reset(published.id());
        assertTrue(scheduleService.find(area, week).isEmpty());
        assertEquals(java.util.Set.of(ScheduleKind.SYSTEM, ScheduleKind.EMPTY), tabs().keySet());
    }

    // ---------- Manual changes stay on their own tab ----------

    /** Every day of a tab: who, when, what and where (not who changed it). */
    private static List<String> days(ScheduleResponse s) {
        return s.rows().stream().flatMap(r -> r.days().stream().map(c -> r.employeeId() + "|" + c.date() + "|"
                + c.type() + "|" + c.storeId())).sorted().toList();
    }

    private ScheduleResponse tab(ScheduleKind kind) {
        return kind == ScheduleKind.SYSTEM ? system()
                : kind == ScheduleKind.MAIN ? scheduleService.find(area, week).orElseThrow()
                : scheduleService.findDraft(area, week, kind).orElseThrow();
    }

    private java.util.Map<ScheduleKind, List<String>> snapshot() {
        java.util.Map<ScheduleKind, List<String>> m = new java.util.EnumMap<>(ScheduleKind.class);
        for (ScheduleKind k : ScheduleKind.values()) {
            m.put(k, days(tab(k)));
        }
        return m;
    }

    private void assertOnlyChanged(java.util.Map<ScheduleKind, List<String>> before, ScheduleKind changed) {
        java.util.Map<ScheduleKind, List<String>> after = snapshot();
        for (ScheduleKind k : ScheduleKind.values()) {
            if (k == changed) {
                assertNotEquals(before.get(k), after.get(k), k + " has the change");
            } else {
                assertEquals(before.get(k), after.get(k), k + " is untouched by a change on " + changed);
            }
        }
    }

    @Test
    void manualChangesStayOnTheirOwnTab() {
        asManager();
        system();
        ScheduleResponse main = scheduleService.generate(area, week, true);
        scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        ScheduleResponse off = scheduleService.createDraft(area, week, ScheduleKind.DAYS_OFF);
        scheduleService.fillRole(off.id(), StoreRole.PRIMARY);
        LocalDate mon = day(DayOfWeek.MONDAY);
        LocalDate wed = day(DayOfWeek.WEDNESDAY);

        // Schedule tab: a day given off, and Ann's approved Monday put back to work (both write day-off records).
        var before = snapshot();
        DayCell bobWed = cell(main, bob, wed);
        assertEquals(DayType.WORK, bobWed.type());
        scheduleService.updateEntry(main.id(), bobWed.entryId(), DayType.OFF, null);
        DayCell annMon = cell(tab(ScheduleKind.MAIN), ann, mon);
        assertNotEquals(DayType.WORK, annMon.type());
        scheduleService.updateEntry(main.id(), annMon.entryId(), DayType.WORK, alpha);
        assertOnlyChanged(before, ScheduleKind.MAIN);

        // By system: a store change.
        before = snapshot();
        ScheduleResponse sys = system();
        DayCell sysDay = row(sys, bea).days().stream().filter(c -> c.type() == DayType.WORK).findFirst().orElseThrow();
        scheduleService.updateEntry(sys.id(), sysDay.entryId(), DayType.WORK, alpha.equals(sysDay.storeId()) ? bravo : alpha);
        assertOnlyChanged(before, ScheduleKind.SYSTEM);

        // Empty draft: someone placed on a day.
        before = snapshot();
        ScheduleResponse empty = tab(ScheduleKind.EMPTY);
        scheduleService.updateEntry(empty.id(), cell(empty, abe, wed).entryId(), DayType.WORK, alpha);
        assertOnlyChanged(before, ScheduleKind.EMPTY);

        // Day-off draft: a day off given by hand.
        before = snapshot();
        ScheduleResponse daysOff = tab(ScheduleKind.DAYS_OFF);
        DayCell blank = row(daysOff, abe).days().stream().filter(c -> c.type() == DayType.UNSET).findFirst().orElseThrow();
        scheduleService.updateEntry(daysOff.id(), blank.entryId(), DayType.OFF, null);
        assertOnlyChanged(before, ScheduleKind.DAYS_OFF);

        // Anything goes, on any tab: an approved day off put to work on the day-off draft…
        before = snapshot();
        daysOff = tab(ScheduleKind.DAYS_OFF);
        DayCell approvedOff = cell(daysOff, ann, mon);
        assertEquals(DayType.REQUESTED_OFF, approvedOff.type());
        scheduleService.updateEntry(daysOff.id(), approvedOff.entryId(), DayType.WORK, alpha);
        assertOnlyChanged(before, ScheduleKind.DAYS_OFF);

        // …a day off swapped to someone else on "By system" and on the week's schedule.
        for (ScheduleKind k : List.of(ScheduleKind.SYSTEM, ScheduleKind.MAIN)) {
            before = snapshot();
            ScheduleResponse s = tab(k);
            DayCell[] pair = offAndWorking(s);
            scheduleService.swap(s.id(), pair[0].entryId(), pair[1].entryId());
            assertOnlyChanged(before, k);
        }
    }

    /** Someone's day off and, the same day, someone else at work. */
    private static DayCell[] offAndWorking(ScheduleResponse s) {
        for (EmployeeRow r : s.rows()) {
            for (DayCell c : r.days()) {
                if (c.type() == DayType.WORK) {
                    continue;
                }
                for (EmployeeRow o : s.rows()) {
                    if (o.employeeId().equals(r.employeeId())) {
                        continue;
                    }
                    DayCell w = o.days().stream().filter(x -> x.date().equals(c.date())).findFirst().orElseThrow();
                    if (w.type() == DayType.WORK) {
                        return new DayCell[] { c, w };
                    }
                }
            }
        }
        throw new AssertionError("no day off next to a work day");

    }

    @Test
    void resetBringsBackTheSystemsVersion() {
        asManager();
        ScheduleResponse main = scheduleService.generate(area, week, true);
        List<String> systemsVersion = days(main);
        LocalDate mon = day(DayOfWeek.MONDAY);
        LocalDate wed = day(DayOfWeek.WEDNESDAY);

        // The manager gives Bob Wednesday off and puts Ann to work on her approved Monday.
        scheduleService.updateEntry(main.id(), cell(main, bob, wed).entryId(), DayType.OFF, null);
        scheduleService.updateEntry(main.id(), cell(tab(ScheduleKind.MAIN), ann, mon).entryId(), DayType.WORK, alpha);
        assertNotEquals(systemsVersion, days(tab(ScheduleKind.MAIN)));

        // Reset (delete, then build again): every manual day off is gone, Ann's Monday is hers again.
        scheduleService.reset(main.id());
        ScheduleResponse again = scheduleService.generate(area, week, true);
        assertEquals(systemsVersion, days(again));
        assertTrue(dayOffService.search(area, null, DayOffStatus.APPROVED, week, week.plusDays(6)).stream()
                .noneMatch(r -> ScheduleService.GIVEN_ON_SCHEDULE.equals(r.reason())), "no day off given by hand left");
        assertNotEquals(DayType.WORK, cell(again, ann, mon).type());
    }

    // ---------- Holiday closures ----------

    private void closeOn(LocalDate date, String label, List<Long> stores) {
        storeService.close(new com.example.schedulingsoftware.store.StoreDtos.ClosureRequest(area, date, label, stores));
        scheduleService.storesClosed(area, date);
    }

    @Test
    void holidayClosesTheWholeAreaOrOneStore() {
        asManager();
        LocalDate thu = day(DayOfWeek.THURSDAY);
        LocalDate fri = day(DayOfWeek.FRIDAY);
        ScheduleResponse main = scheduleService.generate(area, week, true);
        ScheduleResponse empty = scheduleService.createDraft(area, week, ScheduleKind.EMPTY);
        scheduleService.fillBlanks(empty.id());
        assertTrue(cell(main, abe, thu).type() == DayType.WORK || cell(main, bob, thu).type() == DayType.WORK);

        // Thanksgiving: every store in the area closed. Nobody works that day on any unpublished tab.
        closeOn(thu, "Thanksgiving", null);
        var list = storeService.closures(area);
        assertEquals(1, list.size());
        assertTrue(list.get(0).wholeArea());
        assertEquals("Thanksgiving", list.get(0).label());
        for (ScheduleResponse s : List.of(tab(ScheduleKind.MAIN), tab(ScheduleKind.EMPTY))) {
            assertTrue(s.rows().stream().allMatch(r -> cell(s, r.employeeId(), thu).type() != DayType.WORK),
                    s.kind() + ": nobody works on the holiday");
        }
        // Built again, the week still has nobody working that day.
        scheduleService.reset(main.id());
        ScheduleResponse again = scheduleService.generate(area, week, true);
        assertTrue(again.rows().stream().allMatch(r -> cell(again, r.employeeId(), thu).type() != DayType.WORK));

        // One store closed on Friday: its people move to the store still open.
        closeOn(fri, null, List.of(alpha));
        ScheduleResponse after = tab(ScheduleKind.MAIN);
        assertTrue(after.rows().stream().map(r -> cell(after, r.employeeId(), fri))
                .noneMatch(c -> c.type() == DayType.WORK && alpha.equals(c.storeId())), "nobody at Alpha on Friday");
        assertFalse(storeService.closures(area).get(1).wholeArea());

        // Open again.
        storeService.reopen(area, fri, null);
        storeService.reopen(area, thu, null);
        assertTrue(storeService.closures(area).isEmpty());
    }
}
