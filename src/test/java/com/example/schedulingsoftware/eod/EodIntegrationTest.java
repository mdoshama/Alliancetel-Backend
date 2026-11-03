package com.example.schedulingsoftware.eod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.stream.Collectors;

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
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.JwtService;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeDtos.EmployeeRequest;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.EmployeeService;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.eod.EodDtos.CategoryCount;
import com.example.schedulingsoftware.eod.EodDtos.ClosingRequest;
import com.example.schedulingsoftware.eod.EodDtos.LineRequest;
import com.example.schedulingsoftware.eod.EodDtos.ReportResponse;
import com.example.schedulingsoftware.store.StoreDtos.StoreRequest;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.support.TestUsers;

/** End of day: the figures match the old daily sheet, and who can change what. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EodIntegrationTest {

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
    @Autowired
    com.example.schedulingsoftware.schedule.ScheduleService scheduleService;
    @Autowired
    com.example.schedulingsoftware.catalog.CatalogService catalog;

    Long area;
    Long otherArea;
    Long coachella;
    Long otherStore;
    Long manager;
    Long hasnain;
    Long oshama;
    Long outsider;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        TestUsers.signInAsAdmin();
        area = areaService.create(new AreaRequest("EOD Area", null, true)).id();
        otherArea = areaService.create(new AreaRequest("EOD Other", null, true)).id();
        coachella = storeService.create(new StoreRequest(area, "Coachella", "CCH", null, null, true, null, null)).id();
        otherStore = storeService.create(new StoreRequest(otherArea, "Elsewhere", "ELS", null, null, true, null, null)).id();
        manager = person("EOD-M", "Mia", Role.MANAGER, area);
        hasnain = person("EOD-1", "Hasnain", Role.EMPLOYEE, area);
        oshama = person("EOD-2", "Oshama", Role.EMPLOYEE, area);
        outsider = person("EOD-3", "Olga", Role.EMPLOYEE, otherArea);
    }

    @AfterEach
    void tearDown() {
        TestUsers.signOut();
    }

    /** The Nov 1 Coachella sheet: $3,672.21 sale, $3,672.21 drop, $0 over/short. */
    @Test
    void figuresMatchTheDailySheet() {
        as(hasnain);
        eod.addLine(sale(EodCategory.NL_AAL, "SAM A16", "65.00", "23.09", "88.09", "9880"));
        eod.addLine(sale(EodCategory.NL_AAL, "BYOD", "40.00", "0", "40.00", "0719"));
        eod.addLine(sale(EodCategory.NL_AAL, "BYOD", "72.28", "15.10", "87.38", "1687"));
        eod.addLine(accessory("500.00"));
        as(oshama);
        eod.addLine(sale(EodCategory.BTS, "REVVL TAB", "0", "0", "0", "378"));
        eod.addLine(sale(EodCategory.UPG, "MOTO G PLAY", "26.00", "18.65", "44.65", "378"));
        ReportResponse r = eod.addLine(accessory("391.60"));

        ReportResponse closed = eod.submit(closing("2723.77", "3000.00", "672.21", null));
        var t = closed.totals();
        assertMoney("56.84", t.subtotal());
        assertMoney("891.60", t.accessories());
        assertMoney("948.44", t.grandTotal());
        assertMoney("3672.21", t.totalSale());
        assertMoney("3672.21", t.totalDrop());
        assertMoney("0.00", t.overShort());
        assertEquals(5, t.boxes());
        Map<String, Integer> counts = closed.categories().stream()
                .collect(Collectors.toMap(CategoryCount::label, CategoryCount::count));
        assertEquals(3, counts.get("NL/AAL"));
        assertEquals(1, counts.get("BTS"));
        assertEquals(1, counts.get("UPG"));
        assertEquals(EodStatus.SUBMITTED, closed.status());
        assertEquals("Oshama Tester", closed.submittedBy());
        assertEquals("0719", r.lines().get(1).customerRef(), "leading zeros are kept");

        var hasnainDay = closed.reps().stream().filter(x -> x.repId().equals(hasnain)).findFirst().orElseThrow();
        assertEquals(3, hasnainDay.boxes());
        assertMoney("500.00", hasnainDay.accessories());
    }

    @Test
    void repsLogOnlyTheirOwnSalesForTodayInTheirArea() {
        as(hasnain);
        ReportResponse r = eod.addLine(sale(EodCategory.UPG, "MOTO G", "26", "18.65", "44.65", "1"));
        Long hasnainsSale = r.lines().getFirst().id();

        // A rep can credit the sale to a colleague (e.g. logging it for them); the box goes to that one person.
        ReportResponse forOshama = eod.addLine(withRep(sale(EodCategory.UPG, "X", "0", "0", "0", null), oshama));
        assertEquals("Oshama Tester", forOshama.lines().getLast().repName());
        assertTrue(forOshama.lines().getLast().canEdit(), "whoever logged it can fix it");
        eod.deleteLine(forOshama.lines().getLast().id());
        assertThrows(BusinessRuleException.class, () -> eod.addLine(withRep(sale(EodCategory.UPG, "X", "0", "0", "0",
                null), outsider)), "not someone from another area");
        assertThrows(BusinessRuleException.class,
                () -> eod.addLine(new LineRequest(coachella, today.minusDays(1), EodLineType.ACCESSORY, null, null,
                        null, null, new BigDecimal("10"), null, null, null, null, null, null)), "today only");
        assertThrows(AccessDeniedException.class, () -> eod.addLine(new LineRequest(otherStore, today,
                EodLineType.ACCESSORY, null, null, null, null, new BigDecimal("10"), null, null, null, null, null, null)));

        as(oshama);
        assertThrows(AccessDeniedException.class, () -> eod.deleteLine(hasnainsSale), "not their sale");
        assertFalse(eod.find(coachella, today).lines().getFirst().canEdit());

        as(manager);
        ReportResponse fixed = eod.addLine(withRep(sale(EodCategory.BTS, "TAB", "0", "0", "0", null), oshama));
        assertEquals("Oshama Tester", fixed.lines().get(1).repName(), "managers can log for a rep");
        assertThrows(BusinessRuleException.class,
                () -> eod.addLine(withRep(sale(EodCategory.BTS, "TAB", "0", "0", "0", null), outsider)));
        assertTrue(eod.find(coachella, today).lines().getFirst().canEdit());
    }

    @Test
    void subtotalIsTypedByTheRepAndNoTaxIsAdded() {
        TestUsers.signInAsAdmin();
        storeService.update(coachella, new StoreRequest(area, "Coachella", "CCH", null, null, true, null, null,
                new BigDecimal("7.75")));
        as(hasnain);
        // Subtotal $24.88 as written on the receipt; $65 QPAY; customer paid $100 → $10.12 accessories.
        assertThrows(BusinessRuleException.class,
                () -> eod.addLine(sale(EodCategory.NL_AAL, "SAM A16", "65.00", "24.88", "89.00", null)),
                "sold for has to cover QPAY + subtotal");
        assertThrows(BusinessRuleException.class,
                () -> eod.addLine(sale(EodCategory.NL_AAL, "SAM A16", "65.00", null, "100.00", null)),
                "the subtotal has to be entered");
        ReportResponse r = eod.addLine(sale(EodCategory.NL_AAL, "SAM A16", "65.00", "24.88", "100.00", null));
        var line = r.lines().getFirst();
        assertMoney("24.88", line.subtotal(), "kept exactly as typed, no tax added");
        assertMoney("0.00", line.tax());
        assertMoney("10.12", line.accessories());
        assertMoney("24.88", r.totals().subtotal());
        assertMoney("35.00", r.totals().grandTotal(), "subtotal + accessories (QPAY is on the Qpay report)");
        assertMoney("7.75", line.taxRate(), "the store rate is still noted for taking tax off accessories");
    }

    @Test
    void salesAreChecked() {
        as(hasnain);
        assertThrows(BusinessRuleException.class, () -> eod.addLine(sale(null, "SAM A16", "65", "23", "88", null)),
                "category is required (commission depends on it)");
        assertThrows(BusinessRuleException.class, () -> eod.addLine(sale(EodCategory.NL_AAL, "SAM", "65", "23", "80",
                null)), "sold for below QPAY + subtotal");
        assertThrows(BusinessRuleException.class, () -> eod.addLine(sale(EodCategory.UNLOCK, "Unlock", "0", "20", "20",
                null)), "unlock needs IMEI and phone");
        ReportResponse ok = eod.addLine(new LineRequest(coachella, today, EodLineType.SALE, EodCategory.UNLOCK,
                "Unlock", null, new BigDecimal("20"), new BigDecimal("25"), null, "4821", "760-555-0101", null, null, null));
        assertMoney("5.00", ok.lines().getFirst().accessories(), "the extra $5 is accessories");
    }

    @Test
    void submitNeedsANoteWhenOverOrShortThenLocksUntilReopened() {
        as(hasnain);
        eod.addLine(accessory("100.00"));
        assertThrows(BusinessRuleException.class, () -> eod.submit(closing("0", "90.00", "0", null)),
                "$10 short needs a note");
        ReportResponse submitted = eod.submit(closing("0", "90.00", "0", "Survey phone taken out"));
        assertMoney("-10.00", submitted.totals().overShort());
        assertFalse(submitted.canAddSales());

        assertThrows(BusinessRuleException.class, () -> eod.addLine(accessory("5")), "locked after submit");
        assertThrows(AccessDeniedException.class, () -> eod.approve(submitted.id()), "reps can't approve");

        as(manager);
        assertTrue(eod.find(coachella, today).canApprove());
        ReportResponse approved = eod.approve(submitted.id());
        assertEquals(EodStatus.APPROVED, approved.status());
        assertEquals("Mia Tester", approved.approvedBy());
        ReportResponse reopened = eod.reopen(submitted.id());
        assertEquals(EodStatus.OPEN, reopened.status());
        assertNull(reopened.submittedBy(), "no longer shows as submitted");
        assertNull(reopened.approvedBy());
        assertEquals("Mia Tester", reopened.reopenedBy());
        assertMoney("90.00", reopened.cash(), "the money counted is kept to check");

        as(hasnain);
        assertNotNull(eod.addLine(accessory("5")).id(), "open again for the rep");
        // Clearing the money counted leaves the boxes empty, not $0.00.
        ReportResponse cleared = eod.saveClosing(new ClosingRequest(coachella, today, null, null, null, null, null,
                null, null));
        assertNull(cleared.cash());
        assertNull(cleared.qpayTotal());
        assertNull(cleared.note());
        assertThrows(BusinessRuleException.class, () -> eod.submit(new ClosingRequest(coachella, today, null, null,
                null, null, null, null, null)), "can't submit without the money counted");
        ReportResponse again = eod.submit(closing("0", "105.00", "0", null));
        assertNull(again.reopenedBy(), "cleared once it's submitted again");
        assertEquals(EodStatus.SUBMITTED, again.status());
    }

    @Test
    void managersSeeTheDayAndTheMonth() throws Exception {
        as(hasnain);
        eod.addLine(sale(EodCategory.NL_AAL, "BYOD", "40", "0", "40", "1"));
        eod.submit(closing("40.00", "40.00", "0", null));

        as(manager);
        var day = eod.day(null, today);
        assertEquals(1, day.submitted());
        assertEquals(EodStatus.SUBMITTED, day.stores().getFirst().status());

        var month = eod.month(null, YearMonth.from(today), null);
        assertEquals(1, month.reports());
        assertEquals(1, month.reps().getFirst().boxes());
        assertMoney("40.00", month.totals().totalSale());

        as(hasnain);
        assertThrows(AccessDeniedException.class, () -> eod.day(null, today));

        String managerToken = token(manager);
        mvc.perform(get("/api/eod/month").param("month", YearMonth.from(today).toString())
                .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reports").value(1));
        mvc.perform(get("/api/eod/day").param("date", today.toString())
                .header("Authorization", "Bearer " + token(hasnain)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/eod/today").header("Authorization", "Bearer " + token(hasnain)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.storeName").value("Coachella"));
    }

    @Test
    void aSharedSaleSplitsOnlyTheAccessoriesTheBoxStaysWithTheRep() {
        as(hasnain);
        LineRequest shared = withShares(sale(EodCategory.NL_AAL, "SAM A16", "65.00", "23.09", "100.00", "9880"),
                java.util.List.of(oshama));
        ReportResponse r = eod.addLine(shared);
        assertEquals(1, r.lines().getFirst().sharedWith().size());
        assertEquals("Oshama Tester", r.lines().getFirst().sharedWith().getFirst().name());
        // $11.91 of accessories split two ways: Hasnain (who logged it) gets the odd cent.
        eod.addLine(withShares(accessory("10.01"), java.util.List.of(oshama, manager)));

        var reps = eod.find(coachella, today).reps();
        var h = reps.stream().filter(x -> x.repId().equals(hasnain)).findFirst().orElseThrow();
        var o = reps.stream().filter(x -> x.repId().equals(oshama)).findFirst().orElseThrow();
        var m = reps.stream().filter(x -> x.repId().equals(manager)).findFirst().orElseThrow();
        assertEquals(1, h.boxes(), "the box belongs to the rep only");
        assertEquals(0, o.boxes());
        assertEquals(0, m.boxes());
        assertMoney("5.96", h.accessories().subtract(new BigDecimal("3.35")), "Hasnain: 5.96 + 3.35");
        assertMoney("5.95", o.accessories().subtract(new BigDecimal("3.33")), "Oshama: 5.95 + 3.33");
        assertMoney("3.33", m.accessories());
        assertMoney("21.92", h.accessories().add(o.accessories()).add(m.accessories()), "parts add up to the whole");
        assertEquals(2, h.sharedSales());

        assertEquals(1, eod.find(coachella, today).totals().boxes(), "the store still counts one box");

        // Not with someone outside the area, and the rep doesn't count twice.
        assertThrows(BusinessRuleException.class,
                () -> eod.addLine(withShares(accessory("5"), java.util.List.of(outsider))));
        ReportResponse self = eod.addLine(withShares(accessory("5"), java.util.List.of(hasnain)));
        assertTrue(self.lines().getLast().sharedWith().isEmpty());
    }

    @Test
    void picksTheDeviceFromTheDeviceList() {
        // admin keeps the list
        var brand = catalog.createBrand(new com.example.schedulingsoftware.catalog.CatalogDtos.BrandRequest("Samsung", true));
        var a16 = catalog.createDevice(new com.example.schedulingsoftware.catalog.CatalogDtos.DeviceRequest(brand.id(),
                "Galaxy A16", new BigDecimal("23.09"), true));

        as(hasnain);
        assertEquals(1, catalog.brands().size());
        LineRequest picked = new LineRequest(coachella, today, EodLineType.SALE, EodCategory.NL_AAL, null,
                new BigDecimal("65.00"), new BigDecimal("23.09"), new BigDecimal("88.09"), "9880", null, null, null, a16.id(), null);
        var line = eod.addLine(picked).lines().getFirst();
        assertEquals("Samsung Galaxy A16", line.description());
        assertMoney("23.09", line.retail(), "the device list's price is kept for reference");
        assertEquals(a16.id(), line.deviceId());

        // A hidden device can't be picked any more; reps don't see it.
        TestUsers.signInAsAdmin();
        catalog.updateDevice(a16.id(), new com.example.schedulingsoftware.catalog.CatalogDtos.DeviceRequest(brand.id(),
                "Galaxy A16", new BigDecimal("23.09"), false));
        as(hasnain);
        assertTrue(catalog.brands().getFirst().devices().isEmpty());
        assertThrows(BusinessRuleException.class, () -> eod.addLine(picked));
    }

    @Test
    void onlyAdminsChangeTheDeviceList() throws Exception {
        String body = "{\"name\":\"Motorola\",\"active\":true}";
        for (Long who : new Long[] {manager, hasnain}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/catalog/brands")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)
                    .header("Authorization", "Bearer " + token(who)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/catalog/brands").header("Authorization", "Bearer " + token(hasnain)))
                .andExpect(status().isOk());
    }

    @Test
    void theClosingCanSplitTheDaysAccessoriesBetweenTheTeam() {
        // Publish a schedule: Mia, Hasnain and Oshama all work at Coachella today.
        TestUsers.signInAsAdmin();
        var week = scheduleService.generate(area, today);
        scheduleService.publish(week.id());

        as(hasnain);
        ReportResponse r = eod.addLine(accessory("100.00"));
        eod.addLine(sale(EodCategory.UPG, "MOTO", "26.00", "18.65", "64.65", null)); // $20 accessories
        var team = eod.find(coachella, today).team();
        assertEquals(java.util.Set.of(manager, hasnain, oshama),
                team.stream().filter(t -> t.scheduled()).map(t -> t.id()).collect(Collectors.toSet()),
                "the team comes from today's schedule");
        assertTrue(eod.colleagues(coachella, today).getFirst().scheduledHere(), "people on shift are listed first");

        // $120 of accessories, three ways.
        assertThrows(BusinessRuleException.class, () -> eod.saveSplit(split(Map.of(hasnain, "60", oshama, "50"))),
                "has to add up to the total");
        ReportResponse split = eod.saveSplit(split(Map.of(hasnain, "40", oshama, "40", manager, "40")));
        assertEquals(3, split.accessorySplit().size());
        var credit = split.reps().stream().collect(Collectors.toMap(x -> x.repId(), x -> x));
        assertMoney("40.00", credit.get(oshama).accessories());
        assertMoney("40.00", credit.get(manager).accessories());
        assertEquals(1, credit.get(hasnain).boxes(), "boxes are never split");
        assertEquals(0, credit.get(oshama).boxes());

        // Another accessory sale changes the total: the old split no longer adds up, so it's dropped.
        ReportResponse after = eod.addLine(accessory("9.00"));
        assertTrue(after.accessorySplit().isEmpty());
        assertMoney("129.00", after.reps().stream().filter(x -> x.repId().equals(hasnain)).findFirst().orElseThrow()
                .accessories(), "back to whoever sold it");

        // Undo: an empty split.
        eod.saveSplit(split(Map.of(hasnain, "129.00")));
        assertTrue(eod.saveSplit(split(Map.of())).accessorySplit().isEmpty());
        assertNotNull(r.id());
    }

    // ---------- helpers ----------

    private EodDtos.SplitRequest split(Map<Long, String> amounts) {
        return new EodDtos.SplitRequest(coachella, today, amounts.entrySet().stream()
                .map(e -> new EodDtos.SplitShare(e.getKey(), new BigDecimal(e.getValue()))).toList());
    }

    private static LineRequest withShares(LineRequest r, java.util.List<Long> shareWith) {
        return new LineRequest(r.storeId(), r.date(), r.type(), r.category(), r.description(), r.qpay(), r.subtotal(),
                r.soldFor(), r.customerRef(), r.imeiLast4(), r.phone(), r.repId(), r.deviceId(), shareWith);
    }


    private LineRequest sale(EodCategory category, String description, String qpay, String retail, String soldFor,
            String customer) {
        return new LineRequest(coachella, today, EodLineType.SALE, category, description, new BigDecimal(qpay),
                retail == null ? null : new BigDecimal(retail), new BigDecimal(soldFor), customer, null, null, null, null,
                null);
    }

    private LineRequest accessory(String amount) {
        return new LineRequest(coachella, today, EodLineType.ACCESSORY, null, null, null, null, new BigDecimal(amount),
                null, null, null, null, null, null);
    }

    private static LineRequest withRep(LineRequest r, Long repId) {
        return new LineRequest(r.storeId(), r.date(), r.type(), r.category(), r.description(), r.qpay(), r.subtotal(),
                r.soldFor(), r.customerRef(), r.imeiLast4(), r.phone(), repId, r.deviceId(), r.shareWith());
    }

    private ClosingRequest closing(String qpay, String cash, String credit, String note) {
        return new ClosingRequest(coachella, today, new BigDecimal(qpay), new BigDecimal(cash), new BigDecimal(credit),
                null, null, null, note);
    }

    private void as(Long id) {
        Employee e = employeeRepository.findById(id).orElseThrow();
        TestUsers.signInAs(id, e.getRole(), e.getArea() == null ? null : e.getArea().getId());
    }

    private Long person(String code, String first, Role role, Long areaId) {
        Long id = employeeService.create(new EmployeeRequest(code, first, "Tester", code.toLowerCase() + "@test.example",
                null, role, areaId, areaId.equals(area) ? coachella : otherStore, 6, true, null)).employee().id();
        Employee e = employeeRepository.findById(id).orElseThrow();
        e.setMustChangePassword(false);
        employeeRepository.saveAndFlush(e);
        return id;
    }

    private String token(Long id) {
        return jwtService.issue(id, employeeRepository.findById(id).orElseThrow().getTokenVersion()).token();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + ": expected " + expected + " was " + actual);
    }
}
