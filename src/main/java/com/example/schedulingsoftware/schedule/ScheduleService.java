package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.dayoff.DayOffRequest;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleDtos.CoverageGap;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleDtos.StoreCoverage;
import com.example.schedulingsoftware.schedule.ScheduleDtos.StoreRef;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreRepository;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.schedule.ScheduleGenerator.EmployeeInput;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** Managers work the floor too, so they are scheduled alongside employees. */
    private static final Set<Role> SCHEDULED_ROLES = EnumSet.of(Role.MANAGER, Role.EMPLOYEE);

    private final WeeklyScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final DayOffRequestRepository dayOffRepository;
    private final AreaService areaService;
    private final ScheduleGenerator generator;
    private final AccessPolicy accessPolicy;
    private final StoreService storeService;
    private final StoreRepository storeRepository;
    private final StoreAssigner storeAssigner;

    @Transactional(readOnly = true)
    public Optional<ScheduleResponse> find(Long areaId, LocalDate anyDayInWeek) {
        LocalDate weekStart = DayOffService.weekStart(anyDayInWeek);
        accessPolicy.requireArea(areaId);
        areaService.getArea(areaId);
        CurrentUser user = accessPolicy.current();
        // Employees only see a week once their manager has published it.
        return scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .filter(s -> !user.isEmployee() || s.getStatus() == ScheduleStatus.PUBLISHED)
                .map(this::toResponse);
    }

    /**
     * Creates the week's schedule, or rebuilds it from scratch if it is still a draft.
     * Uses every approved day-off request in the area for that week.
     */
    @Transactional
    public ScheduleResponse generate(Long areaId, LocalDate anyDayInWeek) {
        LocalDate weekStart = DayOffService.weekStart(anyDayInWeek);
        accessPolicy.requireManagerOf(areaId);
        Area area = areaService.getArea(areaId);
        if (!area.isActive()) {
            throw new BusinessRuleException("Area '" + area.getName() + "' is inactive.");
        }

        WeeklySchedule schedule = scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .orElseGet(() -> {
                    WeeklySchedule s = new WeeklySchedule();
                    s.setArea(area);
                    s.setWeekStart(weekStart);
                    return scheduleRepository.save(s);
                });
        if (schedule.getStatus() == ScheduleStatus.PUBLISHED) {
            throw new BusinessRuleException("This week is already published. Revert it to draft before regenerating.");
        }

        // Remove old rows first so the unique (schedule, employee, date) constraint is not hit on re-insert.
        schedule.getEntries().clear();
        scheduleRepository.flush();

        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES);
        if (staff.isEmpty()) {
            throw new BusinessRuleException("Area '" + area.getName() + "' has no active staff to schedule.");
        }

        Map<Long, Set<LocalDate>> approved = dayOffRepository
                .findForArea(areaId, DayOffStatus.APPROVED, weekStart, schedule.getWeekEnd()).stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId(),
                        Collectors.mapping(DayOffRequest::getOffDate, Collectors.toSet())));

        List<EmployeeInput> inputs = staff.stream()
                .map(e -> new EmployeeInput(e.getId(), e.getWorkDaysPerWeek(), approved.getOrDefault(e.getId(), Set.of())))
                .toList();
        Map<Long, Map<LocalDate, DayType>> plan = generator.generate(weekStart, inputs);

        for (Employee employee : staff) {
            plan.get(employee.getId()).forEach((date, type) ->
                    schedule.getEntries().add(new ScheduleEntry(schedule, employee, date, type, homeStoreOf(employee))));
        }
        assignStores(schedule);
        schedule.setUpdatedAt(Instant.now());
        scheduleRepository.flush();
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse updateEntry(Long scheduleId, Long entryId, DayType type, Long storeId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ScheduleEntry entry = entry(schedule, entryId);
        entry.setType(type);
        if (type != DayType.WORK) {
            entry.setStore(null);
        } else if (storeId != null) {
            entry.setStore(storeService.requireAssignable(storeId, schedule.getArea().getId()));
        } else if (entry.getStore() == null) {
            entry.setStore(homeStoreOf(entry.getEmployee()));
        }
        entry.setManuallyEdited(true);
        entry.setAutoAssigned(false);
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * Two employees trade their day: e.g. A at Oceanside and B at Chula Vista become A at Chula Vista and
     * B at Oceanside. If one of them was off, the other takes over their shift and gets the day off instead.
     */
    @Transactional
    public ScheduleResponse swap(Long scheduleId, Long entryIdA, Long entryIdB) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ScheduleEntry a = entry(schedule, entryIdA);
        ScheduleEntry b = entry(schedule, entryIdB);
        if (a.getId().equals(b.getId()) || a.getEmployee().getId().equals(b.getEmployee().getId())) {
            throw new BusinessRuleException("Pick two different employees to swap.");
        }
        if (!a.getWorkDate().equals(b.getWorkDate())) {
            throw new BusinessRuleException("Swaps are between two people on the same day.");
        }
        if (a.getType() == DayType.REQUESTED_OFF || b.getType() == DayType.REQUESTED_OFF) {
            throw new BusinessRuleException("An approved day off can't be swapped away.");
        }
        DayType typeA = a.getType();
        Store storeA = a.getStore();
        a.setType(b.getType());
        a.setStore(b.getStore());
        b.setType(typeA);
        b.setStore(storeA);
        a.setManuallyEdited(true);
        b.setManuallyEdited(true);
        a.setAutoAssigned(false);
        b.setAutoAssigned(false);
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * Re-runs automatic store assignment on a draft: every work day a manager did not set by hand goes back to the
     * home store, then people are moved where stores are below their minimum. Use after changing minimums or stores.
     */
    @Transactional
    public ScheduleResponse autoAssignStores(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        assignStores(schedule);
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /** Sends one employee to a store for every day they work in this week. */
    @Transactional
    public ScheduleResponse moveForWeek(Long scheduleId, Long employeeId, Long storeId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        Store store = storeService.requireAssignable(storeId, schedule.getArea().getId());
        List<ScheduleEntry> workDays = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(employeeId))
                .filter(e -> e.getType() == DayType.WORK)
                .toList();
        if (workDays.isEmpty()) {
            throw new BusinessRuleException("This employee has no work days on this schedule.");
        }
        workDays.forEach(e -> {
            e.setStore(store);
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
        });
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse publish(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        schedule.setPublishedAt(Instant.now());
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse revertToDraft(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        schedule.setStatus(ScheduleStatus.DRAFT);
        schedule.setPublishedAt(null);
        return toResponse(schedule);
    }

    /** Loads a schedule the current user is allowed to change (admin, or manager of its area). */
    private WeeklySchedule getManaged(Long id) {
        WeeklySchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", id));
        accessPolicy.requireManagerOf(schedule.getArea().getId());
        return schedule;
    }

    /** Fills in the store for every work day that was not set by hand (see {@link StoreAssigner}). */
    private void assignStores(WeeklySchedule schedule) {
        List<Store> areaStores = storeRepository.findByAreaIdOrderByNameAsc(schedule.getArea().getId());
        List<StoreAssigner.StoreSlot> stores = areaStores.stream()
                .filter(Store::isActive)
                .map(s -> new StoreAssigner.StoreSlot(s.getId(), s.getMinStaffPerDay()))
                .toList();
        Map<Long, Store> storesById = new HashMap<>();
        areaStores.forEach(s -> storesById.put(s.getId(), s));

        List<StoreAssigner.WorkSlot> slots = new ArrayList<>();
        for (ScheduleEntry e : schedule.getEntries()) {
            if (e.getType() != DayType.WORK) {
                e.setStore(null);
                e.setAutoAssigned(false);
                continue;
            }
            Store home = homeStoreOf(e.getEmployee());
            slots.add(new StoreAssigner.WorkSlot(e, e.getEmployee().getId(), e.getWorkDate(),
                    home == null ? null : home.getId(), e.getEmployee().getRole() == Role.MANAGER,
                    e.isManuallyEdited(), e.getStore() == null ? null : e.getStore().getId()));
        }
        storeAssigner.assign(stores, slots).forEach((key, assignment) -> {
            ScheduleEntry e = (ScheduleEntry) key;
            if (!e.isManuallyEdited()) {
                e.setStore(assignment.storeId() == null ? null : storesById.get(assignment.storeId()));
                e.setAutoAssigned(assignment.auto());
            }
        });
    }

    private static ScheduleEntry entry(WeeklySchedule schedule, Long entryId) {
        return schedule.getEntries().stream()
                .filter(e -> e.getId().equals(entryId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Schedule entry", entryId));
    }

    /** The employee's home store, if it is still active. */
    private static Store homeStoreOf(Employee e) {
        Store home = e.getHomeStore();
        return home != null && home.isActive() ? home : null;
    }

    private void requireDraft(WeeklySchedule schedule) {
        if (schedule.getStatus() != ScheduleStatus.DRAFT) {
            throw new BusinessRuleException("Published schedules are locked. Revert to draft to make changes.");
        }
    }

    private ScheduleResponse toResponse(WeeklySchedule schedule) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dates.add(schedule.getWeekStart().plusDays(i));
        }

        Map<Long, List<ScheduleEntry>> byEmployee = new LinkedHashMap<>();
        Map<LocalDate, Integer> headCount = new HashMap<>();
        for (ScheduleEntry entry : schedule.getEntries()) {
            byEmployee.computeIfAbsent(entry.getEmployee().getId(), k -> new ArrayList<>()).add(entry);
            if (entry.getType() == DayType.WORK) {
                headCount.merge(entry.getWorkDate(), 1, Integer::sum);
            }
        }

        List<EmployeeRow> rows = byEmployee.values().stream()
                .map(entries -> {
                    Employee e = entries.getFirst().getEmployee();
                    List<DayCell> cells = entries.stream()
                            .sorted(Comparator.comparing(ScheduleEntry::getWorkDate))
                            .map(x -> new DayCell(x.getId(), x.getWorkDate(), x.getType(),
                                    x.getStore() == null ? null : x.getStore().getId(),
                                    x.getStore() == null ? null : x.getStore().getName(),
                                    x.getStore() == null ? null : x.getStore().getLabel(),
                                    x.isAutoAssigned(),
                                    x.isManuallyEdited()))
                            .toList();
                    int working = (int) cells.stream().filter(c -> c.type() == DayType.WORK).count();
                    Store home = e.getHomeStore();
                    return new EmployeeRow(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getRole(),
                            home == null ? null : home.getId(), home == null ? null : home.getName(),
                            e.getWorkDaysPerWeek(), working, cells);
                })
                .sorted(Comparator.comparing((EmployeeRow r) -> r.role() == Role.MANAGER ? 0 : 1)
                        .thenComparing(EmployeeRow::employeeName))
                .toList();

        long pending = dayOffRepository.countForArea(schedule.getArea().getId(), DayOffStatus.PENDING,
                schedule.getWeekStart(), schedule.getWeekEnd());

        List<Store> areaStores = storeRepository.findByAreaIdOrderByNameAsc(schedule.getArea().getId());
        List<StoreRef> storeRefs = areaStores.stream()
                .filter(Store::isActive)
                .map(s -> new StoreRef(s.getId(), s.getName(), s.getCode(), s.getLabel(), s.getMinStaffPerDay()))
                .toList();
        List<StoreCoverage> coverage = storeCoverage(schedule, dates, areaStores);
        List<CoverageGap> gaps = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            for (StoreCoverage c : coverage) {
                int scheduled = c.dailyHeadCount().get(day);
                if (c.storeId() != null && scheduled < c.minStaffPerDay()) {
                    gaps.add(new CoverageGap(c.storeId(), c.storeName(), dates.get(day), scheduled, c.minStaffPerDay()));
                }
            }
        }

        return new ScheduleResponse(
                schedule.getId(),
                schedule.getArea().getId(),
                schedule.getArea().getName(),
                schedule.getWeekStart(),
                schedule.getWeekEnd(),
                schedule.getStatus(),
                schedule.getPublishedAt(),
                schedule.getUpdatedAt(),
                dates,
                dates.stream().map(d -> headCount.getOrDefault(d, 0)).toList(),
                pending,
                storeRefs,
                coverage,
                gaps,
                rows);
    }

    /** Head count per store per day, including any inactive store still on the schedule and unassigned work days. */
    private static List<StoreCoverage> storeCoverage(WeeklySchedule schedule, List<LocalDate> dates,
            List<Store> areaStores) {
        Map<Long, int[]> counts = new HashMap<>();
        int[] unassigned = new int[7];
        for (ScheduleEntry entry : schedule.getEntries()) {
            if (entry.getType() != DayType.WORK) {
                continue;
            }
            int day = dates.indexOf(entry.getWorkDate());
            if (entry.getStore() == null) {
                unassigned[day]++;
            } else {
                counts.computeIfAbsent(entry.getStore().getId(), k -> new int[7])[day]++;
            }
        }
        List<StoreCoverage> result = new ArrayList<>();
        for (Store store : areaStores) {
            int[] c = counts.get(store.getId());
            if (store.isActive() || c != null) {
                // Inactive stores still listed (because someone is scheduled there) have no minimum to meet.
                int min = store.isActive() ? store.getMinStaffPerDay() : 0;
                result.add(new StoreCoverage(store.getId(), store.getName(), store.getLabel(), min, toList(c)));
            }
        }
        if (java.util.Arrays.stream(unassigned).sum() > 0) {
            result.add(new StoreCoverage(null, "No store assigned", "—", 0, toList(unassigned)));
        }
        return result;
    }

    private static List<Integer> toList(int[] counts) {
        List<Integer> list = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            list.add(counts == null ? 0 : counts[i]);
        }
        return list;
    }
}
