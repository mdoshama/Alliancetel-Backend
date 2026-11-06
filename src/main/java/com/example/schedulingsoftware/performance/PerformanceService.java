package com.example.schedulingsoftware.performance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.common.BusinessRuleException;
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
import com.example.schedulingsoftware.performance.PerformanceDtos.CategoryBoxes;
import com.example.schedulingsoftware.performance.PerformanceDtos.CreditItem;
import com.example.schedulingsoftware.performance.PerformanceDtos.DayPoint;
import com.example.schedulingsoftware.performance.PerformanceDtos.EmployeeStats;
import com.example.schedulingsoftware.performance.PerformanceDtos.Leaderboard;
import com.example.schedulingsoftware.performance.PerformanceDtos.Profile;
import com.example.schedulingsoftware.performance.PerformanceDtos.StoreStats;
import com.example.schedulingsoftware.performance.PerformanceDtos.Totals;
import com.example.schedulingsoftware.schedule.ScheduleEntry;
import com.example.schedulingsoftware.schedule.ScheduleEntryRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sales performance by employee, built from the EOD credit ledger ({@link EodCredits}) and the published schedule
 * (shifts worked). Admins see every area, managers their own, employees only their own profile.
 */
@Service
@RequiredArgsConstructor
public class PerformanceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final int MAX_DAYS = 366;

    private final EodReportRepository reportRepository;
    private final EmployeeRepository employeeRepository;
    private final ScheduleEntryRepository scheduleEntryRepository;
    private final AccessPolicy accessPolicy;

    /** Everyone in the area (or all areas for an admin without one), ranked by boxes, with a daily series. */
    @Transactional(readOnly = true)
    public Leaderboard leaderboard(Long areaId, Long storeId, LocalDate from, LocalDate to) {
        CurrentUser me = accessPolicy.current();
        if (me.isEmployee()) {
            throw new AccessDeniedException("Only managers and admins see everyone's performance.");
        }
        Long area = accessPolicy.restrictToArea(areaId);
        checkRange(from, to);
        List<EodReport> reports = reports(area, storeId, from, to);
        List<Credit> credits = reports.stream().flatMap(r -> EodCredits.of(r).stream()).toList();
        Map<Long, List<ScheduleEntry>> shifts = shifts(area, storeId, from, to);

        // Everyone active in scope shows up, with zeros if they sold nothing; plus anyone with credit.
        Map<Long, Employee> people = new LinkedHashMap<>();
        employeeRepository.findAll().stream()
                .filter(e -> e.isActive() && e.getRole() != Role.ADMIN && e.getArea() != null)
                .filter(e -> area == null || e.getArea().getId().equals(area))
                .filter(e -> storeId == null || (e.getHomeStore() != null && e.getHomeStore().getId().equals(storeId))
                        || shifts.containsKey(e.getId()))
                .forEach(e -> people.put(e.getId(), e));
        credits.forEach(c -> people.computeIfAbsent(c.employeeId(),
                id -> employeeRepository.findById(id).orElse(null)));
        people.values().removeIf(java.util.Objects::isNull);

        Map<Long, List<Credit>> byPerson = group(credits);
        List<EmployeeStats> stats = people.values().stream()
                .map(e -> stats(e, byPerson.getOrDefault(e.getId(), List.of()), shifts.getOrDefault(e.getId(), List.of())))
                .sorted(ranking())
                .toList();
        return new Leaderboard(from, to, area, storeId, totals(reports, credits), stats, days(credits, from, to));
    }

    /** One person's performance. Employees may only open their own. */
    @Transactional(readOnly = true)
    public Profile profile(Long employeeId, LocalDate from, LocalDate to) {
        CurrentUser me = accessPolicy.current();
        Employee e = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", employeeId));
        if (me.isEmployee() && !me.id().equals(employeeId)) {
            throw new AccessDeniedException("You can only see your own performance.");
        }
        if (me.isManager() && (e.getArea() == null || !e.getArea().getId().equals(me.areaId()))) {
            throw new AccessDeniedException("You can only see people in your area.");
        }
        checkRange(from, to);
        Long area = e.getArea() == null ? null : e.getArea().getId();
        List<Credit> areaCredits = area == null ? List.of()
                : reports(area, null, from, to).stream().flatMap(r -> EodCredits.of(r).stream()).toList();
        Map<Long, List<Credit>> byPerson = group(areaCredits);
        List<Credit> mine = byPerson.getOrDefault(employeeId, List.of());
        Map<Long, List<ScheduleEntry>> shifts = area == null ? Map.of() : shifts(area, null, from, to);
        EmployeeStats stats = stats(e, mine, shifts.getOrDefault(employeeId, List.of()));

        // Rank among everyone in the area with a role on the floor.
        List<EmployeeStats> everyone = employeeRepository.findAll().stream()
                .filter(x -> x.getArea() != null && x.getArea().getId().equals(area) && x.getRole() != Role.ADMIN
                        && (x.isActive() || byPerson.containsKey(x.getId())))
                .map(x -> stats(x, byPerson.getOrDefault(x.getId(), List.of()), List.of()))
                .sorted(ranking())
                .toList();
        int rank = 0;
        for (int i = 0; i < everyone.size(); i++) {
            if (everyone.get(i).employeeId().equals(employeeId)) {
                rank = i + 1;
            }
        }

        Map<Long, StoreStats> stores = new LinkedHashMap<>();
        for (Credit c : mine) {
            StoreStats s = stores.getOrDefault(c.storeId(), new StoreStats(c.storeId(), c.storeName(), 0, ZERO, 0));
            stores.put(c.storeId(), new StoreStats(s.storeId(), s.storeName(), s.boxes() + c.boxes(),
                    s.accessories().add(c.accessories()), s.sales() + (c.rep() ? 1 : 0)));
        }
        List<CreditItem> recent = mine.stream()
                .sorted(Comparator.comparing(Credit::date).reversed()
                        .thenComparing(c -> c.lineId() == null ? Long.MAX_VALUE : c.lineId(), Comparator.reverseOrder()))
                .limit(100)
                .map(c -> new CreditItem(c.date(), c.storeName(), c.status(), c.type(), c.category(),
                        c.category() == null ? "Accessories" : c.category().getLabel(), c.description(), c.boxes(),
                        c.accessories(), c.soldFor(), c.shared(), c.rep(), c.lineId() == null))
                .toList();
        return new Profile(e.getId(), e.getFullName(), e.getEmployeeCode(), e.getRole(),
                e.getArea() == null ? null : e.getArea().getName(),
                // Employees don't see home stores: whether someone is primary, secondary or a floater is for admins
                // and managers only.
                e.getHomeStore() == null || accessPolicy.current().isEmployee() ? null : e.getHomeStore().getName(),
                e.getEmail(), e.isActive(), from, to,
                stats, days(mine, from, to),
                stores.values().stream().sorted(Comparator.comparing(StoreStats::boxes).reversed()).toList(),
                recent, rank, everyone.size());
    }

    // ---------- Building blocks ----------

    private List<EodReport> reports(Long area, Long storeId, LocalDate from, LocalDate to) {
        List<EodReport> reports = area == null ? reportRepository.findByDateBetweenOrderByDateAsc(from, to)
                : reportRepository.findByStoreAreaIdAndDateBetweenOrderByDateAscStoreNameAsc(area, from, to);
        return reports.stream().filter(r -> storeId == null || r.getStore().getId().equals(storeId)).toList();
    }

    /** Published shifts per person in scope. */
    private Map<Long, List<ScheduleEntry>> shifts(Long area, Long storeId, LocalDate from, LocalDate to) {
        Map<Long, List<ScheduleEntry>> result = new HashMap<>();
        for (ScheduleEntry s : scheduleEntryRepository.findPublishedShifts(area, from, to)) {
            if (storeId == null || (s.getStore() != null && s.getStore().getId().equals(storeId))) {
                result.computeIfAbsent(s.getEmployee().getId(), k -> new ArrayList<>()).add(s);
            }
        }
        return result;
    }

    private static Map<Long, List<Credit>> group(List<Credit> credits) {
        Map<Long, List<Credit>> map = new HashMap<>();
        credits.forEach(c -> map.computeIfAbsent(c.employeeId(), k -> new ArrayList<>()).add(c));
        return map;
    }

    private static EmployeeStats stats(Employee e, List<Credit> credits, List<ScheduleEntry> shifts) {
        Map<EodCategory, Integer> cats = new EnumMap<>(EodCategory.class);
        BigDecimal acc = ZERO;
        BigDecimal sold = ZERO;
        BigDecimal confirmedAcc = ZERO;
        int boxes = 0;
        int confirmedBoxes = 0;
        int sales = 0;
        Set<Long> sharedLines = new HashSet<>();
        Set<LocalDate> days = new HashSet<>();
        for (Credit c : credits) {
            boxes += c.boxes();
            if (c.boxes() > 0) {
                cats.merge(c.category(), c.boxes(), Integer::sum);
            }
            acc = acc.add(c.accessories());
            sold = sold.add(c.soldFor());
            if (c.rep()) {
                sales++;
            }
            if (c.shared() && c.lineId() != null) {
                sharedLines.add(c.lineId());
            }
            if (c.boxes() > 0 || c.accessories().signum() > 0) {
                days.add(c.date());
            }
            if (c.status() == EodStatus.APPROVED) {
                confirmedBoxes += c.boxes();
                confirmedAcc = confirmedAcc.add(c.accessories());
            }
        }
        List<CategoryBoxes> byCategory = Arrays.stream(EodCategory.values()).filter(cats::containsKey)
                .map(c -> new CategoryBoxes(c, c.getLabel(), cats.get(c))).toList();
        int shiftCount = (int) shifts.stream().map(ScheduleEntry::getWorkDate).distinct().count();
        BigDecimal perShift = shiftCount == 0 ? null
                : acc.divide(BigDecimal.valueOf(shiftCount), 2, RoundingMode.HALF_UP);
        return new EmployeeStats(e.getId(), e.getFullName(), e.getEmployeeCode(), e.getRole(),
                e.getHomeStore() == null ? null : e.getHomeStore().getName(), e.isActive(), boxes, byCategory, acc,
                sold, sales, sharedLines.size(), days.size(), shiftCount, perShift, confirmedBoxes, confirmedAcc);
    }

    /** Boxes first, then accessories, then name. */
    private static Comparator<EmployeeStats> ranking() {
        return Comparator.comparing(EmployeeStats::boxes).reversed()
                .thenComparing(EmployeeStats::accessories, Comparator.reverseOrder())
                .thenComparing(EmployeeStats::name);
    }

    private static Totals totals(List<EodReport> reports, List<Credit> credits) {
        int boxes = credits.stream().mapToInt(Credit::boxes).sum();
        BigDecimal acc = credits.stream().map(Credit::accessories).reduce(ZERO, BigDecimal::add);
        BigDecimal sold = credits.stream().map(Credit::soldFor).reduce(ZERO, BigDecimal::add);
        int sales = (int) credits.stream().filter(Credit::rep).count();
        int cBoxes = credits.stream().filter(c -> c.status() == EodStatus.APPROVED).mapToInt(Credit::boxes).sum();
        BigDecimal cAcc = credits.stream().filter(c -> c.status() == EodStatus.APPROVED).map(Credit::accessories)
                .reduce(ZERO, BigDecimal::add);
        int approved = (int) reports.stream().filter(r -> r.getStatus() == EodStatus.APPROVED).count();
        return new Totals(boxes, acc, sold, sales, cBoxes, cAcc, reports.size(), approved);
    }

    /** Every day from {@code from} to {@code to}, zeros included. */
    private static List<DayPoint> days(List<Credit> credits, LocalDate from, LocalDate to) {
        Map<LocalDate, int[]> boxes = new TreeMap<>();
        Map<LocalDate, BigDecimal[]> money = new TreeMap<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boxes.put(d, new int[1]);
            money.put(d, new BigDecimal[] {ZERO, ZERO});
        }
        for (Credit c : credits) {
            if (boxes.containsKey(c.date())) {
                boxes.get(c.date())[0] += c.boxes();
                BigDecimal[] m = money.get(c.date());
                m[0] = m[0].add(c.accessories());
                m[1] = m[1].add(c.soldFor());
            }
        }
        return boxes.keySet().stream()
                .map(d -> new DayPoint(d, boxes.get(d)[0], money.get(d)[0], money.get(d)[1]))
                .toList();
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessRuleException("Pick a start date on or before the end date.");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new BusinessRuleException("Pick a period of up to a year.");
        }
    }

}
