package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.catalog.CatalogService;
import com.example.schedulingsoftware.catalog.Device;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.eod.EodDtos.CategoryCount;
import com.example.schedulingsoftware.eod.EodDtos.CategoryOption;
import com.example.schedulingsoftware.eod.EodDtos.ClosingRequest;
import com.example.schedulingsoftware.eod.EodDtos.Colleague;
import com.example.schedulingsoftware.eod.EodDtos.SplitRequest;
import com.example.schedulingsoftware.eod.EodDtos.SplitShare;
import com.example.schedulingsoftware.eod.EodDtos.SplitShareView;
import com.example.schedulingsoftware.eod.EodDtos.TeamMember;
import com.example.schedulingsoftware.eod.EodDtos.DayOverview;
import com.example.schedulingsoftware.eod.EodDtos.LineRequest;
import com.example.schedulingsoftware.eod.EodDtos.LineResponse;
import com.example.schedulingsoftware.eod.EodDtos.MonthSummary;
import com.example.schedulingsoftware.eod.EodDtos.Person;
import com.example.schedulingsoftware.eod.EodDtos.RepSummary;
import com.example.schedulingsoftware.eod.EodDtos.ReportResponse;
import com.example.schedulingsoftware.eod.EodDtos.StoreDay;
import com.example.schedulingsoftware.eod.EodDtos.StoreOption;
import com.example.schedulingsoftware.eod.EodDtos.TodayResponse;
import com.example.schedulingsoftware.eod.EodDtos.Totals;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleEntry;
import com.example.schedulingsoftware.schedule.ScheduleEntryRepository;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreRepository;
import com.example.schedulingsoftware.store.StoreService;

import lombok.RequiredArgsConstructor;

