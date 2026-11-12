package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffDtos.AssignedDay;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * The weekly request cycle: ask for next week by Friday, one emergency a month, every request in before the week
 * is generated (with suggested days off for anyone missing), and one primary per store.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.one-emergency-per-month=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class WeeklyRequestRulesTest {

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
    WeekRequests weekRequests;

    LocalDate today = LocalDate.now(ZoneId.of("America/Los_Angeles"));
    LocalDate open = DayOffService.openWeek(today);
    Long area;
    Long store;
    Long anna;
    Long ben;
    Long cara;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Rules Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Rules Store", "RLS", null, 1, true, null, null)).id();
        anna = employee("R-1", "Anna", store);
        ben = employee("R-2", "Ben", store);
        cara = employee("R-3", "Cara", store);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long homeStore) {
        return employeeService.create(new EmployeeRequest(code, first, "Rules", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, homeStore, 6, true, null)).employee().id();
    }

    private void as(Long id) {
        TestUsers.signInAs(id, Role.EMPLOYEE, area);
    }

    @Test
    void theOpenWeekIsNextWeekUntilFridayThenTheWeekAfter() {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        assertEquals(LocalDate.of(2026, 11, 9), DayOffService.openWeek(monday));
        assertEquals(LocalDate.of(2026, 11, 9), DayOffService.openWeek(monday.plusDays(4))); // Friday
        assertEquals(LocalDate.of(2026, 11, 16), DayOffService.openWeek(monday.plusDays(5))); // Saturday
        assertEquals(LocalDate.of(2026, 11, 16), DayOffService.openWeek(monday.plusDays(6))); // Sunday
    }

    @Test
    void employeesCanAskForTheNextTwoWeeks() {
        LocalDate next = DayOffService.weekStart(today).plusWeeks(1);
        assertEquals(List.of(next, next.plusWeeks(1)), dayOffService.window().openWeeks());

        as(anna);
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(anna, List.of(next.plusDays(1)), null, null)).size());
        as(ben);
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(ben, List.of(next.plusWeeks(1).plusDays(2)), null, null)).size());
        // The third week isn't open yet.
        BusinessRuleException far = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(ben, List.of(next.plusWeeks(2).plusDays(2)), null, null)));
        assertTrue(far.getMessage().contains("open on Saturday") || far.getMessage().contains("ahead"), far.getMessage());

        // Admins and managers follow the same two weeks.
        TestUsers.signInAsAdmin();
        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(ben, List.of(next.plusWeeks(2).plusDays(2)), null, null)));
    }

    @Test
    void oneEmergencyRequestAMonth() {
        LocalDate nextMonday = DayOffService.weekStart(today).plusWeeks(1);
        LocalDate first = nextMonday.plusDays(1);
        // Another day in the same month (within the next two weeks).
        LocalDate again = first.plusDays(1).getMonth() == first.getMonth() ? first.plusDays(1) : nextMonday;
        TestUsers.signInAsAdmin();
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(cara, List.of(first), "Sick", true)).size());
        as(cara);
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(cara, List.of(again), "Again", true)));
        assertTrue(e.getMessage().contains("emergency request for"), e.getMessage());
    }

    @Test
    void theWeekWaitsForEveryonesRequestAndSuggestionsFillTheGaps() {
        LocalDate week = open;
        TestUsers.signInAsAdmin();
        BusinessRuleException blocked = assertThrows(BusinessRuleException.class,
                () -> scheduleService.generate(area, week));
        assertTrue(blocked.getMessage().contains("Still waiting for 3"), blocked.getMessage());

        Long annaRequest = dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null))
                .getFirst().id();
        dayOffService.changeDecision(annaRequest, DayOffStatus.REJECTED, "Too busy");
        WeekRequests.Status status = weekRequests.status(area, week);
        assertEquals(3, status.total());
        assertEquals(1, status.sent());
        assertEquals(2, status.missing().size());
        assertEquals(1, status.denied().size());

        List<WeekRequests.Suggestion> suggestions = weekRequests.suggest(area, week);
        assertEquals(3, suggestions.size(), "the two without a request and Anna, who was turned down");
        WeekRequests.Suggestion forAnna = suggestions.stream().filter(s -> s.employeeId().equals(anna)).findFirst()
                .orElseThrow();
        assertFalse(forAnna.date().equals(week.plusDays(2)), "not the day she was already turned down for");
        assertEquals(3, Set.copyOf(suggestions.stream().map(WeekRequests.Suggestion::date).toList()).size(),
                "spread over different days");

        dayOffService.assignDaysOff(area, suggestions.stream()
                .map(s -> new AssignedDay(s.employeeId(), s.date())).toList());
        assertEquals(0, weekRequests.status(area, week).missing().size());
        var schedule = scheduleService.generate(area, week);
        assertEquals(3, schedule.rows().size());
    }

    @Test
    void sixDayWorkersGetOneDayOffAndMoreNeedsAnEmergencyOnceTheWeekIsFull() {
        as(anna);
        // An open day isn't asked for as an emergency.
        BusinessRuleException early = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(open.plusDays(3)), "Sick", true)));
        assertTrue(early.getMessage().contains("still open"), early.getMessage());

        assertEquals(1, dayOffService.create(new CreateDayOffRequest(anna, List.of(open.plusDays(1)), null, null)).size());
        // She works 6 days, so one normal day off is all: a second one is refused.
        BusinessRuleException second = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(open.plusDays(3)), null, null)));
        assertTrue(second.getMessage().contains("only 1 day off"), second.getMessage());

        // Now the week is full for her: an emergency goes to a manager.
        var urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(open.plusDays(3)), "Sick", true)).getFirst();
        assertTrue(urgent.emergency());
        assertEquals(DayOffStatus.PENDING, urgent.status());
    }

    @Test
    void fiveDayWorkersGetTwoDaysOff() {
        TestUsers.signInAsAdmin();
        Long eve = employeeService.create(new EmployeeRequest("R-21", "Eve", "Rules", "r-21@test.example", null,
                Role.EMPLOYEE, area, store, 5, true, null)).employee().id();
        as(eve);
        assertEquals(2, dayOffService.create(new CreateDayOffRequest(eve,
                List.of(open.plusDays(1), open.plusDays(3)), null, null)).size());
    }

    @Test
    void thisWeekIsAlreadyMadeSoPeopleStartAtNextWeek() {
        LocalDate sunday = DayOffService.weekStart(today).plusDays(6);
        as(anna);
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(sunday), "Sick", true)));
        assertTrue(e.getMessage().contains("already made"), e.getMessage());

        // Not even an admin or manager: this week is changed on the schedule itself.
        TestUsers.signInAsAdmin();
        BusinessRuleException admin = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(sunday), "Sick", true)));
        assertTrue(admin.getMessage().contains("edit the schedule"), admin.getMessage());
    }

    @Test
    void aFullDayCanBeAskedForAsAnEmergencyEvenWithOpenDaysLeft() {
        LocalDate next = DayOffService.weekStart(today).plusWeeks(1);
        LocalDate wed = next.plusDays(2);
        // Ben takes Wednesday: the store can spare only one person that day, so it's taken for Anna.
        as(ben);
        dayOffService.create(new CreateDayOffRequest(ben, List.of(wed), null, null));
        as(anna);
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), null, null)), "taken for a normal request");
        // Her week still has open days, but Wednesday itself is taken: an emergency for it goes to a manager.
        var urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(wed), "Wedding", true)).getFirst();
        assertTrue(urgent.emergency());
        assertEquals(DayOffStatus.PENDING, urgent.status());
    }

    @Test
    void aTurnedDownRequestCanBeAskedForAgain() {
        LocalDate next = DayOffService.weekStart(today).plusWeeks(1);
        LocalDate tue = next.plusDays(1);
        LocalDate thu = next.plusDays(3);
        as(anna);
        dayOffService.create(new CreateDayOffRequest(anna, List.of(tue), null, null)); // her normal day: the week is full
        Long urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(thu), "Sick", true)).getFirst().id();
        TestUsers.signInAsAdmin();
        dayOffService.reject(urgent, "Busy");

        // She can ask again, for the same day: the rejected request is replaced, and it didn't use up her month.
        as(anna);
        var again = dayOffService.create(new CreateDayOffRequest(anna, List.of(thu), "Still sick", true)).getFirst();
        assertEquals(DayOffStatus.PENDING, again.status());
        assertTrue(again.emergency());

        // A rejected normal day can be asked for again too (by a manager entering it, here).
        TestUsers.signInAsAdmin();
        dayOffService.reject(again.id(), "Still busy");
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(anna, List.of(thu), null, null, true)).size());
    }

    @Test
    void peopleCanAskForThemselvesUpToTwoWeeksAhead() {
        as(anna);
        LocalDate tooFar = DayOffService.weekStart(today).plusWeeks(3);
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(tooFar), "Wedding", true)));
        assertTrue(e.getMessage().contains("2 weeks ahead"), e.getMessage());
    }

    @Test
    void aManagerCanOpenRequestsFurtherAheadForTheirArea() {
        LocalDate later = open.plusWeeks(3).plusDays(1);
        as(anna);
        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(later), null, null)), "normally only the open week");
        // Employees can't open it themselves.
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.setOpenUntil(area, later.plusDays(10)));

        TestUsers.signInAs(9001L, Role.MANAGER, area);
        assertEquals(later.plusDays(10), dayOffService.setOpenUntil(area, later.plusDays(10)).until());
        as(anna);
        assertEquals(later.plusDays(10), dayOffService.window().openUntil());
        assertEquals(1, dayOffService.create(new CreateDayOffRequest(anna, List.of(later), null, null)).size());
        BusinessRuleException tooFar = assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(anna, List.of(later.plusDays(14)), null, null)));
        assertTrue(tooFar.getMessage().contains("until"), tooFar.getMessage());

        // A manager of another area can't; back to the usual window when cleared.
        TestUsers.signInAs(9002L, Role.MANAGER, area + 1000);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.setOpenUntil(area, null));
        TestUsers.signInAs(9001L, Role.MANAGER, area);
        assertNull(dayOffService.setOpenUntil(area, null).until());
        as(ben);
        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(ben, List.of(later), null, null)));
    }

    @Test
    void someoneWhoTakesTwoDaysOffGetsTwoSuggestions() {
        TestUsers.signInAsAdmin();
        Long dee = employeeService.create(new EmployeeRequest("R-20", "Dee", "Rules", "r-20@test.example", null,
                Role.EMPLOYEE, area, store, 5, true, null)).employee().id();
        // Dee asks for one of her two days.
        dayOffService.create(new CreateDayOffRequest(dee, List.of(open.plusDays(1)), null, null));
        WeekRequests.Status status = weekRequests.status(area, open);
        WeekRequests.PersonStatus deeStatus = status.fewer().stream().filter(p -> p.employeeId().equals(dee))
                .findFirst().orElseThrow();
        assertEquals(2, deeStatus.daysOffNeeded());
        assertEquals(1, deeStatus.stillNeeded());

        List<WeekRequests.Suggestion> forDee = weekRequests.suggest(area, open).stream()
                .filter(s -> s.employeeId().equals(dee)).toList();
        assertEquals(1, forDee.size(), "one more day: she already asked for one");
        assertEquals(2, forDee.getFirst().dayNumber());
        assertFalse(forDee.getFirst().date().equals(open.plusDays(1)));
        assertTrue(forDee.getFirst().reason().contains("Asked for 1 of their 2"), forDee.getFirst().reason());

        // Ben has no request and takes two days off: two different days are suggested.
        employeeService.update(ben, new EmployeeRequest("R-2", "Ben", "Rules", "r-2@test.example", null,
                Role.EMPLOYEE, area, store, 5, true, null));
        List<WeekRequests.Suggestion> forBen = weekRequests.suggest(area, open).stream()
                .filter(s -> s.employeeId().equals(ben)).toList();
        assertEquals(2, forBen.size());
        assertFalse(forBen.get(0).date().equals(forBen.get(1).date()));
    }

    @Test
    void aStoreHasOnlyOnePrimary() {
        Long other = storeService.create(new StoreRequest(area, "Other Store", "OTH", null, 1, true, null, null)).id();
        Long first = employeeService.create(new EmployeeRequest("R-10", "Pia", "Rules", "r-10@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, true)).employee().id();
        BusinessRuleException e = assertThrows(BusinessRuleException.class, () -> employeeService.create(
                new EmployeeRequest("R-11", "Quin", "Rules", "r-11@test.example", null, Role.EMPLOYEE, area, store, 6,
                        true, true)));
        assertTrue(e.getMessage().contains("already the primary"), e.getMessage());
        assertThrows(BusinessRuleException.class, () -> employeeService.changeHomeStore(anna, store, true));

        // The primary of another store moved here (rotation) joins as a secondary.
        Long otherPrimary = employeeService.create(new EmployeeRequest("R-12", "Ria", "Rules", "r-12@test.example",
                null, Role.EMPLOYEE, area, other, 6, true, true)).employee().id();
        var moved = employeeService.changeHomeStore(otherPrimary, store);
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.SECONDARY, moved.storeRole());
        assertEquals(com.example.schedulingsoftware.employee.StoreRole.PRIMARY,
                employeeService.findById(first).storeRole());
    }
}
