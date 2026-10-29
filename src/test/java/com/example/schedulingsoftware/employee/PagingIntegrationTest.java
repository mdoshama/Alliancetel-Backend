package com.example.schedulingsoftware.employee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.common.PageResponse;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffDtos.DayOffResponse;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Paged, searchable staff list and day-off list. */
@SpringBootTest
@Transactional
class PagingIntegrationTest {

    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    DayOffService dayOffService;

    Long area;
    Long otherArea;
    Long north;
    Long south;
    List<Long> people = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Paging Area", null, true)).id();
        otherArea = areaService.create(new AreaRequest("Other Paging Area", null, true)).id();
        north = storeService.create(new StoreRequest(area, "North", "PNOR", null, 1, true, null, null)).id();
        south = storeService.create(new StoreRequest(area, "South", "PSOU", null, 1, true, null, null)).id();
        // 23 people: 12 at North (first one primary), 8 at South, 3 floaters.
        for (int i = 1; i <= 23; i++) {
            Long store = i <= 12 ? north : i <= 20 ? south : null;
            String last = String.format("Pager%02d", i);
            people.add(employeeService.create(new EmployeeRequest("PG-" + i, "Pat", last, "pg" + i + "@test.example",
                    null, Role.EMPLOYEE, area, store, 6, true, i == 1)).employee().id());
        }
        employeeService.create(new EmployeeRequest("PG-X", "Olga", "Outside", "pgx@test.example", null,
                Role.EMPLOYEE, otherArea, null, 6, true, null));
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    private PageResponse<EmployeeResponse> page(Long store, StoreRole role, String q, int page, int size) {
        return employeeService.page(area, null, true, store, role, q, page, size, null);
    }

    @Test
    void pagesThroughTheStaffListInNameOrder() {
        PageResponse<EmployeeResponse> first = page(null, null, null, 0, 10);
        assertEquals(23, first.totalElements());
        assertEquals(3, first.totalPages());
        assertEquals(10, first.content().size());
        assertEquals("Pager01", first.content().getFirst().lastName());

        PageResponse<EmployeeResponse> last = page(null, null, null, 2, 10);
        assertEquals(3, last.content().size());
        assertEquals("Pager23", last.content().getLast().lastName());
    }

    @Test
    void keepsPageSizeInRange() {
        assertEquals(PageResponse.MAX_SIZE, employeeService.page(area, null, null, null, null, null, -4, 5000, null).size());
        assertEquals(0, employeeService.page(area, null, null, null, null, null, -4, 5000, null).page());
        assertEquals(1, employeeService.page(area, null, null, null, null, null, 0, 0, null).size());
    }

    @Test
    void filtersByStoreAndStoreRole() {
        assertEquals(12, page(north, null, null, 0, 50).totalElements());
        assertEquals(8, page(south, null, null, 0, 50).totalElements());
        assertEquals(3, page(null, StoreRole.FLOATER, null, 0, 50).totalElements());
        assertEquals(1, page(null, StoreRole.PRIMARY, null, 0, 50).totalElements());
        assertEquals(19, page(null, StoreRole.SECONDARY, null, 0, 50).totalElements());
    }

    @Test
    void searchesNameEmailCodeAndFullName() {
        assertEquals(1, page(null, null, "pager07", 0, 25).totalElements());
        assertEquals(1, page(null, null, "PAT PAGER07", 0, 25).totalElements());
        assertEquals(1, page(null, null, "pg-13", 0, 25).totalElements());
        assertEquals(1, page(null, null, "pg9@", 0, 25).totalElements());
        // % and _ are matched literally, not as wildcards.
        assertEquals(0, page(null, null, "%", 0, 25).totalElements());
        assertEquals(0, page(null, null, "pager_1", 0, 25).totalElements());
    }

    @Test
    void managersOnlySeeTheirOwnArea() {
        TestUsers.signInAs(people.getFirst(), Role.MANAGER, otherArea);
        PageResponse<EmployeeResponse> result = employeeService.page(null, null, null, null, null, null, 0, 25, null);
        assertEquals(1, result.totalElements());
        assertEquals("Outside", result.content().getFirst().lastName());
    }

    @Test
    void pagesAndSearchesDayOffRequests() {
        LocalDate monday = DayOffService.weekStart(LocalDate.now()).plusWeeks(2);
        for (int i = 0; i < 6; i++) {
            Long floater = people.get(20 + (i % 3));
            dayOffService.create(new CreateDayOffRequest(floater, List.of(monday.plusDays(i)), null, true));
        }
        PageResponse<DayOffResponse> first = dayOffService.page(area, null, null, monday, null, null, 0, 4);
        assertEquals(6, first.totalElements());
        assertEquals(2, first.totalPages());
        assertEquals(monday, first.content().getFirst().date());

        PageResponse<DayOffResponse> byName = dayOffService.page(area, null, null, monday, null, "pager21", 0, 25);
        assertEquals(2, byName.totalElements());
        assertTrue(byName.content().stream().allMatch(r -> r.employeeName().endsWith("Pager21")));
        assertEquals(2, dayOffService.page(area, null, null, monday, null, "PG-22", 0, 25).totalElements());
    }
}
