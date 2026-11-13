package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaService;
import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.notify.ScheduleNotifier;
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
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayOffImpact;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayRoom;
import com.example.schedulingsoftware.schedule.ScheduleDtos.WeekRequest;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ImpactLevel;
import com.example.schedulingsoftware.schedule.ScheduleDtos.Shortage;
import com.example.schedulingsoftware.schedule.ScheduleDtos.DayCell;
import com.example.schedulingsoftware.schedule.ScheduleDtos.EmployeeRow;
import com.example.schedulingsoftware.schedule.ScheduleDtos.ScheduleResponse;
import com.example.schedulingsoftware.schedule.ScheduleDtos.StoreCoverage;
import com.example.schedulingsoftware.schedule.ScheduleDtos.StoreRef;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreDtos.DayHours;
import com.example.schedulingsoftware.store.StoreRepository;
import com.example.schedulingsoftware.store.StoreService;
import com.example.schedulingsoftware.schedule.ScheduleGenerator.EmployeeInput;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ScheduleService {

    /** Managers work the floor too, so they are scheduled alongside employees. */
    private static final Set<Role> SCHEDULED_ROLES = EnumSet.of(Role.MANAGER, Role.EMPLOYEE);
    /**
     * Schedules start with everyone working, so there is no regular day off to give back when a request is approved:
     * the approved day simply becomes a day off.
     */
    private static final int NO_REGULAR_DAYS_OFF = 0;

    private final WeeklyScheduleRepository scheduleRepository;
    private final EmployeeRepository employeeRepository;
    private final DayOffRequestRepository dayOffRepository;
    private final AreaService areaService;
    private final ScheduleGenerator generator;
    private final AccessPolicy accessPolicy;
    private final StoreService storeService;
    private final StoreRepository storeRepository;
    private final StoreAssigner storeAssigner;
    private final ScheduleNotifier notifier;
    private final WeekRequests weekRequests;
    private final com.example.schedulingsoftware.dayoff.RequestPortal portal;
    private final FullWeekRepository fullWeeks;
    private final PastWeekLock pastWeekLock;

    /** Generating waits until everyone has sent their day-off request for the week (or been given a day off). */
    @org.springframework.beans.factory.annotation.Value("${alliancetel.scheduling.require-requests-before-generate:true}")
    private boolean requireRequests;

    @Transactional
    public Optional<ScheduleResponse> find(Long areaId, LocalDate anyDayInWeek) {
        LocalDate weekStart = DayOffService.weekStart(anyDayInWeek);
        accessPolicy.requireArea(areaId);
        areaService.getArea(areaId);
        CurrentUser user = accessPolicy.current();
        // Employees only see a week once their manager has published it.
        return scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .filter(s -> !user.isEmployee() || s.getStatus() == ScheduleStatus.PUBLISHED)
                .map(s -> {
                    ensureApprovedDaysOff(s);
                    return toResponse(s);
                });
    }

    /**
     * Safety net: every approved day off must show as off. Approving normally updates the week straight away; this
     * catches anything that slipped through (e.g. data from before that rule), then refills the stores.
     */
    private void ensureApprovedDaysOff(WeeklySchedule schedule) {
        boolean changed = false;
        for (DayOffRequest r : dayOffRepository.findForArea(schedule.getArea().getId(), DayOffStatus.APPROVED,
                schedule.getWeekStart(), schedule.getWeekEnd())) {
            changed |= applyDayOff(schedule, r.getEmployee().getId(), r.getOffDate()).changed();
        }
        if (changed) {
            assignStores(schedule);
            schedule.setUpdatedAt(Instant.now());
            if (schedule.getStatus() == ScheduleStatus.PUBLISHED) {
                notifier.publishedWeekChanged(schedule, null);
            }
        }
    }

    /**
     * Creates the week's schedule, or rebuilds it from scratch if it is still a draft. Everyone in the area works
     * every day a store is open; approved day-off requests are the only days off. Pending requests are shown on the
     * schedule for the manager to approve there.
     */
    @Transactional
    public ScheduleResponse generate(Long areaId, LocalDate anyDayInWeek) {
        return generate(areaId, anyDayInWeek, false);
    }

    /**
     * @param force a manager or admin generating anyway, before everyone's requests are in (people without a request
     *              simply work every day until given a day off)
     */
    @Transactional
    public ScheduleResponse generate(Long areaId, LocalDate anyDayInWeek, boolean force) {
        LocalDate weekStart = DayOffService.weekStart(anyDayInWeek);
        accessPolicy.requireManagerOf(areaId);
        pastWeekLock.requireOpen(areaId, weekStart);
        LocalDate today = LocalDate.now();
        if (weekStart.isAfter(today.plusYears(1)) || weekStart.isBefore(today.minusYears(1))) {
            throw new BusinessRuleException("Schedules can be made for weeks up to a year ahead or back.");
        }
        Area area = areaService.getArea(areaId);
        if (!area.isActive()) {
            throw new BusinessRuleException("Area '" + area.getName() + "' is inactive.");
        }

        boolean published = scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .map(w -> w.getStatus() == ScheduleStatus.PUBLISHED).orElse(false);
        if (requireRequests && !published && !force) {
            List<String> missing = weekRequests.missingNames(areaId, weekStart);
            if (!missing.isEmpty()) {
                throw new BusinessRuleException("Everyone needs a day-off request in before the schedule is made. "
                        + "Still waiting for " + missing.size() + ": " + String.join(", ", missing.stream().limit(8).toList())
                        + (missing.size() > 8 ? " and " + (missing.size() - 8) + " more" : "")
                        + ". Use \"Suggest days off\" to give them a day.");
            }
        }

        giveReservedDays(areaId, weekStart);

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
        schedule.setDraftSavedAt(null);
        scheduleRepository.flush();

        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES).stream()
                .filter(Employee::worksShifts).toList();
        if (staff.isEmpty()) {
            throw new BusinessRuleException("Area '" + area.getName() + "' has no active staff to schedule.");
        }

        Map<Long, Set<LocalDate>> approved = dayOffRepository
                .findForArea(areaId, DayOffStatus.APPROVED, weekStart, schedule.getWeekEnd()).stream()
                .collect(Collectors.groupingBy(r -> r.getEmployee().getId(),
                        Collectors.mapping(DayOffRequest::getOffDate, Collectors.toSet())));

        // Everyone works every day; only approved requests are days off. Managers give any other day off on the
        // schedule (drag to Off), and people with no day off yet are flagged.
        List<EmployeeInput> inputs = staff.stream()
                .map(e -> new EmployeeInput(e.getId(), 7, approved.getOrDefault(e.getId(), Set.of()),
                        homeStoreOf(e) == null ? null : homeStoreOf(e).getId()))
                .toList();
        Map<Long, Map<LocalDate, DayType>> plan = generator.generate(weekStart, inputs);
        offWhereEveryStoreIsClosed(areaId, plan);

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
        if (type == DayType.WORK && hasApprovedDayOff(entry.getEmployee(), entry.getWorkDate())) {
            throw new BusinessRuleException(entry.getEmployee().getFirstName() + " has an approved day off on "
                    + dayName(entry.getWorkDate()) + ", so it can't be made a work day.");
        }
        boolean approved = hasApprovedDayOff(entry.getEmployee(), entry.getWorkDate());
        if (approved && type != DayType.REQUESTED_OFF) {
            throw new BusinessRuleException(entry.getEmployee().getFirstName() + " has an approved day off on "
                    + dayName(entry.getWorkDate()) + ". It stays off.");
        }
        if (!approved && type == DayType.REQUESTED_OFF) {
            throw new BusinessRuleException("An approved day off comes from approving a day-off request. Choose Off "
                    + "to give " + entry.getEmployee().getFirstName() + " the day off.");
        }
        entry.setType(type);
        if (type != DayType.WORK) {
            entry.setStore(null);
        } else if (storeId != null) {
            Store store = storeService.requireAssignable(storeId, schedule.getArea().getId());
            requireOpen(store, entry.getWorkDate());
            entry.setStore(store);
        } else if (entry.getStore() == null) {
            Store home = homeStoreOf(entry.getEmployee());
            entry.setStore(home != null && home.isOpenOn(entry.getWorkDate()) ? home : null);
        }
        if (type == DayType.WORK && entry.getStore() == null && anyStoreOpen(schedule, entry.getWorkDate())) {
            throw new BusinessRuleException("Pick the store " + entry.getEmployee().getFirstName()
                    + " works at on this day.");
        }
        entry.setManuallyEdited(true);
        entry.setAutoAssigned(false);
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * Two employees trade their day: e.g. A at Oceanside and B at Chula Vista become A at Chula Vista and
     * B at Oceanside. Both must be working: someone who is off that day is never given a shift by a swap.
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
        if ((b.getType() == DayType.WORK && hasApprovedDayOff(a.getEmployee(), a.getWorkDate()))
                || (a.getType() == DayType.WORK && hasApprovedDayOff(b.getEmployee(), b.getWorkDate()))) {
            throw new BusinessRuleException("An approved day off can't be swapped away.");
        }
        requireNotPuttingOffPersonToWork(a, b.getType());
        requireNotPuttingOffPersonToWork(b, a.getType());
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
     * Drag and drop between any two days on the schedule: the two days trade places. One person can move their own day
     * off within the week (Ana's Tuesday at Oceanside and her Thursday off become Tuesday off and Thursday at
     * Oceanside); between two people, only work days trade, because nobody is put to work on a day they are off.
     * With {@code storeId} the dragged work day lands at that store instead (a drop on a store's row).
     *
     * <p>A work day that lands on a date its store is closed goes to the person's home store if that is open, and is
     * refused otherwise. Approved days off never move, and nobody is put to work on a day they were given off.
     */
    @Transactional
    public ScheduleResponse exchange(Long scheduleId, Long fromEntryId, Long toEntryId, Long storeId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ScheduleEntry from = entry(schedule, fromEntryId);
        ScheduleEntry to = entry(schedule, toEntryId);
        if (from.getType() == DayType.REQUESTED_OFF || to.getType() == DayType.REQUESTED_OFF) {
            throw new BusinessRuleException("An approved day off can't be moved. Reject or change the request instead.");
        }
        DayType fromType = from.getType();
        Store fromStore = from.getStore();
        if (storeId != null) {
            if (fromType != DayType.WORK) {
                throw new BusinessRuleException("Only a work day can be dropped on a store.");
            }
            fromStore = storeService.requireAssignable(storeId, schedule.getArea().getId());
        }
        if (from.getId().equals(to.getId())) {
            if (storeId == null) {
                throw new BusinessRuleException("Drop it on a different day.");
            }
            from.setStore(placeStore(schedule, from.getEmployee(), from.getWorkDate(), DayType.WORK, fromStore, true));
            from.setManuallyEdited(true);
            from.setAutoAssigned(false);
            schedule.setUpdatedAt(Instant.now());
            return toResponse(schedule);
        }
        DayType toType = to.getType();
        Store toStore = to.getStore();
        if (!from.getEmployee().getId().equals(to.getEmployee().getId())) {
            // Moving your own day off within your week is fine; handing a shift to someone on their day off is not.
            requireNotPuttingOffPersonToWork(to, fromType);
            requireNotPuttingOffPersonToWork(from, toType);
        }
        Store newToStore = placeStore(schedule, to.getEmployee(), to.getWorkDate(), fromType, fromStore, storeId != null);
        Store newFromStore = placeStore(schedule, from.getEmployee(), from.getWorkDate(), toType, toStore, false);

        to.setType(fromType);
        to.setStore(newToStore);
        from.setType(toType);
        from.setStore(newFromStore);
        for (ScheduleEntry e : List.of(from, to)) {
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
        }
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /** Where a day that is being moved ends up; see {@link #exchange}. */
    private Store placeStore(WeeklySchedule schedule, Employee person, LocalDate date, DayType type, Store store,
            boolean exactStore) {
        if (type != DayType.WORK) {
            return null;
        }
        if (hasApprovedDayOff(person, date)) {
            throw new BusinessRuleException(person.getFirstName() + " has an approved day off on " + dayName(date)
                    + ", so it can't be made a work day.");
        }
        if (store != null && store.isOpenOn(date)) {
            return store;
        }
        if (exactStore && store != null) {
            requireOpen(store, date);
        }
        Store home = homeStoreOf(person);
        if (home != null && home.isOpenOn(date)) {
            return home;
        }
        if (anyStoreOpen(schedule, date)) {
            throw new BusinessRuleException((store == null ? "That store" : store.getName()) + " is closed on "
                    + dayName(date) + ". Open the day to pick a store for " + person.getFirstName() + ".");
        }
        return null;
    }

    /**
     * Re-runs automatic store assignment on a draft: every work day a manager did not set by hand goes back to the
     * home store, then people are moved where stores are below their minimum. Use after changing minimums or stores.
     */
    @Transactional
    public ScheduleResponse autoAssignStores(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        syncApprovedDaysOff(schedule);
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
        // Days the store is closed keep their current assignment.
        List<ScheduleEntry> openDays = workDays.stream().filter(e -> store.isOpenOn(e.getWorkDate())).toList();
        if (openDays.isEmpty()) {
            throw new BusinessRuleException(store.getName() + " is closed on every day this employee works this week.");
        }
        openDays.forEach(e -> {
            e.setStore(store);
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
        });
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * "Save draft": a checkpoint on the way to publishing. Every change is stored as it is made; this marks the draft as
     * saved up to here so the manager can stop and carry on later (and see whether anything changed since).
     */
    @Transactional
    public ScheduleResponse saveDraft(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ensureApprovedDaysOff(schedule);
        schedule.markDraftSaved();
        return toResponse(schedule);
    }

    /**
     * "Add people where needed": sends someone who is working that day to a store that is short. Only people who work
     * that day can be added; anyone who is off (a regular day off or an approved request) is refused, so nobody is put
     * to work on a day off.
     */
    @Transactional
    public ScheduleResponse addToStore(Long scheduleId, Long entryId, Long storeId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ScheduleEntry entry = entry(schedule, entryId);
        Employee person = entry.getEmployee();
        if (entry.getType() != DayType.WORK || hasApprovedDayOff(person, entry.getWorkDate())) {
            throw new BusinessRuleException(person.getFirstName() + " is off on " + dayName(entry.getWorkDate())
                    + ", so they can't be added to work that day.");
        }
        Store store = storeService.requireAssignable(storeId, schedule.getArea().getId());
        requireOpen(store, entry.getWorkDate());
        if (entry.getStore() != null && entry.getStore().getId().equals(store.getId())) {
            throw new BusinessRuleException(person.getFirstName() + " already works at " + store.getName() + " on "
                    + dayName(entry.getWorkDate()) + ".");
        }
        entry.setStore(store);
        entry.setManuallyEdited(true);
        entry.setAutoAssigned(false);
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    @Transactional
    public ScheduleResponse publish(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        ensureApprovedDaysOff(schedule);
        schedule.setStatus(ScheduleStatus.PUBLISHED);
        schedule.setPublishedAt(Instant.now());
        // WhatsApp: everyone's week the first time; after changes, only the people whose days changed.
        notifier.weekPublished(schedule);
        return toResponse(schedule);
    }

    /**
     * Throws the week's schedule away (draft or published) so it can be generated again from scratch. Days off,
     * requests and reservations are kept; only the schedule (shifts, stores, edits made by hand) goes. When the new one
     * is published, everyone gets their whole week again.
     */
    @Transactional
    public void reset(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        org.slf4j.LoggerFactory.getLogger(ScheduleService.class).info("{} reset the schedule of {} for the week of {}",
                accessPolicy.current().email(), schedule.getArea().getName(), schedule.getWeekStart());
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();
    }

    @Transactional
    public ScheduleResponse revertToDraft(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        schedule.setStatus(ScheduleStatus.DRAFT);
        schedule.setPublishedAt(null);
        return toResponse(schedule);
    }

    // ---------- Approved days off ----------

    /**
     * Puts a just-approved day off onto that week's schedule if the week was already made (draft or published), so
     * nobody stays scheduled to work on a day they were given off. Returns a sentence describing the change, or null
     * if there was nothing to change.
     */
    @Transactional
    public String applyApprovedDayOff(Employee employee, LocalDate date) {
        if (employee.getArea() == null) {
            return null;
        }
        Optional<WeeklySchedule> found = scheduleRepository
                .findByAreaIdAndWeekStart(employee.getArea().getId(), DayOffService.weekStart(date));
        if (found.isEmpty()) {
            return null;
        }
        pastWeekLock.requireOpen(employee.getArea().getId(), date);
        WeeklySchedule schedule = found.get();
        Change change = applyDayOff(schedule, employee.getId(), date);
        if (!change.changed()) {
            return null;
        }
        assignStores(schedule);
        schedule.setUpdatedAt(Instant.now());
        boolean published = schedule.getStatus() == ScheduleStatus.PUBLISHED;
        if (published) {
            // Others moved to cover hear about it; the person themselves gets the day-off decision message.
            notifier.publishedWeekChanged(schedule, employee.getId());
        }
        StringBuilder text = new StringBuilder(employee.getFirstName() + " is now off on " + dayName(date) + " in the "
                + (published ? "published" : "draft") + " schedule.");
        if (change.backToWork() != null) {
            ScheduleEntry back = schedule.getEntries().stream()
                    .filter(e -> e.getEmployee().getId().equals(employee.getId()))
                    .filter(e -> e.getWorkDate().equals(change.backToWork()))
                    .findFirst().orElseThrow();
            text.append(' ').append(dayName(change.backToWork())).append(", their regular day off, is now a work day")
                    .append(back.getStore() == null ? "" : " at " + back.getStore().getName())
                    .append(" so they keep their usual days.");
        }
        if (published) {
            text.append(" Let the team know the published week changed.");
        }
        return text.toString();
    }

    /**
     * The opposite of {@link #applyApprovedDayOff}: a day off that is no longer approved becomes a work day again on
     * that week's schedule (draft or published), at their home store when it's open. Returns a sentence describing
     * the change, or null if there was nothing to change.
     */
    @Transactional
    public String revokeApprovedDayOff(Employee employee, LocalDate date) {
        if (employee.getArea() == null) {
            return null;
        }
        Optional<WeeklySchedule> found = scheduleRepository
                .findByAreaIdAndWeekStart(employee.getArea().getId(), DayOffService.weekStart(date));
        if (found.isEmpty()) {
            return null;
        }
        pastWeekLock.requireOpen(employee.getArea().getId(), date);
        WeeklySchedule schedule = found.get();
        ScheduleEntry target = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(employee.getId()))
                .filter(e -> e.getWorkDate().equals(date))
                .findFirst().orElse(null);
        if (target == null || target.getType() != DayType.REQUESTED_OFF) {
            return null;
        }
        boolean open = anyStoreOpen(schedule, date);
        target.setType(open ? DayType.WORK : DayType.OFF);
        Store home = homeStoreOf(employee);
        target.setStore(open && home != null && home.isOpenOn(date) ? home : null);
        target.setAutoAssigned(false);
        target.setManuallyEdited(false);
        assignStores(schedule);
        schedule.setUpdatedAt(Instant.now());
        boolean published = schedule.getStatus() == ScheduleStatus.PUBLISHED;
        if (published) {
            notifier.publishedWeekChanged(schedule, employee.getId());
        }
        if (!open) {
            return null;
        }
        String where = target.getStore() == null ? "" : " at " + target.getStore().getName();
        return employee.getFirstName() + " is back to work on " + dayName(date) + where + " in the "
                + (published ? "published" : "draft") + " schedule."
                + (published ? " Let the team know the published week changed." : "");
    }

    /**
     * What approving a pending day off would do to staffing that week: who else is off, how many people would still
     * be working, and whether any store would fall below its minimum. Uses the week's schedule if it exists (keeping
     * changes made by hand); otherwise it plans the week the way "Generate" would.
     */
    /**
     * Is there room for this person to be off on this date? Counts who would still work in the area: from the week's
     * schedule when it exists (others already off, plus others with a pending or approved request), otherwise
     * everyone in the area minus the others who asked for that day. Regular days off are spread over the week by the
     * generator, so only requested days count when there is no schedule yet. No access check: callers check it.
     */
    @Transactional(readOnly = true)
    /**
     * The order of the rows: managers first, then employees, then trainees; A to Z by name within each. Same order
     * for everyone, whatever they can see.
     */
    private static Comparator<EmployeeRow> rowOrder(Map<Long, List<ScheduleEntry>> byEmployee) {
        return Comparator.comparingInt(ScheduleService::rowGroup)
                .thenComparing(EmployeeRow::employeeName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EmployeeRow::employeeId);
    }

    /** 0 = managers, 1 = employees, 2 = trainees. */
    static int rowGroup(EmployeeRow r) {
        return r.role() == Role.MANAGER ? 0 : r.trainee() ? 2 : 1;
    }

    /** Where an approved day off came from, for the label managers and admins see on the schedule. */
    static String offSource(DayOffRequest r) {
        if (r.isEmergency()) {
            return "EMERGENCY";
        }
        if (r.isRegular()) {
            return "REGULAR";
        }
        if (r.isReserved()) {
            return "RESERVED";
        }
        boolean byManager = "Day off given by your manager".equals(r.getReason())
                || (r.getReviewNote() != null && r.getReviewNote().startsWith("Given by "));
        return byManager ? "MANAGER" : "REQUESTED";
    }

    /**
     * The week is being made: days the system was holding (regular days off, and the days it picked for people who
     * didn't ask) become their days off. Nobody is told now; they see it when the schedule is published. Emergency
     * requests are left for a manager or admin.
     */
    private void giveReservedDays(Long areaId, LocalDate weekStart) {
        java.time.Instant now = java.time.Instant.now();
        for (int i = 0; i < 7; i++) {
            for (DayOffRequest r : dayOffRepository.findForAreaOnDate(areaId, weekStart.plusDays(i),
                    List.of(DayOffStatus.PENDING))) {
                if (r.isReserved()) {
                    r.setStatus(DayOffStatus.APPROVED);
                    r.setReviewNote(r.isRegular() ? "Regular day off, given by the system."
                            : "Given by the system (no day was asked for).");
                    r.setReviewedAt(now);
                    r.setReviewedBy(DayOffRequest.SYSTEM);
                }
            }
        }
        dayOffRepository.flush();
    }

    /**
     * How many people the area's stores would be short on that date if exactly these people were off (primaries
     * stay at their store, floaters only cover their own stores). 0 means every store is covered.
     */
    public int shortWith(Long areaId, LocalDate date, Set<Long> offIds) {
        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES).stream()
                .filter(Employee::worksShifts).toList();
        Coverage coverage = Coverage.of(storeRepository.findByAreaIdOrderByNameAsc(areaId), staff);
        return coverage.day(date, offIds).shortBy();
    }

    public DayRoom dayRoom(Employee employee, LocalDate date, Collection<DayOffStatus> openStatuses) {
        return dayRoom(employee, date, openStatuses, false);
    }

    /**
     * Whether the stores can still be covered that day with this person off as well (see {@link Coverage}: primaries
     * stay at their store, floaters only go to their stores and within each store's floater limit).
     *
     * @param reservedCanMove when true, other people's days that are only reserved (not approved) don't count as
     *                        off: if this request is approved, their reservation moves to another day
     */
    public DayRoom dayRoom(Employee employee, LocalDate date, Collection<DayOffStatus> openStatuses,
            boolean reservedCanMove) {
        Long areaId = employee.getArea() == null ? null : employee.getArea().getId();
        if (areaId == null) {
            return new DayRoom(date, 0, 0, List.of(), false, List.of());
        }
        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES).stream()
                .filter(Employee::worksShifts).toList();
        Coverage coverage = Coverage.of(storeRepository.findByAreaIdOrderByNameAsc(areaId), staff);
        Map<Long, String> asked = new LinkedHashMap<>();
        for (DayOffRequest r : dayOffRepository.findForAreaOnDate(areaId, date, openStatuses)) {
            Employee e = r.getEmployee();
            if (reservedCanMove && r.isMovable()) {
                continue;
            }
            if (e.worksShifts() && !e.getId().equals(employee.getId())) {
                asked.put(e.getId(), e.getFirstName());
            }
        }
        Set<Long> off = new HashSet<>(asked.keySet());
        Optional<WeeklySchedule> schedule = scheduleRepository.findByAreaIdAndWeekStart(areaId, DayOffService.weekStart(date));
        if (schedule.isPresent()) {
            List<ScheduleEntry> day = schedule.get().getEntries().stream()
                    .filter(en -> en.getWorkDate().equals(date) && en.getEmployee().isActive())
                    .toList();
            boolean meWorking = day.stream()
                    .anyMatch(en -> en.getEmployee().getId().equals(employee.getId()) && en.getType() == DayType.WORK);
            if (!meWorking && day.stream().anyMatch(en -> en.getEmployee().getId().equals(employee.getId()))) {
                // Already off that day in the schedule: taking it as a requested day changes nobody's count.
                return new DayRoom(date, coverage.needed(date), Integer.MAX_VALUE, List.copyOf(asked.values()), false,
                        List.of());
            }
            // Others the schedule already has off that day.
            day.stream().filter(en -> en.getType() != DayType.WORK && !en.getEmployee().getId().equals(employee.getId()))
                    .forEach(en -> off.add(en.getEmployee().getId()));
        }
        Set<Long> withMe = new HashSet<>(off);
        withMe.add(employee.getId());
        Coverage.Day before = coverage.day(date, off);
        Coverage.Day after = coverage.day(date, withMe);
        boolean full = !Coverage.fine(before, after);
        return new DayRoom(date, after.needed(), after.working(), List.copyOf(asked.values()), full,
                full ? after.shortStores() : List.of());
    }

    @Transactional(readOnly = true)
    public DayOffImpact dayOffImpact(Long requestId) {
        DayOffRequest request = dayOffRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Day-off request", requestId));
        Employee employee = request.getEmployee();
        if (employee.getArea() == null) {
            throw new BusinessRuleException(employee.getFullName() + " is not in an area.");
        }
        Long areaId = employee.getArea().getId();
        accessPolicy.requireManagerOf(areaId);
        LocalDate date = request.getOffDate();
        LocalDate weekStart = DayOffService.weekStart(date);
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dates.add(weekStart.plusDays(i));
        }
        List<DayOffRequest> otherApproved = dayOffRepository
                .findForArea(areaId, DayOffStatus.APPROVED, weekStart, weekStart.plusDays(6)).stream()
                .filter(r -> !r.getId().equals(requestId))
                .toList();
        Optional<WeeklySchedule> schedule = scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart);

        Map<Long, Sim> before;
        Map<Long, Sim> after;
        LocalDate backToWork = null;
        if (schedule.isPresent()) {
            before = planFrom(schedule.get(), otherApproved);
            after = copy(before);
            Sim me = after.get(employee.getId());
            if (me != null) {
                me.lockedStores().remove(date);
                me.fixed().remove(date);
                backToWork = DayOffPlanner.takeDayOff(me.days(), date, NO_REGULAR_DAYS_OFF, me.fixed(), working(after));
            }
        } else {
            before = planFromGenerator(areaId, weekStart, otherApproved, null);
            after = planFromGenerator(areaId, weekStart, otherApproved, request);
        }

        List<Store> stores = storeRepository.findByAreaIdOrderByNameAsc(areaId).stream().filter(Store::isActive).toList();
        Eval was = evaluate(before, stores, dates);
        Eval will = evaluate(after, stores, dates);
        // A store-day counts if it is newly short, or short by more people than before.
        Map<String, Integer> before0 = new HashMap<>();
        was.gaps().forEach(g -> before0.put(g.storeName() + "|" + g.date(), g.scheduled()));
        List<Shortage> newShortages = will.gaps().stream()
                .filter(g -> {
                    Integer had = before0.get(g.storeName() + "|" + g.date());
                    return had == null || g.scheduled() < had;
                })
                .toList();
        boolean alreadyShortThatDay = will.gaps().stream().anyMatch(g -> g.date().equals(date));
        int needed = stores.stream().mapToInt(s -> s.minStaffOn(date)).sum();
        int workingBefore = was.working().getOrDefault(date, 0);
        int workingAfter = will.working().getOrDefault(date, 0);

        ImpactLevel level;
        String summary;
        String day = dayName(date);
        if (schedule.isPresent() && newShortages.isEmpty() && workingAfter == workingBefore && backToWork == null) {
            level = ImpactLevel.OK;
            summary = employee.getFirstName() + " is already off on " + day + " in the schedule, so nobody else is affected.";
        } else if (!newShortages.isEmpty()) {
            level = ImpactLevel.SHORT;
            Shortage first = newShortages.getFirst();
            summary = "Approving leaves " + first.storeName() + " with " + first.scheduled() + " of "
                    + first.required() + " needed on " + dayName(first.date())
                    + (newShortages.size() > 1 ? " (and " + (newShortages.size() - 1) + " more store-day"
                            + (newShortages.size() == 2 ? "" : "s") + " short)" : "") + ".";
        } else if (alreadyShortThatDay) {
            level = ImpactLevel.TIGHT;
            summary = day + " is short-staffed either way (" + workingAfter + " working for " + needed
                    + " needed); approving doesn't make it worse.";
        } else if (workingAfter <= needed) {
            level = ImpactLevel.TIGHT;
            summary = "Every store stays covered on " + day + ", but with nobody to spare (" + workingAfter
                    + " working for " + needed + " needed).";
        } else {
            level = ImpactLevel.OK;
            summary = "Safe to approve: " + workingAfter + " people still working on " + day + " for " + needed
                    + " needed.";
        }

        List<String> alreadyOff = dayOffRepository
                .findForAreaOnDate(areaId, date, EnumSet.of(DayOffStatus.APPROVED)).stream()
                .filter(r -> !r.getId().equals(requestId))
                .map(r -> r.getEmployee().getFullName())
                .toList();
        List<String> alsoRequested = dayOffRepository
                .findForAreaOnDate(areaId, date, EnumSet.of(DayOffStatus.PENDING)).stream()
                .filter(r -> !r.getId().equals(requestId))
                .map(r -> r.getEmployee().getFullName())
                .toList();

        List<String> sameStoreOff = employee.getHomeStore() == null ? List.of()
                : dayOffRepository.findSameStore(employee.getHomeStore().getId(), employee.getId(), date, date,
                        EnumSet.of(DayOffStatus.PENDING, DayOffStatus.APPROVED)).stream()
                        .map(r -> r.getEmployee().getFullName() + (r.getStatus() == DayOffStatus.PENDING ? " (waiting)" : ""))
                        .toList();

        return new DayOffImpact(requestId, employee.getFullName(), date, level, summary, workingBefore, workingAfter,
                needed, backToWork, schedule.map(WeeklySchedule::getStatus).orElse(null), alreadyOff, alsoRequested,
                sameStoreOff, request.isEmergency(), newShortages, was.gaps().size());
    }

    /**
     * Someone who is off that day (a regular day off or an approved request) can't be handed a work day by a swap with
     * another person. To bring them back, the manager changes their own day back to Work.
     */
    private static void requireNotPuttingOffPersonToWork(ScheduleEntry entry, DayType newType) {
        if (entry.getType() != DayType.WORK && newType == DayType.WORK) {
            throw new BusinessRuleException(entry.getEmployee().getFirstName() + " is off on "
                    + dayName(entry.getWorkDate()) + ", so they can't be assigned to work that day. Swaps between "
                    + "two people only trade stores when both are working.");
        }
    }

    private boolean hasApprovedDayOff(Employee employee, LocalDate date) {
        return dayOffRepository.existsByEmployeeIdAndOffDateAndStatus(employee.getId(), date, DayOffStatus.APPROVED);
    }

    private record Change(boolean changed, LocalDate backToWork) {
    }

    /** Marks one approved day off on a schedule (see {@link DayOffPlanner}); stores are reassigned by the caller. */
    private Change applyDayOff(WeeklySchedule schedule, Long employeeId, LocalDate date) {
        List<ScheduleEntry> mine = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(employeeId))
                .toList();
        ScheduleEntry target = mine.stream().filter(e -> e.getWorkDate().equals(date)).findFirst().orElse(null);
        if (target == null || target.getType() == DayType.REQUESTED_OFF) {
            return new Change(false, null);
        }
        Map<LocalDate, DayType> week = new HashMap<>();
        mine.forEach(e -> week.put(e.getWorkDate(), e.getType()));
        Set<LocalDate> fixed = mine.stream().filter(ScheduleEntry::isManuallyEdited)
                .map(ScheduleEntry::getWorkDate).collect(Collectors.toSet());
        Map<LocalDate, Integer> working = new HashMap<>();
        schedule.getEntries().stream().filter(e -> e.getType() == DayType.WORK)
                .forEach(e -> working.merge(e.getWorkDate(), 1, Integer::sum));
        LocalDate back = DayOffPlanner.takeDayOff(week, date, NO_REGULAR_DAYS_OFF, fixed, working);

        target.setType(DayType.REQUESTED_OFF);
        target.setStore(null);
        target.setAutoAssigned(false);
        target.setManuallyEdited(false);
        if (back != null) {
            mine.stream().filter(e -> e.getWorkDate().equals(back)).forEach(e -> {
                e.setType(DayType.WORK);
                e.setStore(null);
                e.setAutoAssigned(false);
                e.setManuallyEdited(false);
            });
        }
        return new Change(true, back);
    }

    /** On a day every store in the area is closed (e.g. a holiday), nobody works. */
    private void offWhereEveryStoreIsClosed(Long areaId, Map<Long, Map<LocalDate, DayType>> plan) {
        List<Store> open = storeRepository.findByAreaIdOrderByNameAsc(areaId).stream().filter(Store::isActive).toList();
        if (open.isEmpty()) {
            return;
        }
        plan.values().forEach(days -> days.replaceAll((date, type) ->
                type == DayType.WORK && open.stream().noneMatch(st -> st.isOpenOn(date)) ? DayType.OFF : type));
    }

    /** Puts every approved day off of the week onto the schedule (for weeks made before a request was approved). */
    private void syncApprovedDaysOff(WeeklySchedule schedule) {
        dayOffRepository.findForArea(schedule.getArea().getId(), DayOffStatus.APPROVED, schedule.getWeekStart(),
                schedule.getWeekEnd()).forEach(r -> applyDayOff(schedule, r.getEmployee().getId(), r.getOffDate()));
    }

    /** One person's week in a what-if plan. */
    private record Sim(Long employeeId, int workDays, Long homeStoreId, boolean anchored, boolean primary,
            Map<LocalDate, DayType> days, Set<LocalDate> fixed, Map<LocalDate, Long> lockedStores) {

        Sim copy() {
            return new Sim(employeeId, workDays, homeStoreId, anchored, primary, new HashMap<>(days),
                    new java.util.HashSet<>(fixed), new HashMap<>(lockedStores));
        }
    }

    private record Eval(Map<LocalDate, Integer> working, List<Shortage> gaps) {
    }

    private static Map<Long, Sim> copy(Map<Long, Sim> plan) {
        Map<Long, Sim> result = new LinkedHashMap<>();
        plan.forEach((id, sim) -> result.put(id, sim.copy()));
        return result;
    }

    private static Map<LocalDate, Integer> working(Map<Long, Sim> plan) {
        Map<LocalDate, Integer> working = new HashMap<>();
        plan.values().forEach(sim -> sim.days().forEach((d, t) -> {
            if (t == DayType.WORK) {
                working.merge(d, 1, Integer::sum);
            }
        }));
        return working;
    }

    /** The existing schedule as a plan, with any approved days off it is missing applied. */
    private static Map<Long, Sim> planFrom(WeeklySchedule schedule, List<DayOffRequest> approved) {
        Map<Long, Sim> plan = new LinkedHashMap<>();
        for (ScheduleEntry e : schedule.getEntries()) {
            Employee emp = e.getEmployee();
            Store home = homeStoreOf(emp);
            Sim sim = plan.computeIfAbsent(emp.getId(), id -> new Sim(id, emp.getWorkDaysPerWeek(),
                    home == null ? null : home.getId(), emp.isAnchoredToStore(), isPrimary(emp), new HashMap<>(),
                    new java.util.HashSet<>(), new HashMap<>()));
            sim.days().put(e.getWorkDate(), e.getType());
            if (e.isManuallyEdited()) {
                sim.fixed().add(e.getWorkDate());
                if (e.getStore() != null) {
                    sim.lockedStores().put(e.getWorkDate(), e.getStore().getId());
                }
            }
        }
        for (DayOffRequest r : approved) {
            Sim sim = plan.get(r.getEmployee().getId());
            if (sim != null) {
                sim.lockedStores().remove(r.getOffDate());
                DayOffPlanner.takeDayOff(sim.days(), r.getOffDate(), NO_REGULAR_DAYS_OFF, sim.fixed(), working(plan));
            }
        }
        return plan;
    }

    /** The week as "Generate" would make it from the approved requests (plus {@code extra}, if given). */
    private Map<Long, Sim> planFromGenerator(Long areaId, LocalDate weekStart, List<DayOffRequest> approved,
            DayOffRequest extra) {
        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES).stream()
                .filter(Employee::worksShifts).toList();
        Map<Long, Set<LocalDate>> off = new HashMap<>();
        approved.forEach(r -> off.computeIfAbsent(r.getEmployee().getId(), k -> new java.util.HashSet<>()).add(r.getOffDate()));
        if (extra != null) {
            off.computeIfAbsent(extra.getEmployee().getId(), k -> new java.util.HashSet<>()).add(extra.getOffDate());
        }
        Map<Long, Map<LocalDate, DayType>> week = generator.generate(weekStart, staff.stream()
                .map(e -> new EmployeeInput(e.getId(), 7, off.getOrDefault(e.getId(), Set.of()),
                        homeStoreOf(e) == null ? null : homeStoreOf(e).getId()))
                .toList());
        offWhereEveryStoreIsClosed(areaId, week);
        Map<Long, Sim> plan = new LinkedHashMap<>();
        for (Employee e : staff) {
            Store home = homeStoreOf(e);
            plan.put(e.getId(), new Sim(e.getId(), e.getWorkDaysPerWeek(), home == null ? null : home.getId(),
                    e.isAnchoredToStore(), isPrimary(e), new HashMap<>(week.get(e.getId())), new java.util.HashSet<>(),
                    new HashMap<>()));
        }
        return plan;
    }

    /** People working per day, and every store-day below its minimum once stores are assigned automatically. */
    private Eval evaluate(Map<Long, Sim> plan, List<Store> stores, List<LocalDate> dates) {
        List<StoreAssigner.StoreSlot> storeSlots = stores.stream()
                .map(s -> new StoreAssigner.StoreSlot(s.getId(), s.getMinStaffPerDay(),
                        dates.stream().filter(d -> !s.isOpenOn(d)).collect(Collectors.toSet()),
                        s.getMaxFloatersPerDay(), dailyMinimums(s, dates)))
                .toList();
        List<StoreAssigner.WorkSlot> slots = new ArrayList<>();
        Map<LocalDate, Integer> working = new HashMap<>();
        Map<Long, List<Map<LocalDate, DayType>>> primaries = new HashMap<>();
        plan.values().stream().filter(s -> s.primary() && s.homeStoreId() != null)
                .forEach(s -> primaries.computeIfAbsent(s.homeStoreId(), k -> new ArrayList<>()).add(s.days()));
        Set<String> primaryAway = primaryAway(primaries, dates);
        for (Sim sim : plan.values()) {
            sim.days().forEach((d, t) -> {
                if (t == DayType.WORK) {
                    working.merge(d, 1, Integer::sum);
                    boolean anchored = sim.anchored()
                            || (sim.homeStoreId() != null && primaryAway.contains(sim.homeStoreId() + "|" + d));
                    slots.add(new StoreAssigner.WorkSlot(sim.employeeId() + "|" + d, sim.employeeId(), d,
                            sim.homeStoreId(), anchored, sim.fixed().contains(d), sim.lockedStores().get(d)));
                }
            });
        }
        Map<String, Integer> count = new HashMap<>();
        Map<Long, Set<Long>> floatStores = new HashMap<>();
        employeeRepository.findAllById(plan.keySet()).forEach(e -> floatStores.put(e.getId(), e.floatStoreIds()));
        storeAssigner.assign(storeSlots, slots, floatStores).forEach((key, a) -> {
            if (a.storeId() != null) {
                String day = ((String) key).substring(((String) key).indexOf('|') + 1);
                count.merge(a.storeId() + "|" + day, 1, Integer::sum);
            }
        });
        List<Shortage> gaps = new ArrayList<>();
        for (LocalDate d : dates) {
            for (Store s : stores) {
                int n = count.getOrDefault(s.getId() + "|" + d, 0);
                if (s.isOpenOn(d) && n < s.minStaffOn(d)) {
                    gaps.add(new Shortage(s.getName(), d, n, s.minStaffOn(d)));
                }
            }
        }
        return new Eval(working, gaps);
    }

    /** The dates on which the store's minimum differs from its usual one (a day-specific minimum). */
    private static Map<LocalDate, Integer> dailyMinimums(Store store, List<LocalDate> dates) {
        Map<LocalDate, Integer> result = new HashMap<>();
        for (LocalDate d : dates) {
            if (store.isOpenOn(d) && store.minStaffOn(d) != store.getMinStaffPerDay()) {
                result.put(d, store.minStaffOn(d));
            }
        }
        return result;
    }

    private static String dayName(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US) + " "
                + date.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + date.getDayOfMonth();
    }

    /** Loads a schedule the current user is allowed to change (admin, or manager of its area). */
    private WeeklySchedule getManaged(Long id) {
        WeeklySchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Schedule", id));
        accessPolicy.requireManagerOf(schedule.getArea().getId());
        // A week that has passed is locked for managers until an admin opens it.
        pastWeekLock.requireOpen(schedule.getArea().getId(), schedule.getWeekStart());
        return schedule;
    }

    /** Fills in the store for every work day that was not set by hand (see {@link StoreAssigner}). */
    private void assignStores(WeeklySchedule schedule) {
        List<Store> areaStores = storeRepository.findByAreaIdOrderByNameAsc(schedule.getArea().getId());
        List<StoreAssigner.StoreSlot> stores = areaStores.stream()
                .filter(Store::isActive)
                .map(s -> new StoreAssigner.StoreSlot(s.getId(), s.getMinStaffPerDay(),
                        weekDates(schedule).stream().filter(d -> !s.isOpenOn(d)).collect(Collectors.toSet()),
                        s.getMaxFloatersPerDay(), dailyMinimums(s, weekDates(schedule))))
                .toList();
        Map<Long, Store> storesById = new HashMap<>();
        areaStores.forEach(s -> storesById.put(s.getId(), s));

        // When a store's primary is off, its secondaries stay there to cover it instead of being sent elsewhere.
        Map<Long, Map<Long, Map<LocalDate, DayType>>> primaryWeeks = new HashMap<>();
        for (ScheduleEntry e : schedule.getEntries()) {
            if (isPrimary(e.getEmployee())) {
                primaryWeeks.computeIfAbsent(e.getEmployee().getHomeStore().getId(), k -> new HashMap<>())
                        .computeIfAbsent(e.getEmployee().getId(), k -> new HashMap<>())
                        .put(e.getWorkDate(), e.getType());
            }
        }
        Map<Long, List<Map<LocalDate, DayType>>> primaries = new HashMap<>();
        primaryWeeks.forEach((store, people) -> primaries.put(store, new ArrayList<>(people.values())));
        Set<String> primaryAway = primaryAway(primaries, weekDates(schedule));

        List<StoreAssigner.WorkSlot> slots = new ArrayList<>();
        for (ScheduleEntry e : schedule.getEntries()) {
            if (e.getType() != DayType.WORK) {
                e.setStore(null);
                e.setAutoAssigned(false);
                continue;
            }
            Store home = homeStoreOf(e.getEmployee());
            boolean coverForPrimary = home != null && primaryAway.contains(home.getId() + "|" + e.getWorkDate());
            slots.add(new StoreAssigner.WorkSlot(e, e.getEmployee().getId(), e.getWorkDate(),
                    home == null ? null : home.getId(), e.getEmployee().isAnchoredToStore() || coverForPrimary,
                    e.isManuallyEdited(), e.getStore() == null ? null : e.getStore().getId()));
        }
        Map<Long, Set<Long>> floatStores = new HashMap<>();
        schedule.getEntries().forEach(en -> floatStores.computeIfAbsent(en.getEmployee().getId(),
                k -> en.getEmployee().floatStoreIds()));
        storeAssigner.assign(stores, slots, floatStores).forEach((key, assignment) -> {
            ScheduleEntry e = (ScheduleEntry) key;
            if (!e.isManuallyEdited()) {
                e.setStore(assignment.storeId() == null ? null : storesById.get(assignment.storeId()));
                e.setAutoAssigned(assignment.auto());
            }
        });
    }

    /** Primary at an active home store. */
    private static boolean isPrimary(Employee e) {
        return e.isPrimaryAtStore() && homeStoreOf(e) != null;
    }

    /**
     * "storeId|date" for every day a store that has primaries has none of them working; that day its secondaries are
     * kept at the store.
     */
    private static Set<String> primaryAway(Map<Long, List<Map<LocalDate, DayType>>> primariesByStore,
            List<LocalDate> dates) {
        Set<String> away = new java.util.HashSet<>();
        primariesByStore.forEach((store, weeks) -> {
            for (LocalDate d : dates) {
                if (weeks.stream().noneMatch(w -> w.get(d) == DayType.WORK)) {
                    away.add(store + "|" + d);
                }
            }
        });
        return away;
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

    /** Whether the area has an active store open on that date (if not, a work day can have no store). */
    private boolean anyStoreOpen(WeeklySchedule schedule, LocalDate date) {
        return storeRepository.findByAreaIdOrderByNameAsc(schedule.getArea().getId()).stream()
                .anyMatch(st -> st.isActive() && st.isOpenOn(date));
    }

    private static void requireOpen(Store store, LocalDate date) {
        if (!store.isOpenOn(date)) {
            throw new BusinessRuleException(store.getName() + " is closed on "
                    + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US) + "s.");
        }
    }

    private static List<LocalDate> weekDates(WeeklySchedule schedule) {
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dates.add(schedule.getWeekStart().plusDays(i));
        }
        return dates;
    }

    private void requireDraft(WeeklySchedule schedule) {
        if (schedule.getStatus() != ScheduleStatus.DRAFT) {
            throw new BusinessRuleException("Published schedules are locked. Revert to draft to make changes.");
        }
    }

    private ScheduleResponse toResponse(WeeklySchedule schedule) {
        List<LocalDate> dates = weekDates(schedule);

        // Primary / secondary / floater is for managers; employees don't see it.
        boolean hideStoreRoles = accessPolicy.current().isEmployee();
        // People working all 7 days this week (a manager's decision) are expected to work every day.
        Set<Long> fullWeek = fullWeeks.findForArea(schedule.getArea().getId(), schedule.getWeekStart()).stream()
                .map(f -> f.getEmployee().getId()).collect(Collectors.toSet());

        Map<Long, List<ScheduleEntry>> byEmployee = new LinkedHashMap<>();
        Map<LocalDate, Integer> headCount = new HashMap<>();
        for (ScheduleEntry entry : schedule.getEntries()) {
            byEmployee.computeIfAbsent(entry.getEmployee().getId(), k -> new ArrayList<>()).add(entry);
            if (entry.getType() == DayType.WORK) {
                headCount.merge(entry.getWorkDate(), 1, Integer::sum);
            }
        }

        // Pending requests, for managers to decide on from the schedule.
        CurrentUser me = accessPolicy.current();
        List<WeekRequest> requests = me.isEmployee() ? List.of()
                : dayOffRepository.findForArea(schedule.getArea().getId(), DayOffStatus.PENDING,
                        schedule.getWeekStart(), schedule.getWeekEnd()).stream()
                        .filter(r -> r.getEmployee().isActive())
                        .sorted(Comparator.comparing(DayOffRequest::getOffDate)
                                .thenComparing(r -> !r.isEmergency())
                                .thenComparing(DayOffRequest::getId))
                        .map(r -> new WeekRequest(r.getId(), r.getEmployee().getId(), r.getEmployee().getFullName(),
                                r.getOffDate(), r.isEmergency(), r.getReason(),
                                !me.isEmployee() && !r.getEmployee().getId().equals(me.id()), r.isReserved(),
                                r.isRegular()))
                        .toList();
        Map<String, WeekRequest> requestByDay = new HashMap<>();
        requests.forEach(r -> requestByDay.putIfAbsent(r.employeeId() + "|" + r.date(), r));
        // Approved days off, so managers can revoke one from the schedule (the only place to do it).
        Map<String, Long> approvedByDay = new HashMap<>();
        Map<String, String> sourceByDay = new HashMap<>();
        for (DayOffRequest r : dayOffRepository.findForArea(schedule.getArea().getId(), DayOffStatus.APPROVED,
                schedule.getWeekStart(), schedule.getWeekEnd())) {
            String key = r.getEmployee().getId() + "|" + r.getOffDate();
            if (!me.isEmployee()) {
                approvedByDay.put(key, r.getId());
                sourceByDay.put(key, offSource(r));
            }
        }
        // Employees just see "Day off": where it came from (requested, regular, reserved…) and waiting requests are
        // for managers and admins.
        boolean plainDaysOff = me.isEmployee();

        List<EmployeeRow> rows = byEmployee.values().stream()
                .map(entries -> {
                    Employee e = entries.getFirst().getEmployee();
                    List<DayCell> cells = entries.stream()
                            .sorted(Comparator.comparing(ScheduleEntry::getWorkDate))
                            .map(x -> new DayCell(x.getId(), x.getWorkDate(), x.getType(),
                                    x.getStore() == null ? null : x.getStore().getId(),
                                    x.getStore() == null ? null : x.getStore().getName(),
                                    x.getStore() == null ? null : x.getStore().getLabel(),
                                    x.getStore() == null ? null : x.getStore().getDisplayColor(),
                                    x.isAutoAssigned(),
                                    x.isManuallyEdited(),
                                    x.getStore() == null ? null : x.getStore().hoursOn(x.getWorkDate().getDayOfWeek()).getOpensAt(),
                                    x.getStore() == null ? null : x.getStore().hoursOn(x.getWorkDate().getDayOfWeek()).getClosesAt(),
                                    x.getStore() != null && !x.getStore().isOpenOn(x.getWorkDate()),
                                    plainDaysOff ? null : requestByDay.get(x.getEmployee().getId() + "|" + x.getWorkDate()),
                                    x.getType() == DayType.REQUESTED_OFF
                                            ? approvedByDay.get(x.getEmployee().getId() + "|" + x.getWorkDate())
                                            : null,
                                    x.getType() == DayType.REQUESTED_OFF && !plainDaysOff
                                            ? sourceByDay.getOrDefault(x.getEmployee().getId() + "|" + x.getWorkDate(),
                                                    "REQUESTED")
                                            : null))
                            .toList();
                    int working = (int) cells.stream().filter(c -> c.type() == DayType.WORK).count();
                    Store home = e.getHomeStore();
                    return new EmployeeRow(e.getId(), e.getEmployeeCode(), e.getFullName(), e.getRole(),
                            home == null ? null : home.getId(), home == null ? null : home.getName(),
                            hideStoreRoles ? null : e.getStoreRole(),
                            fullWeek.contains(e.getId()) ? 7 : e.getWorkDaysPerWeek(), working, cells,
                            hideStoreRoles ? List.of() : List.copyOf(e.floatStoreIds()), e.isTrainee());
                })
                .sorted(rowOrder(byEmployee))
                .toList();

        long pending = dayOffRepository.countForArea(schedule.getArea().getId(), DayOffStatus.PENDING,
                schedule.getWeekStart(), schedule.getWeekEnd());

        List<Store> areaStores = storeRepository.findByAreaIdOrderByNameAsc(schedule.getArea().getId());
        List<StoreRef> storeRefs = areaStores.stream()
                .filter(Store::isActive)
                .map(s -> new StoreRef(s.getId(), s.getName(), s.getCode(), s.getLabel(), s.getMinStaffPerDay(),
                        DayHours.weekOf(s), s.getDisplayColor(), s.getAddress()))
                .toList();
        List<StoreCoverage> coverage = storeCoverage(schedule, dates, areaStores);
        List<CoverageGap> gaps = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            for (StoreCoverage c : coverage) {
                int scheduled = c.dailyHeadCount().get(day);
                DayHours hours = c.dailyHours().get(day);
                // The day's own minimum when the store has one (e.g. fewer on Sunday), otherwise its usual one.
                int min = hours.minStaff() != null ? hours.minStaff() : c.minStaffPerDay();
                if (c.storeId() != null && !hours.closed() && scheduled < min) {
                    gaps.add(new CoverageGap(c.storeId(), c.storeName(), dates.get(day), scheduled, min));
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
                schedule.getDraftSavedAt(),
                schedule.isChangedSinceSave(),
                dates,
                dates.stream().map(d -> headCount.getOrDefault(d, 0)).toList(),
                pending,
                storeRefs,
                coverage,
                gaps,
                rows,
                requests);
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
                List<DayHours> daily = dates.stream().map(d -> DayHours.from(store.hoursOn(d.getDayOfWeek())))
                        .map(h -> store.isActive() ? h : new DayHours(h.day(), h.opensAt(), h.closesAt(), h.closed()))
                        .toList();
                result.add(new StoreCoverage(store.getId(), store.getName(), store.getLabel(), min, toList(c), daily,
                        store.getDisplayColor()));
            }
        }
        if (java.util.Arrays.stream(unassigned).sum() > 0) {
            List<DayHours> none = dates.stream().map(d -> new DayHours(d.getDayOfWeek(), null, null, false)).toList();
            result.add(new StoreCoverage(null, "No store assigned", "—", 0, toList(unassigned), none, null));
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
