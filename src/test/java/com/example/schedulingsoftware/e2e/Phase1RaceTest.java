package com.example.schedulingsoftware.e2e;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.dayoff.DayOffDtos.CreateDayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.dayoff.PortalMode;
import com.example.schedulingsoftware.dayoff.RequestPortal;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Two colleagues at one store ask for the same day at the same moment (no test transaction: real commits). */
@SpringBootTest(properties = { "alliancetel.scheduling.request-window=true" })
class Phase1RaceTest {

    @Autowired AreaService areaService;
    @Autowired StoreService storeService;
    @Autowired EmployeeService employeeService;
    @Autowired DayOffService dayOffService;
    @Autowired DayOffRequestRepository requests;
    @Autowired RequestPortal portal;

    @Test
    void twoColleaguesSameDaySameMoment() throws Exception {
        TestUsers.signInAsAdmin();
        portal.setMode(PortalMode.AUTO, "test");
        LocalDate day = RequestPortal.openWeek(portal.today()).plusDays(DayOfWeek.WEDNESDAY.getValue() - 1L);
        Long area = areaService.create(new AreaRequest("Race Area " + System.nanoTime(), null, true)).id();
        Long store = storeService.create(new StoreRequest(area, "Race", "RAC", null, 1, true, null, null)).id();
        List<Long> people = new ArrayList<>();
        int rounds = 20;
        int doubles = 0;
        for (int round = 0; round < rounds; round++) {
            Long s = storeService.create(new StoreRequest(area, "Race" + round, "R" + round, null, 1, true, null, null)).id();
            Long p = make(area, s, "P" + round, true);
            Long q = make(area, s, "Q" + round, false);
            make(area, null, "F" + round, null);
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            for (Long id : List.of(p, q)) {
                pool.submit(() -> {
                    TestUsers.signInAs(id, Role.EMPLOYEE, area);
                    try {
                        go.await();
                        dayOffService.create(new CreateDayOffRequest(id, List.of(day), null, false));
                        results.add("ok");
                    } catch (Exception e) {
                        results.add("refused");
                    } finally {
                        TestUsers.signOut();
                    }
                    return null;
                });
            }
            go.countDown();
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
            long given = requests.findAll().stream().filter(r -> r.getOffDate().equals(day)
                    && r.getStatus() == DayOffStatus.APPROVED
                    && (r.getEmployee().getId().equals(p) || r.getEmployee().getId().equals(q))).count();
            if (given > 1) {
                doubles++;
            }
            TestUsers.signInAsAdmin();
        }
        // Control: the same two people one after the other.
        Long s2 = storeService.create(new StoreRequest(area, "Seq", "SEQ", null, 1, true, null, null)).id();
        Long p2 = make(area, s2, "SP", true);
        Long q2 = make(area, s2, "SQ", false);
        make(area, null, "SF", null);
        String control;
        TestUsers.signInAs(p2, Role.EMPLOYEE, area);
        dayOffService.create(new CreateDayOffRequest(p2, List.of(day), null, false));
        TestUsers.signInAs(q2, Role.EMPLOYEE, area);
        try {
            dayOffService.create(new CreateDayOffRequest(q2, List.of(day), null, false));
            control = "sequential: both given";
        } catch (Exception e) {
            control = "sequential: second refused (" + e.getMessage() + ")";
        }
        System.out.println("RACE CONTROL: " + control);
        System.out.println("RACE RESULT: " + doubles + " of " + rounds + " rounds gave BOTH primary and secondary the day");
        org.junit.jupiter.api.Assertions.assertEquals(0, doubles, "REQ 8.7 race: primary and secondary both given the same day");
    }

    private Long make(Long area, Long store, String code, Boolean primary) {
        TestUsers.signInAsAdmin();
        String c = code + System.nanoTime() % 100000;
        return employeeService.create(new EmployeeRequest(c, "Race", "Test", c.toLowerCase() + "@test.example", null,
                Role.EMPLOYEE, area, store, 6, true, primary, null, null, null, null, null, null)).employee().id();
    }
}
