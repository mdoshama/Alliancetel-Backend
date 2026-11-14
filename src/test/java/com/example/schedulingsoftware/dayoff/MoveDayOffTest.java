package com.example.schedulingsoftware.dayoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.notify.NotificationDtos.MessageManyResult;
import com.example.schedulingsoftware.notify.NotificationService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/**
 * Someone changes their mind about their day off: it moves to another day that has room, given straight away, with no
 * emergency request. A day that isn't free (a colleague at the store is off) can't be moved to, and they keep theirs.
 */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true" })
@Transactional
class MoveDayOffTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;
    @Autowired
    DayOffRequestRepository requests;
    @Autowired
    RequestPortal portal;
    @Autowired
    NotificationService notifications;

    LocalDate week;
    Long area;
    Long safwan;
    Long zakir;

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        week = RequestPortal.openWeek(portal.today());
        area = areaService.create(new AreaRequest("Move Area", null, true)).id();
        Long telegraph = storeService.create(new StoreRequest(area, "Telegraph", "MT", null, 1, true, null, null)).id();
        Long mission = storeService.create(new StoreRequest(area, "Mission", "MM", null, 1, true, null, null)).id();
        safwan = employee("MV-1", "Safwan", telegraph, true);
        zakir = employee("MV-2", "Zakir", telegraph, false);
        employee("MV-3", "Hasham", mission, true);
        employee("MV-4", "Fareed", mission, false);
        employee("MV-5", "Surror", null, null);
        employee("MV-6", "Mujtaba", null, null);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private Long employee(String code, String first, Long store, Boolean primaryAtStore) {
        return employeeService.create(new EmployeeRequest(code, first, "Move", code.toLowerCase() + "@test.example",
                null, Role.EMPLOYEE, area, store, 6, true, primaryAtStore, null, null, null, null, null, null))
                .employee().id();
    }

    private List<DayOffRequest> live(Long employeeId) {
        return requests.findAll().stream()
                .filter(r -> r.getEmployee().getId().equals(employeeId) && r.getStatus() != DayOffStatus.REJECTED)
                .filter(r -> !r.getOffDate().isBefore(week) && r.getOffDate().isBefore(week.plusDays(7)))
                .toList();
    }

    private void signInAs(Long id) {
        TestUsers.signInAs(id, Role.EMPLOYEE, area);
    }

    @Test
    void aChangedMindMovesTheDayWithoutAnEmergency() {
        signInAs(safwan);
        DayOffResponse monday = dayOffService.create(new CreateDayOffRequest(safwan, List.of(week), "family", false))
                .getFirst();
        assertEquals(DayOffStatus.APPROVED, monday.status());
        // Asking for Wednesday as well is over the weekly limit: that's the emergency they were pushed to before.
        assertThrows(BusinessRuleException.class, () -> dayOffService.create(
                new CreateDayOffRequest(safwan, List.of(week.plusDays(2)), null, false)));

        DayOffResponse wednesday = dayOffService.move(monday.id(), week.plusDays(2));

        assertEquals(DayOffStatus.APPROVED, wednesday.status());
        assertEquals(week.plusDays(2), wednesday.date());
        List<DayOffRequest> days = live(safwan);
        assertEquals(1, days.size(), "still one day off, now Wednesday");
        assertEquals(week.plusDays(2), days.getFirst().getOffDate());
        assertTrue(!days.getFirst().isEmergency());
        assertEquals("family", days.getFirst().getReason());
    }

    @Test
    void aDayAColleagueAtTheStoreHasStaysTakenAndTheOldDayIsKept() {
        signInAs(zakir);
        dayOffService.create(new CreateDayOffRequest(zakir, List.of(week.plusDays(3)), null, false));
        signInAs(safwan);
        DayOffResponse monday = dayOffService.create(new CreateDayOffRequest(safwan, List.of(week), null, false))
                .getFirst();

        assertThrows(BusinessRuleException.class, () -> dayOffService.move(monday.id(), week.plusDays(3)));

        List<DayOffRequest> days = live(safwan);
        assertEquals(1, days.size());
        assertEquals(week, days.getFirst().getOffDate(), "Monday stays theirs");
    }

    @Test
    void onlyTheirOwnDayAndOnlyWithinTheWeek() {
        signInAs(safwan);
        DayOffResponse monday = dayOffService.create(new CreateDayOffRequest(safwan, List.of(week), null, false))
                .getFirst();
        assertThrows(BusinessRuleException.class, () -> dayOffService.move(monday.id(), week.plusDays(7)));
        signInAs(zakir);
        assertThrows(AccessDeniedException.class, () -> dayOffService.move(monday.id(), week.plusDays(1)));
    }

    @Test
    void messagingSeveralPeopleListsThoseItCantReach() {
        // Everyone is accounted for: sent, or listed with why not (a person who can't be reached doesn't stop the rest).
        MessageManyResult r = notifications.messageMany(List.of(safwan, zakir), "Hi {first}, please pick a day.");
        assertEquals(2, r.sent().size() + r.notReached().size());
    }
}
