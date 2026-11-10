package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Days off reserved for everyone when a week opens, swapped automatically when someone asks for another day, and the
 * admin's open / close switch for requests.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.reserve-days-off=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class ReservedDaysOffTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    DayOffReservations reservations;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    RequestPortal portal;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    WeekRequests weekRequests;

    LocalDate week;
    Long area;
    Long anna;
    Long ben;
    Long cara;
    Long dee;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Reserve Area", null, true)).id();
        Long north = storeService.create(new StoreRequest(area, "North", "RN", null, 1, true, null, null)).id();
        Long south = storeService.create(new StoreRequest(area, "South", "RS", null, 1, true, null, null)).id();
        anna = employee("RV-1", "Anna", north, 6);
        ben = employee("RV-2", "Ben", north, 6);
        cara = employee("RV-3", "Cara", south, 6);
        dee = employee("RV-4", "Dee", south, 5);
        employee("RV-5", "Eli", null, 6);
        employee("RV-6", "Fay", null, 6);
        reservations.reserveWeek(area, week);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, int days) {
        return employeeService.create(new EmployeeRequest(code, first, "Reserve", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, days, true, null)).employee().id();
    }

    private List<DayOffRequest> daysOff(Long employeeId) {
        return requests.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(employeeId))
                .filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                .filter(r -> !r.getOffDate().isBefore(week) && r.getOffDate().isBefore(week.plusDays(7)))
                .toList();
    }

    @Test
    void everyoneGetsTheirUsualDaysReserved() {
        assertEquals(1, daysOff(anna).size());
        assertEquals(2, daysOff(dee).size(), "5-day workers get two days");
        assertTrue(daysOff(dee).stream().allMatch(DayOffRequest::isReserved));
        assertEquals(DayOffStatus.PENDING, daysOff(anna).getFirst().getStatus(), "only reserved, not approved");
        // Anna and Ben share a store: their reserved days differ.
        assertFalse(daysOff(anna).getFirst().getOffDate().equals(daysOff(ben).getFirst().getOffDate()));
        // Running again changes nothing.
        assertEquals(0, reservations.reserveWeek(area, week));
    }

    @Test
    void askingForAnotherDayIsGivenStraightAwayAndTheReservedDayGoesBack() {
        LocalDate reserved = daysOff(anna).getFirst().getOffDate();
        LocalDate wanted = week.datesUntil(week.plusDays(7))
                .filter(d -> !d.equals(reserved)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        var result = dayOffService.create(new CreateDayOffRequest(anna, List.of(wanted), "Family", null));
        assertEquals(DayOffStatus.APPROVED, result.getFirst().status(), "the stores stay covered, so it's given");

        TestUsers.signInAsAdmin();
        List<DayOffRequest> mine = daysOff(anna);
        assertEquals(1, mine.size(), "approved: the reserved day went back to work");
        assertEquals(wanted, mine.getFirst().getOffDate());
        // Whoever else at North had that day reserved got another day; everyone still has their days.
        var status = weekRequests.status(area, week);
        assertEquals(0, status.missing().size());
        assertEquals(1, daysOff(ben).size());
        assertFalse(daysOff(ben).getFirst().getOffDate().equals(wanted));
    }

    /** "Approve all that fit" with a reserved day in the batch that an earlier approval gives back. */
    @Test
    void approveManySkipsAReservedDayThatAnEarlierApprovalGaveBack() {
        DayOffRequest reservedRow = daysOff(anna).getFirst();
        Long reservedId = reservedRow.getId();
        LocalDate reserved = reservedRow.getOffDate();
        // Prefer a day before the reserved one: the batch runs earliest day first, so her request is approved first
        // and gives the reserved day back while it is still waiting in the batch (the case that used to fail).
        List<LocalDate> open = week.datesUntil(week.plusDays(7))
                .filter(d -> !d.equals(reserved)).filter(d -> d.isAfter(portal.today())).toList();
        LocalDate wanted = open.stream().filter(d -> d.isBefore(reserved)).findFirst().orElse(open.getFirst());
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        // An emergency waits for a decision, so it can be approved in the same batch as her reserved day.
        Long asked = dayOffService.create(new CreateDayOffRequest(anna, List.of(wanted), "Family", true)).getFirst().id();

        TestUsers.signInAsAdmin();
        var result = assertDoesNotThrow(() -> dayOffService.approveMany(List.of(reservedId, asked), false));
        assertTrue(result.approved().stream().anyMatch(r -> r.id().equals(asked)));
        if (wanted.isBefore(reserved)) {
            assertFalse(requests.findById(reservedId).isPresent(), "given back while approving");
            assertTrue(result.approved().stream().noneMatch(r -> r.id().equals(reservedId)));
        }
        assertTrue(daysOff(anna).stream().anyMatch(r -> r.getStatus() == DayOffStatus.APPROVED
                && r.getOffDate().equals(wanted)));
    }

    @Test
    void aTurnedDownRequestLeavesTheReservedDay() {
        LocalDate reserved = daysOff(cara).getFirst().getOffDate();
        LocalDate other = week.datesUntil(week.plusDays(7))
                .filter(d -> !d.equals(reserved)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        Long id = dayOffService.create(new CreateDayOffRequest(cara, List.of(other), "Urgent", true)).getFirst().id();
        TestUsers.signInAsAdmin();
        dayOffService.reject(id, "Busy day");
        List<DayOffRequest> mine = daysOff(cara);
        assertEquals(1, mine.size());
        assertEquals(reserved, mine.getFirst().getOffDate());
    }

    @Test
    void askingForTheReservedDayMakesItTheirsStraightAway() {
        LocalDate reserved = daysOff(cara).getFirst().getOffDate();
        TestUsers.signInAs(cara, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(cara, List.of(reserved), null, null));
        assertEquals(1, daysOff(cara).size());
        assertFalse(daysOff(cara).getFirst().isReserved());
        assertEquals(DayOffStatus.APPROVED, daysOff(cara).getFirst().getStatus());
    }

    @Test
    void reservedDaysAreHiddenFromEmployeesAndGivenWhenTheWeekIsMade() {
        reservations.reserveUpcoming();
        boolean anyApproved = requests.findAll().stream()
                .filter(r -> r.getEmployee().getArea() != null && r.getEmployee().getArea().getId().equals(area))
                .anyMatch(r -> r.getStatus() == DayOffStatus.APPROVED);
        assertFalse(anyApproved, "reserving alone gives nothing yet");
        // Employees never see the days the system holds for them.
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertTrue(dayOffService.search(null, null, null, null, null).isEmpty());
        // Making the week gives everyone their reserved day (nobody had to approve anything).
        TestUsers.signInAsAdmin();
        var made = scheduleService.generate(area, week, true);
        for (Long who : List.of(anna, ben, cara, dee)) {
            assertTrue(daysOff(who).stream().allMatch(r -> r.getStatus() == DayOffStatus.APPROVED), "given: " + who);
            LocalDate off = daysOff(who).getFirst().getOffDate();
            var cell = made.rows().stream().filter(r -> r.employeeId().equals(who)).findFirst().orElseThrow()
                    .days().get(off.getDayOfWeek().getValue() - 1);
            assertEquals(com.example.schedulingsoftware.schedule.DayType.REQUESTED_OFF, cell.type());
            assertEquals("RESERVED", cell.offSource(), "the schedule says where the day off came from");
        }
        assertTrue(made.requests().isEmpty(), "nothing left waiting for a manager");
        // Employees can't drop their reserved day: they ask for another one instead.
        TestUsers.signInAs(ben, Role.EMPLOYEE, area);
        Long bensReserved = daysOff(ben).getFirst().getId();
        assertThrows(BusinessRuleException.class, () -> dayOffService.cancel(bensReserved));
    }

    @Test
    void anApprovedReservedDayStillGivesWayToALaterApprovedRequest() {
        TestUsers.signInAsAdmin();
        DayOffRequest reserved = daysOff(dee).getFirst();
        dayOffService.approve(reserved.getId(), null);
        assertEquals(DayOffStatus.APPROVED, requests.findById(reserved.getId()).orElseThrow().getStatus());
        List<LocalDate> deeDays = daysOff(dee).stream().map(DayOffRequest::getOffDate).toList();
        LocalDate wanted = week.datesUntil(week.plusDays(7)).filter(d -> !deeDays.contains(d))
                .filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        TestUsers.signInAs(dee, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(dee, List.of(wanted), null, null)); // given right away
        TestUsers.signInAsAdmin();
        // Dee takes 2 days a week: her own approved day plus one reserved day remain.
        assertEquals(2, daysOff(dee).size());
        assertTrue(daysOff(dee).stream().anyMatch(r -> r.getOffDate().equals(wanted)));
    }

    @Test
    void adminsOpenAndCloseRequests() {
        TestUsers.signInAsAdmin();
        dayOffService.setPortal(PortalMode.CLOSED);
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        BusinessRuleException closed = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(week.plusDays(2)), null, null)));
        assertTrue(closed.getMessage().contains("closed right now"), closed.getMessage());

        TestUsers.signInAsAdmin();
        var w = dayOffService.setPortal(PortalMode.OPEN);
        assertTrue(w.openWeeks().contains(DayOffService.weekStart(portal.today()).plusWeeks(1)), "next week reopened");
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> dayOffService.setPortal(PortalMode.AUTO));
    }

    @Test
    void theWeekCanBeMadeWhileRequestsAreStillOpen() {
        TestUsers.signInAsAdmin();
        scheduleService.generate(area, week);
        // A manager approves the reserved days in one go; then they're off on the schedule.
        List<Long> reservedIds = requests.findAll().stream()
                .filter(r -> r.isReserved() && r.getStatus() == DayOffStatus.PENDING)
                .filter(r -> r.getEmployee().getArea().getId().equals(area))
                .map(DayOffRequest::getId).toList();
        var result = dayOffService.approveMany(reservedIds, false);
        assertEquals(reservedIds.size(), result.approved().size());
        var schedule = scheduleService.generate(area, week);
        long annaOff = schedule.rows().stream().filter(r -> r.employeeId().equals(anna)).findFirst().orElseThrow()
                .days().stream().filter(c -> c.type() != com.example.schedulingsoftware.schedule.DayType.WORK).count();
        assertTrue(annaOff >= 1, "her approved reserved day is off on the schedule");
    }

    // ---- Regular days off -------------------------------------------------------------------------------------

    /** A day in the open week that is still ahead (reservations are only made for days after today). */
    private LocalDate futureDay(int skip) {
        return week.datesUntil(week.plusDays(7)).filter(d -> d.isAfter(portal.today())).skip(skip).findFirst()
                .orElseThrow();
    }

    private void setRegular(Long employee, java.time.DayOfWeek... days) {
        employeeService.setRegularDaysOff(employee, List.of(days));
        reservations.replanFor(employeeService.getEmployee(employee));
    }

    private DayOffRequest onDay(Long employee, LocalDate date) {
        return daysOff(employee).stream().filter(r -> r.getOffDate().equals(date)).findFirst().orElse(null);
    }

    @Test
    void aRegularDayOffIsReservedAndGivenWhenTheWeekIsMade() {
        LocalDate tue = futureDay(1);
        setRegular(anna, tue.getDayOfWeek());
        DayOffRequest r = onDay(anna, tue);
        assertTrue(r != null && r.isReserved() && r.isRegular(), "regular day reserved");
        assertEquals(DayOffStatus.PENDING, r.getStatus(), "held until the week is made");
        assertEquals(1, daysOff(anna).size(), "still only her usual one day off");
        scheduleService.generate(area, week, true);
        assertEquals(DayOffStatus.APPROVED, onDay(anna, tue).getStatus());
    }

    @Test
    void aRegularDayComesBeforeARequest() {
        LocalDate regular = futureDay(1);
        LocalDate other = futureDay(3);
        setRegular(anna, regular.getDayOfWeek());
        // Anna works 6 days: her regular day is her one day off, so a normal request for another day is refused.
        TestUsers.signInAs(anna, Role.EMPLOYEE, area);
        BusinessRuleException e = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(anna, List.of(other), null, null)));
        assertTrue(e.getMessage().contains("regular day off"), e.getMessage());
        // Asking for her regular day just confirms it, still labelled regular.
        dayOffService.create(new CreateDayOffRequest(anna, List.of(regular), null, null));
        assertTrue(onDay(anna, regular).isRegular());
        assertEquals(DayOffStatus.APPROVED, onDay(anna, regular).getStatus());
        // She sees her regular day; an emergency still goes to a manager, and approving it never drops the regular day.
        assertEquals(1, dayOffService.search(null, null, null, null, null).size());
        Long urgent = dayOffService.create(new CreateDayOffRequest(anna, List.of(other), "Sick", true)).getFirst().id();
        TestUsers.signInAsAdmin();
        dayOffService.approve(urgent, null);
        assertTrue(onDay(anna, regular) != null && onDay(anna, regular).isRegular(), "regular day kept");
    }

    @Test
    void withTwoDaysOffTheRegularOneStaysAndARequestReplacesTheReservedOne() {
        LocalDate regular = futureDay(0);
        setRegular(dee, regular.getDayOfWeek());
        // Dee takes 2 days: her regular day plus one the system picked.
        assertEquals(1, daysOff(dee).stream().filter(DayOffRequest::isRegular).count());
        assertEquals(1, daysOff(dee).stream().filter(r -> r.isReserved() && !r.isRegular()).count());
        LocalDate picked = daysOff(dee).stream().filter(r -> !r.isRegular()).findFirst().orElseThrow().getOffDate();
        LocalDate wanted = week.datesUntil(week.plusDays(7))
                .filter(d -> d.isAfter(portal.today()) && !d.equals(regular) && !d.equals(picked)).findFirst().orElseThrow();
        TestUsers.signInAs(dee, Role.EMPLOYEE, area);
        // She can ask for one day besides her regular day: it's given, and the system's pick goes back.
        assertEquals(DayOffStatus.APPROVED,
                dayOffService.create(new CreateDayOffRequest(dee, List.of(wanted), null, null)).getFirst().status());
        TestUsers.signInAsAdmin();
        List<DayOffRequest> mine = daysOff(dee);
        assertEquals(2, mine.size());
        assertTrue(mine.stream().anyMatch(r -> r.isRegular() && r.getOffDate().equals(regular)), "regular first");
        assertTrue(mine.stream().anyMatch(r -> !r.isReserved() && r.getOffDate().equals(wanted)), "then her request");
        // A third day is one too many.
        LocalDate third = week.datesUntil(week.plusDays(7))
                .filter(d -> d.isAfter(portal.today()) && !d.equals(regular) && !d.equals(wanted)).findFirst().orElseThrow();
        TestUsers.signInAs(dee, Role.EMPLOYEE, area);
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(dee, List.of(third), null, null)));
    }

    @Test
    void twoPeopleAtTheSameStoreCantShareARegularDayOff() {
        java.time.DayOfWeek day = futureDay(1).getDayOfWeek();
        setRegular(anna, day);
        // Ben works at North too.
        BusinessRuleException same = assertThrows(BusinessRuleException.class,
                () -> employeeService.setRegularDaysOff(ben, List.of(day), reservations::checkRegularDays));
        assertTrue(same.getMessage().contains("Anna") && same.getMessage().contains("North"), same.getMessage());
        // Cara at South can have the same day; Ben can have another.
        employeeService.setRegularDaysOff(cara, List.of(day), reservations::checkRegularDays);
        employeeService.setRegularDaysOff(ben, List.of(futureDay(2).getDayOfWeek()), reservations::checkRegularDays);
    }

    @Test
    void aRegularDayIsRefusedWhenTheStoresCouldntBeCovered() {
        java.time.DayOfWeek day = futureDay(1).getDayOfWeek();
        // North needs 2 and South 1: 3 of the 6 must work. Anna (North) and both floaters off leaves Ben, Cara, Dee.
        Long north = weekRequests.staffOf(area).stream().filter(e -> e.getId().equals(anna)).findFirst().orElseThrow()
                .getHomeStore().getId();
        storeService.update(north, new StoreRequest(area, "North", "RN", null, 2, true, null, null));
        Long eli = weekRequests.staffOf(area).stream().filter(e -> e.getFirstName().equals("Eli")).findFirst()
                .orElseThrow().getId();
        Long fay = weekRequests.staffOf(area).stream().filter(e -> e.getFirstName().equals("Fay")).findFirst()
                .orElseThrow().getId();
        for (Long who : List.of(anna, eli, fay)) {
            employeeService.setRegularDaysOff(who, List.of(day), reservations::checkRegularDays);
        }
        // Cara (South) as well would leave 2 people for 3 shifts.
        BusinessRuleException tooMany = assertThrows(BusinessRuleException.class,
                () -> employeeService.setRegularDaysOff(cara, List.of(day), reservations::checkRegularDays));
        assertTrue(tooMany.getMessage().contains("1 person short"), tooMany.getMessage());
        // Another day is fine for her.
        employeeService.setRegularDaysOff(cara, List.of(futureDay(2).getDayOfWeek()), reservations::checkRegularDays);
    }

    @Test
    void aRegularDayIsNotMovedWhenSomeoneElsesRequestIsApproved() {
        LocalDate day = futureDay(2);
        setRegular(cara, day.getDayOfWeek());
        Long regularId = onDay(cara, day).getId();
        // Dee (same store) can't simply ask for it: it's Cara's standing day, like a real request.
        TestUsers.signInAs(dee, Role.EMPLOYEE, area);
        assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(dee, List.of(day), null, null)));
        // An emergency still can be sent; approving it leaves Cara's regular day where it is.
        Long emergency = dayOffService.create(new CreateDayOffRequest(dee, List.of(day), "Urgent", true)).getFirst().id();
        TestUsers.signInAsAdmin();
        dayOffService.approve(emergency, null);
        assertTrue(requests.findById(regularId).isPresent(), "regular day stays");
        assertEquals(DayOffStatus.PENDING, requests.findById(regularId).orElseThrow().getStatus());
    }

    @Test
    void changingTheRegularDayMovesTheReservation() {
        LocalDate first = futureDay(1);
        LocalDate second = futureDay(3);
        setRegular(anna, first.getDayOfWeek());
        assertTrue(onDay(anna, first).isRegular());
        setRegular(anna, second.getDayOfWeek());
        assertEquals(null, onDay(anna, first), "old regular day given back");
        assertTrue(onDay(anna, second).isRegular());
        // Clearing them: the system picks a day again, as before.
        setRegular(anna);
        assertEquals(1, daysOff(anna).size());
        assertFalse(daysOff(anna).getFirst().isRegular());
    }

    @Test
    void someoneWithARegularDayCountsAsHavingChosen() {
        assertTrue(weekRequests.status(area, week).reserved().stream().anyMatch(p -> p.name().startsWith("Anna")));
        setRegular(anna, futureDay(1).getDayOfWeek());
        var status = weekRequests.status(area, week);
        assertFalse(status.reserved().stream().anyMatch(p -> p.name().startsWith("Anna")),
                "her regular day is her choice, not one the system picked");
        assertTrue(status.regular().stream().anyMatch(p -> p.name().startsWith("Anna")));
    }

    @Test
    void noMoreRegularDaysThanTheirUsualDaysOff() {
        BusinessRuleException tooMany = assertThrows(BusinessRuleException.class,
                () -> employeeService.setRegularDaysOff(anna, List.of(java.time.DayOfWeek.MONDAY,
                        java.time.DayOfWeek.TUESDAY)));
        assertTrue(tooMany.getMessage().contains("1 regular day off"), tooMany.getMessage());
        // Dee works 5 days: two regular days are fine.
        setRegular(dee, futureDay(0).getDayOfWeek(), futureDay(2).getDayOfWeek());
        assertEquals(2, daysOff(dee).stream().filter(DayOffRequest::isRegular).count());
    }

    @Test
    void managersOfAnotherAreaCannotSetThem() {
        Long other = areaService.create(new AreaRequest("Elsewhere", null, true)).id();
        TestUsers.signInAs(999L, Role.MANAGER, other);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> employeeService.setRegularDaysOff(anna, List.of(java.time.DayOfWeek.MONDAY)));
    }
}
