package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.Coverage;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Days off are judged by whether the stores can really be covered (primaries stay put, floaters only go to their
 * stores and within each store's floater limit), plus the manager / admin tools: give a day off directly, generate
 * anyway, reset a schedule, and the one-time fix for reserved days an older version approved by itself.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.reserve-days-off=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class CapacityAndOverridesTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
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
    LocalDate day;
    Long area;
    Long north;
    Long south;
    Long n1;
    Long n2;
    Long s1;
    Long f1;
    Long f2;
    Long f3;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        day = week.datesUntil(week.plusDays(7)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        area = areaService.create(new AreaRequest("Capacity Area", null, true)).id();
        north = storeService.create(new StoreRequest(area, "North", "CN", null, 2, true, null, null)).id();
        // South takes at most one floater a day.
        south = storeService.create(new StoreRequest(area, "South", "CS", null, 2, true, null, null, 1)).id();
        n1 = employee("CP-1", "Nia", north, true, List.of());
        n2 = employee("CP-2", "Ned", north, false, List.of());
        s1 = employee("CP-3", "Sal", south, true, List.of());
        f1 = employee("CP-4", "Fin", null, false, List.of(north));
        f2 = employee("CP-5", "Flo", null, false, List.of(south));
        f3 = employee("CP-6", "Fox", null, false, List.of(south));
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, boolean primary, List<Long> floats) {
        return employeeService.create(new EmployeeRequest(code, first, "Cap", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, store == null ? null : primary, null, null, null, floats))
                .employee().id();
    }

    private Coverage coverage() {
        return weekRequests.coverage(area);
    }

    @Test
    void floatersOnlyCoverTheirOwnStores() {
        Coverage c = coverage();
        assertEquals(0, c.day(day, Set.of()).shortBy(), "everyone in: both stores covered");
        // North without Nia and Ned: only Fin floats to North (Flo and Fox are South only, Sal stays at South).
        Coverage.Day d = c.day(day, Set.of(n1, n2));
        assertEquals(1, d.shortBy());
        assertEquals(List.of("North"), d.shortStores());
        assertFalse(c.canBeOff(day, Set.of(n1), f1), "Fin is the only one who can cover North then");
        assertTrue(c.canBeOff(day, Set.of(n1), f2), "Flo can't help North anyway");
    }

    @Test
    void aStoresFloaterLimitCounts() {
        Coverage c = coverage();
        // Sal off: South gets one floater (its limit) and Ned moves over from North, Fin covers North.
        assertEquals(0, c.day(day, Set.of(s1)).shortBy());
        // Sal and Fin off: Ned has to stay at North, and South can't take Flo and Fox both.
        Coverage.Day d = c.day(day, Set.of(s1, f1));
        assertEquals(1, d.shortBy());
        assertEquals(List.of("South"), d.shortStores());
    }

    @Test
    void reservedDaysNeverLeaveTheStoresShortAndStayPending() {
        reservations.reserveWeek(area, week);
        Coverage c = coverage();
        List<DayOffRequest> held = requests.findAll().stream()
                .filter(r -> r.getEmployee().getArea().getId().equals(area)).toList();
        assertEquals(6, held.size());
        assertTrue(held.stream().allMatch(r -> r.isReserved() && r.getStatus() == DayOffStatus.PENDING));
        for (LocalDate d : week.datesUntil(week.plusDays(7)).toList()) {
            Set<Long> off = new java.util.HashSet<>();
            held.stream().filter(r -> r.getOffDate().equals(d)).forEach(r -> off.add(r.getEmployee().getId()));
            assertEquals(0, c.day(d, off).shortBy(), "stores covered on " + d + " with " + off.size() + " off");
        }
        var cap = weekRequests.capacity(area, week);
        assertEquals(28, cap.shiftsNeeded(), "4 people a day, 7 days");
        assertEquals(36, cap.shiftsAvailable(), "6 people, 6 days each");
        assertEquals(6, cap.daysOffToGive());
    }

    @Test
    void aDayIsFullWhenTheRightPeopleArentLeftToCoverIt() {
        // Ned is given the day off: North now needs Fin.
        dayOffService.create(new CreateDayOffRequest(n2, List.of(day), null, null, true));
        TestUsers.signInAs(f1, Role.EMPLOYEE, area);
        BusinessRuleException full = assertThrows(BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(f1, List.of(day), "Trip", null)));
        assertTrue(full.getMessage().contains("is full"), full.getMessage());
        // Flo can still take it: South is fine without one of its floaters.
        TestUsers.signInAs(f2, Role.EMPLOYEE, area);
        var ok = dayOffService.create(new CreateDayOffRequest(f2, List.of(day), "Trip", null));
        assertEquals(DayOffStatus.APPROVED, ok.getFirst().status(), "covered, so given right away");
    }

    @Test
    void managersCanGiveADayOffDirectlyAndTheReservedDayGoesBack() {
        reservations.reserveWeek(area, week);
        DayOffRequest held = requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(f1)).findFirst()
                .orElseThrow();
        LocalDate other = week.datesUntil(week.plusDays(7))
                .filter(d -> d.isAfter(portal.today()) && !d.equals(held.getOffDate())).findFirst().orElseThrow();
        // Even on a day the rules would refuse (Nia and Ned off too), the override gives it.
        dayOffService.create(new CreateDayOffRequest(n1, List.of(other), null, true, true));
        var given = dayOffService.create(new CreateDayOffRequest(f1, List.of(other), "Agreed", null, true));
        assertEquals(DayOffStatus.APPROVED, given.getFirst().status());
        List<DayOffRequest> fins = requests.findAll().stream().filter(r -> r.getEmployee().getId().equals(f1))
                .filter(r -> r.getStatus() != DayOffStatus.REJECTED).toList();
        assertEquals(1, fins.size(), "the reserved day went back to work");
        assertEquals(other, fins.getFirst().getOffDate());

        TestUsers.signInAs(f2, Role.EMPLOYEE, area);
        assertThrows(AccessDeniedException.class,
                () -> dayOffService.create(new CreateDayOffRequest(f2, List.of(other), null, null, true)));
    }

    @Test
    void generateAnywayAndReset() {
        LocalDate later = week.plusWeeks(3);
        assertThrows(BusinessRuleException.class, () -> scheduleService.generate(area, later),
                "nobody has a request in for that week");
        var made = scheduleService.generate(area, later, true);
        assertTrue(scheduleService.find(area, later).isPresent());
        scheduleService.publish(made.id());
        scheduleService.reset(made.id());
        assertTrue(scheduleService.find(area, later).isEmpty(), "gone, ready to be generated again");
        assertEquals(made.weekStart(), scheduleService.generate(area, later, true).weekStart());
    }

    @Test
    void reservedDaysAnOlderVersionApprovedGoBackToPendingOnce() {
        DayOffRequest r = new DayOffRequest();
        r.setEmployee(employeeRepository.findById(f3).orElseThrow());
        r.setOffDate(day);
        r.setReason(DayOffReservations.RESERVED_REASON);
        r.setReserved(true);
        r.setStatus(DayOffStatus.APPROVED);
        r.setReviewedAt(java.time.Instant.now());
        requests.save(r);
        RequestPortalSetting s = portal.setting();
        s.setReservedApprovalsUndoneAt(null);
        portal.save(s);

        assertEquals(1, reservations.undoSelfApprovedReservations());
        assertEquals(DayOffStatus.PENDING, requests.findById(r.getId()).orElseThrow().getStatus());

        // Only once: a reserved day a manager approves later stays approved.
        r.setStatus(DayOffStatus.APPROVED);
        assertEquals(0, reservations.undoSelfApprovedReservations());
        assertEquals(DayOffStatus.APPROVED, requests.findById(r.getId()).orElseThrow().getStatus());
    }
}
