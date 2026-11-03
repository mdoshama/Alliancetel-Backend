package com.example.schedulingsoftware.commission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.AreaDtos.AreaRequest;
import com.example.schedulingsoftware.commission.CommissionDtos.Statement;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.JwtService;
import com.example.schedulingsoftware.common.BusinessRuleException;
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
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** Commission is paid on approved EODs, includes fines and rewards, locks when finalized, and only admins see it. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CommissionIntegrationTest {

    @Autowired
    CommissionService commission;
    @Autowired
    EodService eod;
    @Autowired
    AreaService areaService;
    @Autowired
    StoreService storeService;
    @Autowired
    EmployeeService employeeService;
    @Autowired
    EmployeeRepository employeeRepository;
    @Autowired
    JwtService jwtService;
    @Autowired
    MockMvc mvc;

    Long area;
    Long store;
    Long manager;
    Long ana;
    LocalDate today = LocalDate.now();
    YearMonth month = YearMonth.now();

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("Comm Area", null, true)).id();
        store = storeService.create(new StoreRequest(area, "Comm Store", "CMS", null, null, true, null, null)).id();
        manager = person("CM-M", "Mia", Role.MANAGER);
        ana = person("CM-1", "Ana", Role.EMPLOYEE);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    @Test
    void paysOnApprovedEodsWithFinesAndRewards() {
        as(ana);
        eod.addLine(sale(EodCategory.NL_AAL, "40", "0", "40"));
        eod.addLine(sale(EodCategory.HINT, "0", "0", "0"));
        eod.addLine(accessory("100"));
        eod.submit(new ClosingRequest(store, today, new BigDecimal("40"), new BigDecimal("140"), BigDecimal.ZERO, null,
                null, null, null));

        TestUsers.signInAsAdmin();
        Statement before = mine(commission.month(month, area));
        money("0.00", before.pay().total(), "not approved yet: nothing to pay");
        assertEquals(2, before.pendingBoxes());
        assertTrue(commission.month(month, area).warnings().stream().anyMatch(w -> w.contains("approved")));

        as(manager);
        eod.approve(eod.find(store, today).id());
        TestUsers.signInAsAdmin();
        commission.addAdjustment(new CommissionDtos.AdjustmentRequest(ana, today, AdjustmentType.LATE, 7, null, null));
        commission.addAdjustment(new CommissionDtos.AdjustmentRequest(ana, today, AdjustmentType.SCORECARD_REWARD, null,
                null, "Top 1000"));
        var pay = mine(commission.month(month, area)).pay();
        money("1.00", pay.activationPay()); // 1 × $1
        money("5.00", pay.homeInternetPay()); // $5
        money("2.00", pay.accessoryPay()); // 2% of $100
        money("-7.00", pay.fines()); // 7 min × $1
        money("50.00", pay.rewards());
        money("51.00", pay.total());
    }

    @Test
    void accessoriesComeOffAtTheStoresTaxRate() {
        TestUsers.signInAsAdmin();
        storeService.update(store, new StoreRequest(area, "Comm Store", "CMS", null, null, true, null, null,
                new BigDecimal("8")));
        as(ana);
        eod.addLine(accessory("108.00")); // $100 before 8% tax
        eod.submit(new ClosingRequest(store, today, BigDecimal.ZERO, new BigDecimal("108"), BigDecimal.ZERO, null,
                null, null, null));
        as(manager);
        eod.approve(eod.find(store, today).id());
        TestUsers.signInAsAdmin();
        var pay = mine(commission.month(month, area)).pay();
        money("108.00", pay.accessoriesGross());
        money("100.00", pay.accessoriesNet());
        money("2.00", pay.accessoryPay());
        assertFalse(commission.month(month, area).warnings().stream().anyMatch(w -> w.contains("Comm Store")),
                "no tax warning once the store has a rate");
    }

    @Test
    void finalizingLocksTheMonthUntilReopened() {
        as(ana);
        eod.addLine(sale(EodCategory.UPG, "26", "0", "26"));
        eod.submit(new ClosingRequest(store, today, new BigDecimal("26"), new BigDecimal("26"), BigDecimal.ZERO, null,
                null, null, null));
        as(manager);
        eod.approve(eod.find(store, today).id());

        TestUsers.signInAsAdmin();
        var finalized = commission.finalizeMonth(month);
        assertTrue(finalized.finalized());
        money("4.00", mine(commission.month(month, area)).pay().total());

        // A new rate doesn't change a finalized month, and fines are locked.
        var plan = commission.plan();
        commission.updatePlan(withUpgradeRate(plan, new BigDecimal("9.00")));
        money("4.00", mine(commission.month(month, area)).pay().total());
        assertThrows(BusinessRuleException.class, () -> commission.addAdjustment(
                new CommissionDtos.AdjustmentRequest(ana, today, AdjustmentType.NO_UNIFORM, null, null, null)));

        var reopened = commission.reopenMonth(month);
        assertFalse(reopened.finalized());
        money("9.00", mine(commission.month(month, area)).pay().total(), "live again with the new rate");
    }

    @Test
    void onlyAdminsSeeCommission() throws Exception {
        as(manager);
        assertThrows(AccessDeniedException.class, () -> commission.month(month, area));
        as(ana);
        assertThrows(AccessDeniedException.class, () -> commission.plan());
        for (Long who : new Long[] {manager, ana}) {
            mvc.perform(get("/api/commission/months/" + month).header("Authorization", "Bearer " + token(who)))
                    .andExpect(status().isForbidden());
        }
    }

    // ---------- helpers ----------

    private Statement mine(CommissionDtos.MonthResponse m) {
        return m.statements().stream().filter(s -> s.employeeId().equals(ana)).findFirst().orElseThrow();
    }

    private static CommissionDtos.PlanDto withUpgradeRate(CommissionDtos.PlanDto p, BigDecimal rate) {
        return new CommissionDtos.PlanDto(p.activationTier1Max(), p.activationTier1Rate(), p.activationTier2Max(),
                p.activationTier2Rate(), p.activationTier3Rate(), p.upgradeTier1Max(), rate, p.upgradeTier2Rate(),
                p.btsRate(), p.homeInternetRate(), p.unlockRate(), p.metroLockedRate(), p.freeLinePaysAs(),
                p.reactivationPaysAs(), p.costPercent(), p.accessoryTier1Max(),
                p.accessoryTier1Percent(), p.accessoryTier3From(), p.accessoryTier2Percent(), p.accessoryTier3Percent(),
                p.accessoryMode(), p.lateFinePerMinute(), p.noCallNoShowFine(), p.noUniformFine(),
                p.missedMeetingFine(), p.missedSyncMeetingFine(), p.scorecardReward(), null, null);
    }

    private LineRequest sale(EodCategory category, String qpay, String retail, String soldFor) {
        return new LineRequest(store, today, EodLineType.SALE, category, "Phone", new BigDecimal(qpay),
                new BigDecimal(retail), new BigDecimal(soldFor), null, null, null, null, null, null);
    }

    private LineRequest accessory(String amount) {
        return new LineRequest(store, today, EodLineType.ACCESSORY, null, null, null, null, new BigDecimal(amount), null,
                null, null, null, null, null);
    }

    private void as(Long id) {
        Employee e = employeeRepository.findById(id).orElseThrow();
        TestUsers.signInAs(id, e.getRole(), e.getArea() == null ? null : e.getArea().getId());
    }

    private Long person(String code, String first, Role role) {
        Long id = employeeService.create(new EmployeeRequest(code, first, "Tester", code.toLowerCase() + "@test.example",
                null, role, area, store, 6, true, null)).employee().id();
        Employee e = employeeRepository.findById(id).orElseThrow();
        e.setMustChangePassword(false);
        employeeRepository.saveAndFlush(e);
        return id;
    }

    private String token(Long id) {
        return jwtService.issue(id, employeeRepository.findById(id).orElseThrow().getTokenVersion()).token();
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " was " + actual);
    }

    private static void money(String expected, BigDecimal actual, String why) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), why + ": expected " + expected + " was " + actual);
    }

}
