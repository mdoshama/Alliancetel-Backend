package com.example.schedulingsoftware.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.commission.CommissionCalculator.Breakdown;
import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentRequest;
import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentResponse;
import com.example.schedulingsoftware.commission.CommissionDtos.AdjustmentTypeOption;
import com.example.schedulingsoftware.commission.CommissionDtos.MonthResponse;
import com.example.schedulingsoftware.commission.CommissionDtos.MonthTotals;
import com.example.schedulingsoftware.commission.CommissionDtos.PlanDto;
import com.example.schedulingsoftware.commission.CommissionDtos.Statement;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.eod.EodCategory;
import com.example.schedulingsoftware.eod.EodCredits;
import com.example.schedulingsoftware.eod.EodCredits.Credit;
import com.example.schedulingsoftware.eod.EodReport;
import com.example.schedulingsoftware.eod.EodReportRepository;
import com.example.schedulingsoftware.eod.EodStatus;

import lombok.RequiredArgsConstructor;

/**
 * Commission (admins only). Each person's month comes from the EOD credit ledger, counting only approved EODs, plus
 * fines and rewards; the plan turns it into pay. A finalized month keeps its payouts until reopened.
 */
@Service
@RequiredArgsConstructor
public class CommissionService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final CommissionPlanRepository planRepository;
    private final CommissionAdjustmentRepository adjustmentRepository;
    private final CommissionPeriodRepository periodRepository;
    private final EodReportRepository reportRepository;
    private final EmployeeRepository employeeRepository;
    private final com.example.schedulingsoftware.store.StoreRepository storeRepository;
    private final AccessPolicy accessPolicy;

    // ---------- Plan ----------

    @Transactional
    public PlanDto plan() {
        requireAdmin();
        return toDto(currentPlan());
    }

    @Transactional
    public PlanDto updatePlan(PlanDto d) {
        CurrentUser me = requireAdmin();
        if (d.activationTier2Max() <= d.activationTier1Max()) {
            throw new BusinessRuleException("The second activation tier has to start above the first.");
        }
        if (d.accessoryTier3From().compareTo(d.accessoryTier1Max()) <= 0) {
            throw new BusinessRuleException("The top accessory bracket has to start above the first one.");
        }
        CommissionPlan p = currentPlan();
        p.setActivationTier1Max(d.activationTier1Max());
        p.setActivationTier1Rate(d.activationTier1Rate());
        p.setActivationTier2Max(d.activationTier2Max());
        p.setActivationTier2Rate(d.activationTier2Rate());
        p.setActivationTier3Rate(d.activationTier3Rate());
        p.setUpgradeTier1Max(d.upgradeTier1Max());
        p.setUpgradeTier1Rate(d.upgradeTier1Rate());
        p.setUpgradeTier2Rate(d.upgradeTier2Rate());
        p.setBtsRate(d.btsRate());
        p.setHomeInternetRate(d.homeInternetRate());
        p.setUnlockRate(d.unlockRate());
        p.setMetroLockedRate(d.metroLockedRate());
        p.setFreeLinePaysAs(d.freeLinePaysAs());
        p.setReactivationPaysAs(d.reactivationPaysAs());
        p.setCostPercent(d.costPercent());
        p.setAccessoryTier1Max(d.accessoryTier1Max());
        p.setAccessoryTier1Percent(d.accessoryTier1Percent());
        p.setAccessoryTier3From(d.accessoryTier3From());
        p.setAccessoryTier2Percent(d.accessoryTier2Percent());
        p.setAccessoryTier3Percent(d.accessoryTier3Percent());
        p.setAccessoryMode(d.accessoryMode());
        p.setLateFinePerMinute(d.lateFinePerMinute());
        p.setNoCallNoShowFine(d.noCallNoShowFine());
        p.setNoUniformFine(d.noUniformFine());
        p.setMissedMeetingFine(d.missedMeetingFine());
        p.setMissedSyncMeetingFine(d.missedSyncMeetingFine());
        p.setScorecardReward(d.scorecardReward());
        p.setUpdatedAt(Instant.now());
        p.setUpdatedBy(me.fullName());
        return toDto(planRepository.save(p));
    }

    /**
     * The plan row (created at startup by {@link CommissionPlanSetup}). If it's somehow missing, the defaults are
     * used and saved on the first change; reads never insert, so pages opened at the same time can't collide.
     */
    private CommissionPlan currentPlan() {
        return planRepository.findById(CommissionPlan.ID).orElseGet(CommissionPlan::new);
    }

    // ---------- Fines and rewards ----------

    @Transactional
    public List<AdjustmentTypeOption> adjustmentTypes() {
        requireAdmin();
        CommissionPlan p = currentPlan();
        List<AdjustmentTypeOption> list = new ArrayList<>();
        for (AdjustmentType t : AdjustmentType.values()) {
            list.add(new AdjustmentTypeOption(t, t.getLabel(), t.isReward(), t.isPerMinute(), defaultAmount(p, t)));
        }
        return list;
    }

    @Transactional
    public AdjustmentResponse addAdjustment(AdjustmentRequest r) {
        CurrentUser me = requireAdmin();
        requireOpen(YearMonth.from(r.date()));
        Employee e = employeeRepository.findById(r.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee", r.employeeId()));
        if (e.getRole() == Role.ADMIN) {
            throw new BusinessRuleException("Fines and rewards are for staff, not admins.");
        }
        if (r.date().isAfter(LocalDate.now())) {
            throw new BusinessRuleException("Pick today or an earlier day.");
        }
        int qty = r.quantity() == null ? 1 : r.quantity();
        if (!r.type().isPerMinute()) {
            qty = 1;
        }
        BigDecimal amount = r.amount() != null ? r.amount()
                : defaultAmount(currentPlan(), r.type()).multiply(BigDecimal.valueOf(qty));
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() == 0 && (r.type() == AdjustmentType.OTHER_FINE || r.type() == AdjustmentType.OTHER_BONUS)) {
            throw new BusinessRuleException("Enter the amount.");
        }
        CommissionAdjustment a = new CommissionAdjustment();
        a.setEmployee(e);
        a.setDate(r.date());
        a.setType(r.type());
        a.setQuantity(qty);
        a.setAmount(r.type().isReward() ? amount : amount.negate());
        a.setNote(InputRules.clean(r.note()));
        a.setCreatedBy(me.fullName());
        return toResponse(adjustmentRepository.save(a));
    }

    @Transactional
    public void deleteAdjustment(Long id) {
        requireAdmin();
        CommissionAdjustment a = adjustmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Fine or reward", id));
        requireOpen(YearMonth.from(a.getDate()));
        adjustmentRepository.delete(a);
    }

    // ---------- Months ----------

    /** Everyone's commission for a month: live while open, the stored payouts once finalized. */
    @Transactional
    public MonthResponse month(YearMonth month, Long areaId) {
        requireAdmin();
        CommissionPlan plan = currentPlan();
        Optional<CommissionPeriod> finalized = periodRepository.findByMonth(month.toString());
        Map<Long, List<CommissionAdjustment>> adjustments = adjustmentsByPerson(month);
        List<EodReport> reports = reportRepository.findByDateBetweenOrderByDateAsc(month.atDay(1), month.atEndOfMonth())
                .stream()
                .filter(r -> areaId == null || r.getStore().getArea().getId().equals(areaId))
                .toList();
        Map<Long, List<Credit>> approved = new HashMap<>();
        Map<Long, List<Credit>> pending = new HashMap<>();
        for (EodReport r : reports) {
            for (Credit c : EodCredits.of(r)) {
                (r.getStatus() == EodStatus.APPROVED ? approved : pending)
                        .computeIfAbsent(c.employeeId(), k -> new ArrayList<>()).add(c);
            }
        }

        List<Statement> statements = new ArrayList<>();
        if (finalized.isPresent()) {
            for (CommissionPayout p : finalized.get().getPayouts()) {
                Employee e = p.getEmployee();
                if (inArea(e, areaId)) {
                    statements.add(statement(e, p.toBreakdown(), pending.getOrDefault(e.getId(), List.of()),
                            adjustments.getOrDefault(e.getId(), List.of())));
                }
            }
        } else {
            for (Employee e : people(areaId, approved, adjustments)) {
                statements.add(statement(e, breakdown(plan, approved.getOrDefault(e.getId(), List.of()),
                        adjustments.getOrDefault(e.getId(), List.of())), pending.getOrDefault(e.getId(), List.of()),
                        adjustments.getOrDefault(e.getId(), List.of())));
            }
        }
        statements.sort(Comparator.comparing((Statement s) -> s.pay().total()).reversed()
                .thenComparing(Statement::name));

        MonthTotals totals = new MonthTotals(
                sum(statements, s -> s.pay().boxPay()), sum(statements, s -> s.pay().accessoryPay()),
                sum(statements, s -> s.pay().fines()), sum(statements, s -> s.pay().rewards()),
                sum(statements, s -> s.pay().total()), statements.size());
        int unapproved = (int) reports.stream().filter(r -> r.getStatus() != EodStatus.APPROVED).count();
        return new MonthResponse(month.toString(), areaId, finalized.isPresent(),
                finalized.map(CommissionPeriod::getFinalizedAt).orElse(null),
                finalized.map(CommissionPeriod::getFinalizedBy).orElse(null), totals, statements,
                warnings(plan, statements, unapproved, finalized.isPresent(), untaxedStores(areaId)), unapproved);
    }

    /** Locks the month: stores everyone's payout as it is now (all areas). */
    @Transactional
    public MonthResponse finalizeMonth(YearMonth month) {
        CurrentUser me = requireAdmin();
        if (month.isAfter(YearMonth.now())) {
            throw new BusinessRuleException("A month can't be finalized before it starts.");
        }
        if (periodRepository.findByMonth(month.toString()).isPresent()) {
            throw new BusinessRuleException(month + " is already finalized.");
        }
        MonthResponse live = month(month, null);
        CommissionPeriod period = new CommissionPeriod();
        period.setMonth(month.toString());
        period.setFinalizedAt(Instant.now());
        period.setFinalizedBy(me.fullName());
        for (Statement s : live.statements()) {
            Employee e = employeeRepository.findById(s.employeeId()).orElseThrow();
            period.getPayouts().add(new CommissionPayout(period, e, s.pay()));
        }
        periodRepository.saveAndFlush(period);
        return month(month, null);
    }

    /** Unlocks a finalized month: payouts are worked out live again (e.g. after fixing an EOD). */
    @Transactional
    public MonthResponse reopenMonth(YearMonth month) {
        requireAdmin();
        CommissionPeriod period = periodRepository.findByMonth(month.toString())
                .orElseThrow(() -> new BusinessRuleException(month + " isn't finalized."));
        periodRepository.delete(period);
        periodRepository.flush();
        return month(month, null);
    }

    // ---------- Building blocks ----------

    /** The plan applied to a person's approved credits and adjustments. */
    static Breakdown breakdown(CommissionPlan plan, List<Credit> credits, List<CommissionAdjustment> adjustments) {
        Map<EodCategory, Integer> boxes = new EnumMap<>(EodCategory.class);
        BigDecimal accessories = ZERO;
        BigDecimal afterTax = BigDecimal.ZERO;
        for (Credit c : credits) {
            if (c.boxes() > 0) {
                boxes.merge(c.category(), c.boxes(), Integer::sum);
            }
            accessories = accessories.add(c.accessories());
            afterTax = afterTax.add(CommissionCalculator.withoutTax(c.accessories(), c.taxRate()));
        }
        BigDecimal fines = adjustments.stream().map(CommissionAdjustment::getAmount).filter(a -> a.signum() < 0)
                .reduce(ZERO, BigDecimal::add);
        BigDecimal rewards = adjustments.stream().map(CommissionAdjustment::getAmount).filter(a -> a.signum() > 0)
                .reduce(ZERO, BigDecimal::add);
        return CommissionCalculator.calculate(plan, new CommissionCalculator.Month(
                boxes.getOrDefault(EodCategory.NL_AAL, 0), boxes.getOrDefault(EodCategory.UPG, 0),
                boxes.getOrDefault(EodCategory.BTS, 0), boxes.getOrDefault(EodCategory.HINT, 0),
                boxes.getOrDefault(EodCategory.UNLOCK, 0), boxes.getOrDefault(EodCategory.M_LOCK, 0),
                boxes.getOrDefault(EodCategory.FREE_LINE, 0), boxes.getOrDefault(EodCategory.REACT, 0), accessories,
                afterTax, fines, rewards));
    }

    /** Active staff in scope, plus anyone with approved sales or adjustments that month. */
    private List<Employee> people(Long areaId, Map<Long, List<Credit>> approved,
            Map<Long, List<CommissionAdjustment>> adjustments) {
        Map<Long, Employee> people = new LinkedHashMap<>();
        employeeRepository.findAll().stream()
                .filter(e -> e.isActive() && e.getRole() != Role.ADMIN && inArea(e, areaId))
                .forEach(e -> people.put(e.getId(), e));
        for (Long id : approved.keySet()) {
            employeeRepository.findById(id).filter(e -> inArea(e, areaId)).ifPresent(e -> people.putIfAbsent(id, e));
        }
        for (Long id : adjustments.keySet()) {
            employeeRepository.findById(id).filter(e -> inArea(e, areaId)).ifPresent(e -> people.putIfAbsent(id, e));
        }
        return new ArrayList<>(people.values());
    }

    private static boolean inArea(Employee e, Long areaId) {
        return areaId == null || (e.getArea() != null && e.getArea().getId().equals(areaId));
    }

    private Map<Long, List<CommissionAdjustment>> adjustmentsByPerson(YearMonth month) {
        Map<Long, List<CommissionAdjustment>> map = new HashMap<>();
        adjustmentRepository.findByDateBetweenOrderByDateAscIdAsc(month.atDay(1), month.atEndOfMonth())
                .forEach(a -> map.computeIfAbsent(a.getEmployee().getId(), k -> new ArrayList<>()).add(a));
        return map;
    }

    private Statement statement(Employee e, Breakdown pay, List<Credit> pending, List<CommissionAdjustment> adj) {
        int pendingBoxes = pending.stream().mapToInt(Credit::boxes).sum();
        BigDecimal pendingAcc = pending.stream().map(Credit::accessories).reduce(ZERO, BigDecimal::add);
        int ncns = (int) adj.stream().filter(a -> a.getType() == AdjustmentType.NO_CALL_NO_SHOW).count();
        return new Statement(e.getId(), e.getFullName(), e.getEmployeeCode(),
                e.getArea() == null ? null : e.getArea().getName(),
                e.getHomeStore() == null ? null : e.getHomeStore().getName(), e.isActive(), pay, pendingBoxes,
                pendingAcc, ncns, adj.stream().map(CommissionService::toResponse).toList());
    }

    /** Active stores in scope with no sales tax rate set. */
    private List<String> untaxedStores(Long areaId) {
        return storeRepository.findAll().stream()
                .filter(s -> s.isActive() && (areaId == null || s.getArea().getId().equals(areaId)))
                .filter(s -> s.getTaxRate() == null || s.getTaxRate().signum() == 0)
                .map(com.example.schedulingsoftware.store.Store::getName)
                .sorted()
                .toList();
    }

    private static List<String> warnings(CommissionPlan plan, List<Statement> statements, int unapproved,
            boolean finalized, List<String> untaxedStores) {
        List<String> w = new ArrayList<>();
        if (!finalized && unapproved > 0) {
            w.add(unapproved + " EOD" + (unapproved == 1 ? " isn't" : "s aren't")
                    + " approved yet. Their sales aren't counted until a manager approves them.");
        }
        if (!untaxedStores.isEmpty()) {
            w.add("No sales tax rate set for " + String.join(", ", untaxedStores) + ". Set it on the Stores page; "
                    + "until then their accessories are paid before tax.");
        }
        if (plan.getCostPercent().signum() == 0) {
            w.add("Accessory cost is set to 0%, so accessories are paid before cost. Set it under Plan settings.");
        }
        if (plan.getFreeLinePaysAs() == PaysAs.NONE || plan.getReactivationPaysAs() == PaysAs.NONE) {
            w.add("FREE LINE and REACT aren't on the compensation sheet, so they pay nothing unless you choose "
                    + "otherwise under Plan settings.");
        }
        statements.stream().filter(s -> s.noCallNoShows() >= 3)
                .forEach(s -> w.add(s.name() + " has " + s.noCallNoShows()
                        + " no call, no shows this month. The sheet says that means letting them go."));
        return w;
    }

    private static BigDecimal sum(List<Statement> list, java.util.function.Function<Statement, BigDecimal> f) {
        return list.stream().map(f).reduce(ZERO, BigDecimal::add);
    }

    private void requireOpen(YearMonth month) {
        if (periodRepository.findByMonth(month.toString()).isPresent()) {
            throw new BusinessRuleException(month + " is finalized. Reopen it to change fines or rewards.");
        }
    }

    private CurrentUser requireAdmin() {
        CurrentUser me = accessPolicy.current();
        if (!me.isAdmin()) {
            throw new AccessDeniedException("Commission is for admins only.");
        }
        return me;
    }

    private static BigDecimal defaultAmount(CommissionPlan p, AdjustmentType t) {
        return switch (t) {
            case LATE -> p.getLateFinePerMinute();
            case NO_CALL_NO_SHOW -> p.getNoCallNoShowFine();
            case NO_UNIFORM -> p.getNoUniformFine();
            case MISSED_MEETING -> p.getMissedMeetingFine();
            case MISSED_SYNC_MEETING -> p.getMissedSyncMeetingFine();
            case SCORECARD_REWARD -> p.getScorecardReward();
            case OTHER_FINE, OTHER_BONUS -> BigDecimal.ZERO;
        };
    }

    private static AdjustmentResponse toResponse(CommissionAdjustment a) {
        return new AdjustmentResponse(a.getId(), a.getEmployee().getId(), a.getEmployee().getFullName(), a.getDate(),
                a.getType(), a.getType().getLabel(), a.getQuantity(), a.getAmount(), a.getNote(), a.getCreatedBy(),
                a.getCreatedAt());
    }

    private static PlanDto toDto(CommissionPlan p) {
        return new PlanDto(p.getActivationTier1Max(), p.getActivationTier1Rate(), p.getActivationTier2Max(),
                p.getActivationTier2Rate(), p.getActivationTier3Rate(), p.getUpgradeTier1Max(),
                p.getUpgradeTier1Rate(), p.getUpgradeTier2Rate(), p.getBtsRate(), p.getHomeInternetRate(),
                p.getUnlockRate(), p.getMetroLockedRate(), p.getFreeLinePaysAs(), p.getReactivationPaysAs(),
                p.getCostPercent(), p.getAccessoryTier1Max(), p.getAccessoryTier1Percent(),
                p.getAccessoryTier3From(), p.getAccessoryTier2Percent(), p.getAccessoryTier3Percent(),
                p.getAccessoryMode(), p.getLateFinePerMinute(), p.getNoCallNoShowFine(), p.getNoUniformFine(),
                p.getMissedMeetingFine(), p.getMissedSyncMeetingFine(), p.getScorecardReward(), p.getUpdatedAt(),
                p.getUpdatedBy());
    }
}
