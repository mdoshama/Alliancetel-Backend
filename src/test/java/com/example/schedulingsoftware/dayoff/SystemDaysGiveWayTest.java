package com.example.schedulingsoftware.dayoff;

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
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * A day the system picked for someone (who never asked) gives way to a colleague's real request, also after the
 * week was generated and the system gave those days. Seen live: Safwan's Monday was refused because "Zakir already
 * has Monday off at TELEGRAPH", but Zakir's Monday was only the system's pick; and floaters had to send emergency
 * requests on days only held by system picks.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true",
        "alliancetel.scheduling.reserve-days-off=true",
        "alliancetel.scheduling.require-requests-before-generate=true" })
@Transactional
class SystemDaysGiveWayTest {

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

    LocalDate week;
    Long area;
    Long primary;
    Long secondary;
    Long telegraph;
    Long mission;
    Long hasham;
    Long surror;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Give Way Area", null, true)).id();
        telegraph = storeService.create(new StoreRequest(area, "Telegraph", "GT", null, 1, true, null, null)).id();
        mission = storeService.create(new StoreRequest(area, "Mission", "GM", null, 1, true, null, null)).id();
        primary = employee("GW-1", "Safwan", telegraph, true);
        secondary = employee("GW-2", "Zakir", telegraph, false);
        hasham = employee("GW-3", "Hasham", mission, true);
        employee("GW-4", "Fareed", mission, false);
        surror = employee("GW-5", "Surror", null, null);
        employee("GW-6", "Mujtaba", null, null);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, Boolean primaryAtStore) {
        return employeeService.create(new EmployeeRequest(code, first, "Way", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, primaryAtStore, null, null, null, null, null, null))
                .employee().id();
    }

    private List<DayOffRequest> live(Long employeeId) {
        return requests.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(employeeId) && r.getStatus() != DayOffStatus.REJECTED)
                .filter(r -> !r.getOffDate().isBefore(week) && r.getOffDate().isBefore(week.plusDays(7)))
                .toList();
    }

    private DayType cell(ScheduleResponse s, Long employeeId, LocalDate date) {
        return s.rows().stream().filter(r -> r.employeeId().equals(employeeId)).findFirst().orElseThrow()
                .days().stream().filter(c -> c.date().equals(date)).findFirst().orElseThrow().type();
    }

    @Test
    void aColleaguesSystemDayMovesForARealRequestAfterTheWeekIsGenerated() {
        reservations.reserveWeek(area, week);
        // The week is generated: the system's picks are now given (approved by the system).
        scheduleService.generate(area, week, true);
        DayOffRequest zakirsDay = live(secondary).getFirst();
        assertTrue(zakirsDay.isReserved() && zakirsDay.isGivenBySystem(), "Zakir never asked: the system gave his day");
        LocalDate monday = zakirsDay.getOffDate();

        // Safwan (same store, primary) asks for that very day.
        DayOffResponse safwan = dayOffService.create(new CreateDayOffRequest(primary, List.of(monday), null, false))
                .getFirst();

        assertEquals(DayOffStatus.APPROVED, safwan.status(), "given, not turned down: " + safwan.reviewNote());
        // Zakir gets another day, already on the draft schedule, and works the day Safwan took.
        List<DayOffRequest> zakir = live(secondary);
        assertEquals(1, zakir.size(), "Zakir still has exactly one day off");
        LocalDate newDay = zakir.getFirst().getOffDate();
        assertFalse(newDay.equals(monday));
        assertEquals(DayOffStatus.APPROVED, zakir.getFirst().getStatus());
        ScheduleResponse s = scheduleService.find(area, week).orElseThrow();
        assertEquals(DayType.REQUESTED_OFF, cell(s, primary, monday));
        assertEquals(DayType.WORK, cell(s, secondary, monday));
        assertEquals(DayType.REQUESTED_OFF, cell(s, secondary, newDay));
        // Safwan's own system day (if he had one elsewhere) is gone: one day off each.
        assertEquals(1, live(primary).size());
    }

    @Test
    void aPublishedWeekKeepsItsDays() {
        reservations.reserveWeek(area, week);
        ScheduleResponse s = scheduleService.generate(area, week, true);
        scheduleService.publish(s.id());
        LocalDate zakirsDay = live(secondary).getFirst().getOffDate();

        // On a published week Zakir keeps his day, so the same store can't have both off: Safwan picks another day.
        var refused = assertThrows(com.example.schedulingsoftware.common.BusinessRuleException.class,
                () -> dayOffService.create(new CreateDayOffRequest(primary, List.of(zakirsDay), null, false)));
        assertTrue(refused.getMessage().contains("Only one person per store"), refused.getMessage());
        assertEquals(zakirsDay, live(secondary).getFirst().getOffDate());
    }

    @Test
    void aDayAManagerApprovedByHandStays() {
        reservations.reserveWeek(area, week);
        DayOffRequest zakirsDay = live(secondary).getFirst();
        dayOffService.approve(zakirsDay.getId(), null);

        // A manager's decision isn't moved by the system.
        assertThrows(com.example.schedulingsoftware.common.BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(primary, List.of(zakirsDay.getOffDate()), null, false)));
        assertEquals(DayOffStatus.APPROVED, requests.findById(zakirsDay.getId()).orElseThrow().getStatus());
    }

    @Test
    void aFloaterGetsADayThatIsOnlyFullBecauseOfASystemPickOnTheGeneratedSchedule() {
        // Both stores need 2 a day: with Zakir off (he asked) and Hasham off (the system's pick), the day looks full.
        storeService.update(telegraph, new StoreRequest(area, "Telegraph", "GT", null, 2, true, null, null));
        storeService.update(mission, new StoreRequest(area, "Mission", "GM", null, 2, true, null, null));
        LocalDate day = week.datesUntil(week.plusDays(7)).filter(d -> d.isAfter(portal.today())).findFirst().orElseThrow();
        DayOffRequest held = new DayOffRequest();
        held.setEmployee(employeeService.getEmployee(hasham));
        held.setOffDate(day);
        held.setReason(DayOffReservations.RESERVED_REASON);
        held.setReserved(true);
        requests.save(held);
        dayOffService.create(new CreateDayOffRequest(secondary, List.of(day), null, false));
        scheduleService.generate(area, week, true);
        assertTrue(requests.findById(held.getId()).orElseThrow().isGivenBySystem());

        // Surror (floater) asks for that day: no emergency needed, Hasham's pick moves.
        DayOffResponse r = dayOffService.create(new CreateDayOffRequest(surror, List.of(day), null, false)).getFirst();

        assertEquals(DayOffStatus.APPROVED, r.status(), "given straight away: " + r.reviewNote());
        assertFalse(r.emergency());
        List<DayOffRequest> hashams = live(hasham);
        assertEquals(1, hashams.size(), "Hasham still has his one day");
        assertFalse(hashams.getFirst().getOffDate().equals(day), "on another day");
        ScheduleResponse s = scheduleService.find(area, week).orElseThrow();
        assertEquals(DayType.WORK, cell(s, hasham, day));
        assertTrue(s.coverageGaps().stream().noneMatch(g -> g.date().equals(day)), "the stores stay covered that day");
    }
}
