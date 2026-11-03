package com.example.schedulingsoftware.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.eod.EodCategory;
import com.example.schedulingsoftware.eod.EodDtos.ClosingRequest;
import com.example.schedulingsoftware.eod.EodDtos.LineRequest;
import com.example.schedulingsoftware.eod.EodLineType;
import com.example.schedulingsoftware.eod.EodService;
import com.example.schedulingsoftware.performance.PerformanceDtos.EmployeeStats;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Performance numbers come from the same credit rules as the EOD, and only the right people can see them. */
@SpringBootTest
@Transactional
class PerformanceIntegrationTest {

    @Autowired
    PerformanceService performance;
    @Autowired
    EodService eod;
    @Autowired
    ScheduleService scheduleService;
    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;

    Long area;
    Long otherArea;
    Long store;
    Long manager;
    Long ana;
    Long ben;
    Long outsider;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Perf Area", null, true)).id();
        otherArea = areaService.create(new AreaRequest("Perf Other", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Vista", "VST", null, null, true, null, null)).id();
        Long other = storeService.create(new StoreRequest(otherArea, "Far", "FAR", null, null, true, null, null)).id();
        manager = person("PF-M", "Mia", Role.MANAGER, area, store);
        ana = person("PF-1", "Ana", Role.EMPLOYEE, area, store);
        ben = person("PF-2", "Ben", Role.EMPLOYEE, area, store);
        outsider = person("PF-3", "Olga", Role.EMPLOYEE, otherArea, other);
        // Everyone works today (published schedule), so shifts can be counted.
        scheduleService.publish(scheduleService.generate(area, today).id());
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void leaderboardRanksByBoxesWithAccessoriesAndShifts() {
        as(ana);
        eod.addLine(sale(EodCategory.NL_AAL, "50", "10", "80", null)); // $20 accessories
        eod.addLine(withShare(sale(EodCategory.UPG, "20", "0", "30", null), ben)); // $10 accessories, split 5/5
        as(ben);
        eod.addLine(accessory("40"));
        eod.submit(new ClosingRequest(store, today, BigDecimal.ZERO, new BigDecimal("80"), BigDecimal.ZERO, null, null,
                null, null));

        as(manager);
        var board = performance.leaderboard(null, null, today, today);
        EmployeeStats first = board.employees().getFirst();
        assertEquals(ana, first.employeeId(), "most boxes first");
        assertEquals(2, first.boxes());
        assertMoney("25.00", first.accessories());
        EmployeeStats b = board.employees().stream().filter(x -> x.employeeId().equals(ben)).findFirst().orElseThrow();
        assertEquals(0, b.boxes(), "sharing never gives a box");
        assertMoney("45.00", b.accessories());
        assertEquals(1, b.sharedSales());
        assertEquals(1, b.shifts());
        assertMoney("45.00", b.accessoriesPerShift());
        assertEquals(0, b.confirmedBoxes(), "not approved yet");

        assertTrue(board.employees().stream().anyMatch(x -> x.employeeId().equals(manager) && x.boxes() == 0),
                "people with no sales still show");
        assertTrue(board.employees().stream().noneMatch(x -> x.employeeId().equals(outsider)), "own area only");
        assertEquals(2, board.totals().boxes());
        assertMoney("70.00", board.totals().accessories());
        assertEquals(1, board.days().size());

        // Approving confirms the numbers (what commission will be paid on).
        Long reportId = eod.find(store, today).id();
        eod.approve(reportId);
        assertEquals(2, performance.leaderboard(null, null, today, today).employees().getFirst().confirmedBoxes());
    }

    @Test
    void profileShowsTheirDaysStoresAndRecentSales() {
        as(ana);
        eod.addLine(withShare(sale(EodCategory.NL_AAL, "50", "10", "80", null), ben));
        as(ben);
        var p = performance.profile(ben, today.minusDays(6), today);
        assertEquals(7, p.days().size(), "every day of the period");
        assertMoney("10.00", p.stats().accessories());
        assertEquals(0, p.stats().boxes());
        assertEquals(1, p.recent().size());
        assertTrue(p.recent().getFirst().shared());
        assertEquals("Vista", p.stores().getFirst().storeName());
        assertEquals(2, p.rank(), "Ana has the box");
    }

    @Test
    void whoCanSeeWhat() {
        as(ana);
        assertThrows(AccessDeniedException.class, () -> performance.leaderboard(null, null, today, today));
        assertThrows(AccessDeniedException.class, () -> performance.profile(ben, today, today), "only their own");
        assertEquals(ana, performance.profile(ana, today, today).employeeId(), "their own is fine");
        as(manager);
        assertThrows(AccessDeniedException.class, () -> performance.profile(outsider, today, today), "other area");
        TestUsers.signInAsAdmin();
        assertEquals(outsider, performance.profile(outsider, today, today).employeeId());
        assertTrue(performance.leaderboard(null, null, today, today).employees().stream()
                .anyMatch(x -> x.employeeId().equals(outsider)), "admins see every area");
    }

    // ---------- helpers ----------

    private LineRequest sale(EodCategory category, String qpay, String retail, String soldFor, Long rep) {
        return new LineRequest(store, today, EodLineType.SALE, category, "Phone", new BigDecimal(qpay),
                new BigDecimal(retail), new BigDecimal(soldFor), null, null, null, rep, null, null);
    }

    private LineRequest accessory(String amount) {
        return new LineRequest(store, today, EodLineType.ACCESSORY, null, null, null, null, new BigDecimal(amount), null,
                null, null, null, null, null);
    }

    private static LineRequest withShare(LineRequest r, Long with) {
        return new LineRequest(r.storeId(), r.date(), r.type(), r.category(), r.description(), r.qpay(), r.subtotal(),
                r.soldFor(), r.customerRef(), r.imeiLast4(), r.phone(), r.repId(), r.deviceId(), List.of(with));
    }

    private void as(Long id) {
        Employee e = employeeRepository.findById(id).orElseThrow();
        TestUsers.signInAs(id, e.getRole(), e.getArea() == null ? null : e.getArea().getId());
    }

    private Long person(String code, String first, Role role, Long areaId, Long home) {
        return employeeService.create(new EmployeeRequest(code, first, "Tester", code.toLowerCase() + "@test.example",
                null, role, areaId, home, 6, true, null)).employee().id();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }
}