/**
 * End of day (EOD): replaces the store's daily sheet. Reps log their own sales during the day; whoever closes enters
 * the money counted and submits; a manager approves or reopens it.
 *
 * <p>Who can do what:
 * <ul>
 * <li>Employees: only stores in their area, only today, only while the report is open; they log sales as themselves and
 * can change only their own.</li>
 * <li>Managers: any store in their area, any past day; can log or fix a sale for any rep in the area, approve and
 * reopen.</li>
 * <li>Admins: everything.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class EodService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final EodReportRepository reportRepository;
    private final EodLineRepository lineRepository;
    private final StoreService storeService;
    private final StoreRepository storeRepository;
    private final EmployeeRepository employeeRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;
    private final AccessPolicy accessPolicy;
    private final CatalogService catalogService;

    public List<CategoryOption> categories() {
        return Arrays.stream(EodCategory.values())
                .map(c -> new CategoryOption(c, c.getLabel(), c.getHelp(), c.needsDevice()))
                .toList();
    }

    /**
     * Who a sale can be credited to or split with: active staff of the store's area, with the people on the schedule
     * at this store that day first.
     */
    @Transactional(readOnly = true)
    public List<Colleague> colleagues(Long storeId, LocalDate date) {
        Store store = readableStore(storeId);
        Set<Long> scheduled = scheduledAt(store.getId(), date == null ? LocalDate.now() : date);
        return employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(
                        store.getArea().getId(), java.util.EnumSet.of(Role.MANAGER, Role.EMPLOYEE)).stream()
                .map(e -> new Colleague(e.getId(), e.getFullName(), scheduled.contains(e.getId())))
                .sorted(Comparator.comparing((Colleague c) -> !c.scheduledHere()).thenComparing(Colleague::name))
                .toList();
    }

    /** People working at the store that day on the published schedule. */
    private Set<Long> scheduledAt(Long storeId, LocalDate date) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        scheduleEntryRepository.findWorkingAtStore(storeId, date).forEach(e -> ids.add(e.getEmployee().getId()));
        return ids;
    }

    // ---------- Reading ----------

    /** The store the signed-in person works at today (published schedule, else their home store), and its EOD. */
    @Transactional(readOnly = true)
    public TodayResponse today() {
        CurrentUser me = accessPolicy.current();
        LocalDate today = LocalDate.now();
        List<StoreOption> stores = me.areaId() == null ? List.of()
                : storeRepository.findByAreaIdOrderByNameAsc(me.areaId()).stream()
                        .filter(Store::isActive)
                        .map(s -> new StoreOption(s.getId(), s.getName(), s.getLabel()))
                        .toList();
        Store store = null;
        String reason = null;
        Optional<ScheduleEntry> scheduled = scheduleEntryRepository.findPublishedDay(me.id(), today).stream()
                .findFirst();
        if (scheduled.isPresent() && scheduled.get().getType() == DayType.WORK && scheduled.get().getStore() != null) {
            store = scheduled.get().getStore();
            reason = "You're on the schedule at " + store.getName() + " today.";
        } else if (scheduled.isPresent() && scheduled.get().getType() != DayType.WORK) {
            reason = "You're off today on the schedule.";
        }
        if (store == null) {
            Employee self = employeeRepository.findById(me.id()).orElse(null);
            if (self != null && self.getHomeStore() != null && self.getHomeStore().isActive()) {
                store = self.getHomeStore();
                if (reason == null) {
                    reason = "Your home store.";
                }
            }
        }
        if (store == null && !stores.isEmpty()) {
            store = storeRepository.findById(stores.getFirst().id()).orElse(null);
        }
        ReportResponse report = store == null ? null : view(store, today);
        return new TodayResponse(today, store == null ? null : store.getId(), store == null ? null : store.getName(),
                reason, stores, report);
    }

    @Transactional(readOnly = true)
    public ReportResponse find(Long storeId, LocalDate date) {
        Store store = readableStore(storeId);
        requireViewableDate(date);
        return view(store, date);
    }

    /** Every active store in an area on one day: submitted, still open, or nothing logged yet. */
    @Transactional(readOnly = true)
    public DayOverview day(Long areaId, LocalDate date) {
        Long area = requireManagedArea(areaId);
        List<StoreDay> stores = new ArrayList<>();
        Map<Long, EodReport> byStore = new LinkedHashMap<>();
        reportRepository.findByStoreAreaIdAndDateBetweenOrderByDateAscStoreNameAsc(area, date, date)
                .forEach(r -> byStore.put(r.getStore().getId(), r));
        for (Store s : storeRepository.findByAreaIdOrderByNameAsc(area)) {
            EodReport r = byStore.get(s.getId());
            if (!s.isActive() && r == null) {
                continue;
            }
            stores.add(storeDay(s, date, r));
        }
        int submitted = (int) stores.stream()
                .filter(d -> d.status() == EodStatus.SUBMITTED || d.status() == EodStatus.APPROVED).count();
        return new DayOverview(date, stores, submitted, stores.size() - submitted);
    }

    /** A month of reports for an area (optionally one store), with totals and each rep's counts for commission. */
    @Transactional(readOnly = true)
    public MonthSummary month(Long areaId, YearMonth month, Long storeId) {
        Long area = requireManagedArea(areaId);
        List<EodReport> reports = reportRepository.findByStoreAreaIdAndDateBetweenOrderByDateAscStoreNameAsc(area,
                month.atDay(1), month.atEndOfMonth()).stream()
                .filter(r -> storeId == null || r.getStore().getId().equals(storeId))
                .toList();
        List<StoreDay> days = reports.stream().map(r -> storeDay(r.getStore(), r.getDate(), r)).toList();
        List<EodLine> lines = reports.stream().flatMap(r -> r.getLines().stream()).toList();
        Totals all = sum(reports);
        // Over/short only means something once a day is closed out; open days would show as fully short.
        BigDecimal closedOverShort = sum(reports.stream().filter(r -> r.getStatus() != EodStatus.OPEN).toList())
                .overShort();
        Totals totals = new Totals(all.subtotal(), all.accessories(), all.grandTotal(), all.lineQpay(), all.qpayTotal(),
                all.newLayaway(), all.totalSale(), all.cash(), all.credit(), all.finance(), all.layawayRedeem(),
                all.totalDrop(), closedOverShort, all.boxes());
        return new MonthSummary(month.format(DateTimeFormatter.ofPattern("yyyy-MM", Locale.US)), area, days, totals,
                categoryCounts(lines), repSummaries(reports), reports.size(),
                (int) reports.stream().filter(r -> r.getStatus() == EodStatus.APPROVED).count());
    }

    // ---------- Sales ----------

    @Transactional
    public ReportResponse addLine(LineRequest request) {
        Store store = readableStore(request.storeId());
        EodReport report = openReport(store, request.date());
        EodLine line = new EodLine();
        line.setReport(report);
        line.setCreatedBy(self());
        apply(line, request, store);
        report.getLines().add(line);
        clearStaleSplit(report);
        report.setUpdatedAt(Instant.now());
        reportRepository.saveAndFlush(report);
        return toResponse(report);
    }

    @Transactional
    public ReportResponse updateLine(Long lineId, LineRequest request) {
        EodLine line = editableLine(lineId);
        EodReport report = line.getReport();
        if (!report.getStore().getId().equals(request.storeId()) || !report.getDate().equals(request.date())) {
            throw new BusinessRuleException("A sale can't be moved to another store or day. Remove it and log it there.");
        }
        apply(line, request, report.getStore());
        clearStaleSplit(report);
        report.setUpdatedAt(Instant.now());
        return toResponse(report);
    }

    @Transactional
    public ReportResponse deleteLine(Long lineId) {
        EodLine line = editableLine(lineId);
        EodReport report = line.getReport();
        report.getLines().remove(line);
        clearStaleSplit(report);
        report.setUpdatedAt(Instant.now());
        return toResponse(report);
    }

    // ---------- Closing ----------

    /** Saves the money counted at close (can be saved several times before submitting). */
    @Transactional
    public ReportResponse saveClosing(ClosingRequest request) {
        Store store = readableStore(request.storeId());
        EodReport report = openReport(store, request.date());
        // A box left empty stays empty (not $0.00), so it doesn't come back filled in.
        report.setQpayTotal(moneyOrNull(request.qpayTotal()));
        report.setCash(moneyOrNull(request.cash()));
        report.setCredit(moneyOrNull(request.credit()));
        report.setFinance(moneyOrNull(request.finance()));
        report.setLayawayRedeem(moneyOrNull(request.layawayRedeem()));
        report.setNewLayaway(moneyOrNull(request.newLayaway()));
        report.setNote(cleanNote(request.note()));
        report.setUpdatedAt(Instant.now());
        reportRepository.saveAndFlush(report);
        return toResponse(report);
    }

    /**
     * Splits the day's accessories between people by hand (e.g. evenly between everyone who worked). The parts must
     * add up to the day's accessory total; an empty list goes back to "whoever sold it". Boxes are never split.
     */
    @Transactional
    public ReportResponse saveSplit(SplitRequest request) {
        Store store = readableStore(request.storeId());
        EodReport report = openReport(store, request.date());
        // Check everything first, then replace the old split.
        Map<Employee, BigDecimal> parts = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        BigDecimal sum = ZERO;
        for (SplitShare share : request.shares()) {
            if (!seen.add(share.employeeId())) {
                throw new BusinessRuleException("Each person can appear only once in the split.");
            }
            BigDecimal amount = money(share.amount());
            if (amount.signum() > 0) {
                parts.put(areaStaff(share.employeeId(), store, "Accessories can only be split with staff in "), amount);
                sum = sum.add(amount);
            }
        }
        BigDecimal total = totals(report).accessories();
        if (!request.shares().isEmpty() && sum.compareTo(total) != 0) {
            throw new BusinessRuleException("The split adds up to $" + sum + " but the day's accessories are $" + total
                    + ". Make the parts add up to the total.");
        }
        report.getAccessorySplit().clear();
        reportRepository.flush();
        parts.forEach((e, amount) -> report.getAccessorySplit().add(new EodAccessoryShare(report, e, amount)));
        report.setUpdatedAt(Instant.now());
        reportRepository.saveAndFlush(report);
        return toResponse(report);
    }

    /** A split made before the accessories changed no longer adds up, so it goes back to "whoever sold it". */
    private static void clearStaleSplit(EodReport report) {
        if (report.getAccessorySplit().isEmpty()) {
            return;
        }
        BigDecimal sum = report.getAccessorySplit().stream().map(EodAccessoryShare::getAmount)
                .reduce(ZERO, BigDecimal::add);
        if (sum.compareTo(totals(report).accessories()) != 0) {
            report.getAccessorySplit().clear();
        }
    }

    /**
     * Closes out the day. The closing figures must be entered, and if the drop doesn't match the sale (over or short),
     * a note must say why. After this, reps can't change it; a manager approves or reopens it.
     */
    @Transactional
    public ReportResponse submit(ClosingRequest request) {
        ReportResponse saved = saveClosing(request);
        EodReport report = reportRepository.findById(saved.id()).orElseThrow();
        if (report.getCash() == null && report.getCredit() == null && report.getQpayTotal() == null) {
            throw new BusinessRuleException("Enter the closing figures (Qpay report, cash and card) before submitting.");
        }
        Totals t = totals(report);
        if (t.overShort().signum() != 0 && (report.getNote() == null || report.getNote().isBlank())) {
            throw new BusinessRuleException("The drop is " + (t.overShort().signum() > 0 ? "over" : "short") + " by $"
                    + t.overShort().abs() + ". Add a note saying why before submitting.");
        }
        report.setStatus(EodStatus.SUBMITTED);
        report.setSubmittedBy(self());
        report.setSubmittedAt(Instant.now());
        report.setReopenedBy(null);
        report.setReopenedAt(null);
        return toResponse(report);
    }

    @Transactional
    public ReportResponse approve(Long reportId) {
        EodReport report = managedReport(reportId);
        if (report.getStatus() != EodStatus.SUBMITTED) {
            throw new BusinessRuleException(report.getStatus() == EodStatus.APPROVED ? "This EOD is already approved."
                    : "This EOD hasn't been submitted yet.");
        }
        report.setStatus(EodStatus.APPROVED);
        report.setApprovedBy(self());
        report.setApprovedAt(Instant.now());
        return toResponse(report);
    }

    /** Opens a submitted or approved EOD again so it can be corrected. */
    @Transactional
    public ReportResponse reopen(Long reportId) {
        EodReport report = managedReport(reportId);
        if (report.getStatus() == EodStatus.OPEN) {
            throw new BusinessRuleException("This EOD is already open.");
        }
        report.setStatus(EodStatus.OPEN);
        report.setSubmittedBy(null);
        report.setSubmittedAt(null);
        report.setApprovedBy(null);
        report.setApprovedAt(null);
        report.setReopenedBy(self());
        report.setReopenedAt(Instant.now());
        return toResponse(report);
    }

    // ---------- Rules ----------

    private void apply(EodLine line, LineRequest r, Store store) {
        CurrentUser me = accessPolicy.current();
        line.setType(r.type());
        BigDecimal soldFor = money(r.soldFor());
        if (r.type() == EodLineType.ACCESSORY) {
            if (soldFor.signum() <= 0) {
                throw new BusinessRuleException("Enter the accessory amount.");
            }
            line.setCategory(null);
            line.setQpay(ZERO);
            line.setRetail(ZERO);
            line.setTax(ZERO);
            line.setSubtotal(ZERO);
            line.setTaxRate(rateOf(store));
            line.setDescription(blankTo(r.description(), "All accessories"));
            line.setCustomerRef(blankTo(r.customerRef(), "Walk in"));
            line.setImeiLast4(null);
            line.setPhone(null);
            line.setDeviceId(null);
        } else {
            if (r.category() == null) {
                throw new BusinessRuleException("Pick the category (NL/AAL, UPG, BTS…). Commission is counted by it.");
            }
            Device device = r.deviceId() == null ? null : catalogService.sellable(r.deviceId());
            String description = device != null ? device.getDisplayName() : InputRules.clean(r.description());
            if (description == null || description.isBlank()) {
                throw new BusinessRuleException("Pick the device from the list, or enter what was sold (e.g. BYOD).");
            }
            if (r.subtotal() == null) {
                throw new BusinessRuleException("Enter the subtotal from the receipt (0 if there isn't one).");
            }
            BigDecimal qpay = money(r.qpay());
            BigDecimal subtotal = money(r.subtotal());
            BigDecimal minimum = qpay.add(subtotal);
            if (soldFor.compareTo(minimum) < 0) {
                throw new BusinessRuleException("Sold for ($" + soldFor + ") can't be less than QPAY + subtotal ($"
                        + minimum + ").");
            }
            line.setSubtotal(subtotal);
            line.setTax(ZERO);
            line.setTaxRate(rateOf(store));
            String imei = blankToNull(r.imeiLast4());
            String phone = blankToNull(r.phone());
            if (r.category().needsDevice() && (imei == null || phone == null)) {
                throw new BusinessRuleException(r.category().getLabel()
                        + " needs the last 4 of the IMEI and the phone number.");
            }
            line.setCategory(r.category());
            line.setDescription(description);
            line.setQpay(qpay);
            line.setRetail(device == null ? ZERO : money(device.getRetailPrice()));
            line.setCustomerRef(blankToNull(r.customerRef()));
            line.setImeiLast4(imei);
            line.setPhone(phone);
            line.setDeviceId(device == null ? null : device.getId());
        }
        line.setSoldFor(soldFor);
        line.setRep(rep(r.repId(), me, store));
        line.getSharedWith().clear();
        line.getSharedWith().addAll(sharers(r.shareWith(), line.getRep(), store));
    }

    /** Colleagues sharing a sale: active staff of the store's area, not the rep, no repeats. */
    private List<Employee> sharers(List<Long> ids, Employee rep, Store store) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        List<Employee> result = new ArrayList<>();
        for (Long id : ids) {
            if (id == null || id.equals(rep.getId()) || !seen.add(id)) {
                continue;
            }
            Employee e = areaStaff(id, store, "A sale's accessories can only be split with staff in ");
            result.add(e);
        }
        if (result.size() > 3) {
            throw new BusinessRuleException("A sale can be shared with up to 3 people.");
        }
        return result;
    }

    /**
     * The person who gets the box: one person, picked from the store's area (people scheduled there that day are listed
     * first in the app). Left empty, it's whoever is logging the sale.
     */
    private Employee rep(Long repId, CurrentUser me, Store store) {
        if (repId == null || repId.equals(me.id())) {
            if (me.isAdmin()) {
                throw new BusinessRuleException("Pick the rep who made the sale.");
            }
            return self();
        }
        return areaStaff(repId, store, "The rep has to work in ");
    }

    /** An active manager or employee of the store's area. */
    private Employee areaStaff(Long id, Store store, String messageStart) {
        Employee e = employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
        if (!e.isActive() || e.getRole() == Role.ADMIN || e.getArea() == null
                || !e.getArea().getId().equals(store.getArea().getId())) {
            throw new BusinessRuleException(messageStart + store.getArea().getName() + ". " + e.getFullName()
                    + " doesn't.");
        }
        return e;
    }

    /** The day's report, created on first use; must still be open. */
    private EodReport openReport(Store store, LocalDate date) {
        requireWritableDate(date);
        if (!store.isActive()) {
            throw new BusinessRuleException(store.getName() + " is inactive.");
        }
        EodReport report = reportRepository.findByStoreIdAndDate(store.getId(), date)
                .orElseGet(() -> reportRepository.save(new EodReport(store, date)));
        requireOpen(report);
        return report;
    }

    private EodLine editableLine(Long lineId) {
        EodLine line = lineRepository.findById(lineId).orElseThrow(() -> new ResourceNotFoundException("Sale", lineId));
        EodReport report = line.getReport();
        readableStore(report.getStore().getId());
        requireWritableDate(report.getDate());
        requireOpen(report);
        CurrentUser me = accessPolicy.current();
        if (me.isEmployee() && !line.getRep().getId().equals(me.id()) && !line.getCreatedBy().getId().equals(me.id())) {
            throw new AccessDeniedException("You can only change your own sales.");
        }
        return line;
    }

    private static void requireOpen(EodReport report) {
        if (report.getStatus() != EodStatus.OPEN) {
            throw new BusinessRuleException("This EOD was submitted"
                    + (report.getStatus() == EodStatus.APPROVED ? " and approved" : "")
                    + ". A manager has to reopen it before anything changes.");
        }
    }

    /** Employees work on today only; managers and admins can fix any day up to today. */
    private void requireWritableDate(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw new BusinessRuleException("You can't log an EOD for a future day.");
        }
        if (accessPolicy.current().isEmployee() && !date.equals(today)) {
            throw new BusinessRuleException("You can only log sales for today. Ask your manager to fix an earlier day.");
        }
        if (date.isBefore(today.minusYears(1))) {
            throw new BusinessRuleException("That day is more than a year ago.");
        }
    }

    private void requireViewableDate(LocalDate date) {
        CurrentUser me = accessPolicy.current();
        if (me.isEmployee() && !date.equals(LocalDate.now())) {
            throw new AccessDeniedException("Employees see today's EOD only.");
        }
    }

    /** A store the user may see: admins any, others only in their own area. */
    private Store readableStore(Long storeId) {
        Store store = storeService.getStore(storeId);
        accessPolicy.requireArea(store.getArea().getId());
        return store;
    }

    private Long requireManagedArea(Long areaId) {
        CurrentUser me = accessPolicy.current();
        if (me.isEmployee()) {
            throw new AccessDeniedException("Only managers and admins see the EOD overview.");
        }
        Long area = accessPolicy.restrictToArea(areaId);
        if (area == null) {
            throw new BusinessRuleException("Pick an area.");
        }
        return area;
    }

    private EodReport managedReport(Long reportId) {
        EodReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("EOD", reportId));
        accessPolicy.requireManagerOf(report.getStore().getArea().getId());
        return report;
    }

    private Employee self() {
        Long id = accessPolicy.current().id();
        return employeeRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Employee", id));
    }

    // ---------- Figures ----------

    private static BigDecimal moneyOrNull(BigDecimal value) {
        return value == null ? null : money(value);
    }

    static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static BigDecimal rateOf(Store store) {
        return store.getTaxRate() == null ? BigDecimal.ZERO : store.getTaxRate();
    }

    /** The sheet's formulas. */
    static Totals totals(EodReport r) {
        BigDecimal subtotal = ZERO;
        BigDecimal accessories = ZERO;
        BigDecimal lineQpay = ZERO;
        int boxes = 0;
        for (EodLine l : r.getLines()) {
            accessories = accessories.add(l.getAccessories());
            if (l.getType() == EodLineType.SALE) {
                subtotal = subtotal.add(l.getSubtotal());
                lineQpay = lineQpay.add(l.getQpay());
                boxes++;
            }
        }
        BigDecimal grand = subtotal.add(accessories);
        BigDecimal sale = grand.add(orZero(r.getQpayTotal())).add(orZero(r.getNewLayaway()));
        BigDecimal drop = orZero(r.getCash()).add(orZero(r.getCredit())).add(orZero(r.getFinance()))
                .add(orZero(r.getLayawayRedeem()));
        return new Totals(subtotal, accessories, grand, lineQpay, orZero(r.getQpayTotal()), orZero(r.getNewLayaway()),
                sale, orZero(r.getCash()), orZero(r.getCredit()), orZero(r.getFinance()), orZero(r.getLayawayRedeem()),
                drop, drop.subtract(sale), boxes);
    }

    private static Totals sum(Collection<EodReport> reports) {
        BigDecimal[] t = new BigDecimal[13];
        Arrays.fill(t, ZERO);
        int boxes = 0;
        for (EodReport r : reports) {
            Totals x = totals(r);
            BigDecimal[] v = {x.subtotal(), x.accessories(), x.grandTotal(), x.lineQpay(), x.qpayTotal(), x.newLayaway(),
                x.totalSale(), x.cash(), x.credit(), x.finance(), x.layawayRedeem(), x.totalDrop(), x.overShort()};
            for (int i = 0; i < v.length; i++) {
                t[i] = t[i].add(v[i]);
            }
            boxes += x.boxes();
        }
        return new Totals(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7], t[8], t[9], t[10], t[11], t[12], boxes);
    }

    private static List<CategoryCount> categoryCounts(Collection<EodLine> lines) {
        Map<EodCategory, Integer> counts = new EnumMap<>(EodCategory.class);
        for (EodCategory c : EodCategory.values()) {
            counts.put(c, 0);
        }
        lines.stream().filter(l -> l.getCategory() != null).forEach(l -> counts.merge(l.getCategory(), 1, Integer::sum));
        return counts.entrySet().stream()
                .map(e -> new CategoryCount(e.getKey(), e.getKey().getLabel(), e.getValue()))
                .toList();
    }

    /** Each person's credit over the given reports, from the credit ledger ({@link EodCredits}). */
    private static List<RepSummary> repSummaries(Collection<EodReport> reports) {
        Map<Long, String> names = new LinkedHashMap<>();
        Map<Long, Map<EodCategory, Integer>> boxes = new HashMap<>();
        Map<Long, BigDecimal> acc = new HashMap<>();
        Map<Long, BigDecimal> sold = new HashMap<>();
        Map<Long, Integer> shared = new HashMap<>();
        for (EodReport r : reports) {
            for (EodCredits.Credit c : EodCredits.of(r)) {
                names.putIfAbsent(c.employeeId(), c.employeeName());
                if (c.boxes() > 0) {
                    boxes.computeIfAbsent(c.employeeId(), k -> new EnumMap<>(EodCategory.class))
                            .merge(c.category(), c.boxes(), Integer::sum);
                }
                acc.merge(c.employeeId(), c.accessories(), BigDecimal::add);
                sold.merge(c.employeeId(), c.soldFor(), BigDecimal::add);
                if (c.shared() && c.lineId() != null) {
                    shared.merge(c.employeeId(), 1, Integer::sum);
                }
            }
        }
        return names.entrySet().stream()
                .map(e -> {
                    Long id = e.getKey();
                    Map<EodCategory, Integer> mine = boxes.getOrDefault(id, Map.of());
                    List<CategoryCount> byCategory = Arrays.stream(EodCategory.values())
                            .filter(mine::containsKey)
                            .map(c -> new CategoryCount(c, c.getLabel(), mine.get(c)))
                            .toList();
                    int total = mine.values().stream().mapToInt(Integer::intValue).sum();
                    return new RepSummary(id, e.getValue(), total, byCategory, acc.getOrDefault(id, ZERO),
                            sold.getOrDefault(id, ZERO), shared.getOrDefault(id, 0));
                })
                .sorted(Comparator.comparing(RepSummary::boxes).reversed()
                        .thenComparing(RepSummary::accessories, Comparator.reverseOrder())
                        .thenComparing(RepSummary::repName))
                .toList();
    }

    private StoreDay storeDay(Store s, LocalDate date, EodReport r) {
        if (r == null) {
            return new StoreDay(date, s.getId(), s.getName(), s.getLabel(), null, null, 0, ZERO, ZERO, ZERO, null, null);
        }
        Totals t = totals(r);
        return new StoreDay(date, s.getId(), s.getName(), s.getLabel(), r.getId(), r.getStatus(), t.boxes()
                + (int) r.getLines().stream().filter(l -> l.getType() == EodLineType.ACCESSORY).count(),
                t.totalSale(), t.totalDrop(), t.overShort(),
                r.getSubmittedBy() == null ? null : r.getSubmittedBy().getFullName(), r.getSubmittedAt());
    }

    /** A store's day as the signed-in user sees it; an empty (unsaved) report when nothing was logged yet. */
    private ReportResponse view(Store store, LocalDate date) {
        return toResponse(reportRepository.findByStoreIdAndDate(store.getId(), date)
                .orElseGet(() -> new EodReport(store, date)));
    }

    private ReportResponse toResponse(EodReport r) {
        CurrentUser me = accessPolicy.current();
        List<RepSummary> reps = repSummaries(List.of(r));
        boolean managerHere = me.isAdmin()
                || (me.isManager() && me.areaId() != null && me.areaId().equals(r.getStore().getArea().getId()));
        LocalDate today = LocalDate.now();
        boolean dateOk = !r.getDate().isAfter(today) && (managerHere || r.getDate().equals(today));
        boolean open = r.getStatus() == EodStatus.OPEN && r.getStore().isActive() && dateOk;
        List<LineResponse> lines = r.getLines().stream()
                .map(l -> new LineResponse(l.getId(), l.getType(), l.getCategory(),
                        l.getCategory() == null ? "Accessories" : l.getCategory().getLabel(), l.getDescription(),
                        l.getQpay(), l.getRetail(), l.getTax(), l.getTaxRate(), l.getSubtotal(), l.getSoldFor(),
                        l.getAccessories(), l.getCustomerRef(),
                        l.getImeiLast4(), l.getPhone(), l.getRep().getId(), l.getRep().getFullName(), l.getDeviceId(),
                        l.getSharedWith().stream().map(x -> new Person(x.getId(), x.getFullName())).toList(),
                        l.getCreatedAt(),
                        open && (managerHere || l.getRep().getId().equals(me.id())
                                || l.getCreatedBy().getId().equals(me.id()))))
                .toList();
        return new ReportResponse(r.getId(), r.getStore().getId(), r.getStore().getName(),
                r.getStore().getArea().getId(), r.getDate(), r.getStatus(), r.getQpayTotal(), r.getCash(),
                r.getCredit(), r.getFinance(), r.getLayawayRedeem(), r.getNewLayaway(), r.getNote(),
                r.getSubmittedBy() == null ? null : r.getSubmittedBy().getFullName(), r.getSubmittedAt(),
                r.getApprovedBy() == null ? null : r.getApprovedBy().getFullName(), r.getApprovedAt(),
                r.getReopenedBy() == null ? null : r.getReopenedBy().getFullName(), r.getReopenedAt(),
                lines, totals(r), categoryCounts(r.getLines()), reps, team(r, reps),
                r.getAccessorySplit().stream().map(x -> new SplitShareView(x.getEmployee().getId(),
                        x.getEmployee().getFullName(), x.getAmount())).toList(),
                open, open,
                managerHere && r.getStatus() == EodStatus.SUBMITTED,
                managerHere && r.getStatus() != EodStatus.OPEN && r.getId() != null, me.id());
    }

    /** The day's team: everyone scheduled at the store, then anyone else with credit there, with their credit. */
    private List<TeamMember> team(EodReport r, List<RepSummary> reps) {
        Map<Long, RepSummary> credit = new HashMap<>();
        reps.forEach(x -> credit.put(x.repId(), x));
        List<TeamMember> team = new ArrayList<>();
        Set<Long> added = new HashSet<>();
        for (ScheduleEntry e : scheduleEntryRepository.findWorkingAtStore(r.getStore().getId(), r.getDate())) {
            Employee p = e.getEmployee();
            if (added.add(p.getId())) {
                RepSummary c = credit.get(p.getId());
                team.add(new TeamMember(p.getId(), p.getFullName(), true, c == null ? 0 : c.boxes(),
                        c == null ? ZERO : c.accessories()));
            }
        }
        team.sort(Comparator.comparing(TeamMember::name));
        for (RepSummary c : reps) {
            if (added.add(c.repId())) {
                team.add(new TeamMember(c.repId(), c.repName(), false, c.boxes(), c.accessories()));
            }
        }
        return team;
    }

    private static String cleanNote(String note) {
        String n = InputRules.clean(note);
        return n == null || n.isBlank() ? null : n;
    }

    private static String blankToNull(String text) {
        String t = InputRules.clean(text);
        return t == null || t.isBlank() ? null : t;
    }

    private static String blankTo(String text, String fallback) {
        String t = blankToNull(text);
        return t == null ? fallback : t;
    }
}
