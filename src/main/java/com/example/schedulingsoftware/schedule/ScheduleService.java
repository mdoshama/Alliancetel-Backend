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
import java.util.Objects;
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
    private static final Set<Role> SCHEDULED_ROLES = EnumSet.of(Role.MANAGER, Role.MARKET_MANAGER, Role.EMPLOYEE);
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
                // Once made, the week is the manager's: it's shown exactly as they left it.
                .map(this::toResponse);
    }

    /**
     * Safety net: every approved day off must show as off. Approving normally updates the week straight away; this
     * catches anything that slipped through (e.g. data from before that rule), then refills the stores.
     */
    private void ensureApprovedDaysOff(WeeklySchedule schedule) {
        boolean changed = false;
        for (Map.Entry<Long, Set<LocalDate>> person : byPerson(offKeys(schedule)).entrySet()) {
            for (LocalDate date : person.getValue()) {
                changed |= applyDayOff(schedule, person.getKey(), date).changed();
            }
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
     * Stores closed on a date (a holiday): on every schedule of that week not yet published, people placed at a closed
     * store that day move to their home store if it's open, otherwise to the open store with the fewest people; with
     * every store closed they're off. Published weeks stay as they are until they're changed.
     */
    @Transactional
    public void storesClosed(Long areaId, LocalDate date) {
        LocalDate week = DayOffService.weekStart(date);
        List<Store> open = storeRepository.findByAreaIdOrderByNameAsc(areaId).stream()
                .filter(st -> st.isActive() && st.isOpenOn(date)).toList();
        for (WeeklySchedule s : scheduleRepository.findByAreaIdAndWeekStartOrderByKindAsc(areaId, week)) {
            if (s.getStatus() != ScheduleStatus.DRAFT) {
                continue;
            }
            boolean changed = false;
            for (ScheduleEntry e : s.getEntries()) {
                if (!e.getWorkDate().equals(date) || e.getType() != DayType.WORK
                        || e.getStore() == null || e.getStore().isOpenOn(date)) {
                    continue;
                }
                Store home = homeStoreOf(e.getEmployee());
                Store to = home != null && home.isOpenOn(date) ? home
                        : open.stream().min(java.util.Comparator.comparingLong(st -> s.getEntries().stream()
                                .filter(x -> x.getWorkDate().equals(date) && x.getType() == DayType.WORK
                                        && st.equals(x.getStore())).count())).orElse(null);
                if (to == null) {
                    e.setType(DayType.OFF);
                }
                e.setStore(to);
                e.setAutoAssigned(to != null && to != home);
                changed = true;
            }
            if (changed) {
                s.setUpdatedAt(Instant.now());
            }
        }
        scheduleRepository.flush();
    }

    /**
     * Reset: the days off a manager gave or took back by changing a schedule that week are undone (given ones go, taken
     * back ones are theirs again), so the week is built again exactly as the system would. Real decisions (requests
     * approved or turned down on the day-off page) stay.
     */
    @Transactional
    public void clearManualDaysOff(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        for (DayOffRequest r : dayOffRepository.findForArea(areaId, DayOffStatus.APPROVED, week, week.plusDays(6))) {
            if (givenOnSchedule(r)) {
                dayOffRepository.delete(r);
            }
        }
        for (DayOffRequest r : dayOffRepository.findForArea(areaId, DayOffStatus.REJECTED, week, week.plusDays(6))) {
            if (setAside(r) || wasTakenBack(r)) {
                restoreTakenBack(r);
            }
        }
        dayOffRepository.flush();
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
                throw new BusinessRuleException("Still waiting for " + missing.size() + ": " + String.join(", ", missing.stream().limit(8).toList())
                        + (missing.size() > 8 ? " and " + (missing.size() - 8) + " more" : "")
                        + ". Use \"Suggest days off\".");
            }
        }

        if (!published) {
            clearManualDaysOff(areaId, weekStart);
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
        // The empty draft is the manager's own: they decide every day, approved requests included (shown as a hint).
        // Working on an approved day off is confirmed when the draft is published.
        // Every tab is the manager's to change as they like: no day is locked. On a draft nothing is recorded until
        // it's published; an approved day off keeps its label only where it still is one on this tab.
        boolean emptyDraft = schedule.getKind() != ScheduleKind.MAIN;
        if (emptyDraft && type == DayType.REQUESTED_OFF
                && (schedule.getKind() == ScheduleKind.EMPTY
                        || !hasApprovedDayOff(schedule, entry.getEmployee(), entry.getWorkDate()))) {
            type = DayType.OFF;
        }
        if (schedule.getKind() == ScheduleKind.MAIN) {
            if (type == DayType.UNSET) {
                throw new BusinessRuleException("Only a draft you build by hand can have empty days.");
            }
            // A made week is the manager's: they can change any day, an approved day off included (it's turned down,
            // so the request and the schedule agree). Choosing Off on an approved day keeps it as that day off.
            DayOffRequest onDay = dayOffRepository.findByEmployeeIdAndOffDate(entry.getEmployee().getId(),
                    entry.getWorkDate()).orElse(null);
            if (onDay != null && type != DayType.WORK && (wasTakenBack(onDay) || setAside(onDay))) {
                // The manager changed their mind: the day off they took back is theirs again.
                restoreTakenBack(onDay);
            }
            DayOffRequest approvedOff = onDay != null && onDay.getStatus() == DayOffStatus.APPROVED ? onDay : null;
            if (approvedOff != null && type != DayType.WORK) {
                type = DayType.REQUESTED_OFF;
            } else if (type == DayType.OFF || type == DayType.REQUESTED_OFF) {
                // A new day off the manager gives on the schedule: kept as a day off given by them, so the person
                // sees it in their days off too (not only on the schedule).
                // The schedule shows it as a plain Off, as before.
                giveOnSchedule(schedule, entry, onDay);
                type = DayType.OFF;
            }
            if (approvedOff != null && type == DayType.WORK) {
                // They work it now: the day off (asked for, regular or given by the system) is taken back, and they
                // are told (they can ask for it again as an emergency).
                takeBack(approvedOff, entry, null);
            }
        }
        // No day is locked: the manager sets any day as they like.
        if (type == DayType.UNSET && schedule.getKind() == ScheduleKind.MAIN) {
            throw new BusinessRuleException("Only a draft you build by hand can have empty days.");
        }
        entry.setType(type);
        if (type == DayType.UNSET) {
            entry.setStore(null);
            entry.setManuallyEdited(false);
            entry.setAutoAssigned(false);
            schedule.setUpdatedAt(Instant.now());
            return toResponse(schedule);
        }
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
        if (schedule.getKind() == ScheduleKind.MAIN && type != DayType.WORK) {
            // A new day off on a made week replaces one they had: they keep their usual number of days off.
            keepUsualDaysOff(schedule, entry);
        }
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * The manager gave {@code kept} as a day off on a made week. If the person now has more days off than they usually
     * get (1 for a 6-day worker, none for someone working the full week), other days off go back to work, and their
     * day-off requests are deleted: first a day the system picked, then their regular day, then a day they asked
     * for, then one a manager gave earlier. An approved emergency is never taken away.
     */
    private void keepUsualDaysOff(WeeklySchedule schedule, ScheduleEntry kept) {
        Employee person = kept.getEmployee();
        boolean fullWeek = fullWeeks.findForArea(schedule.getArea().getId(), schedule.getWeekStart()).stream()
                .anyMatch(f -> f.getEmployee().getId().equals(person.getId()));
        int usual = fullWeek ? 0 : Math.max(0, 7 - person.getWorkDaysPerWeek());
        List<ScheduleEntry> offs = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(person.getId()))
                .filter(e -> e.getType() == DayType.OFF || e.getType() == DayType.REQUESTED_OFF)
                .filter(e -> anyStoreOpen(schedule, e.getWorkDate()))
                .toList();
        int extra = offs.size() - usual;
        if (extra <= 0) {
            return;
        }
        Map<LocalDate, DayOffRequest> requests = new HashMap<>();
        offs.forEach(e -> dayOffRepository.findByEmployeeIdAndOffDate(person.getId(), e.getWorkDate())
                .ifPresent(r -> requests.put(e.getWorkDate(), r)));
        List<ScheduleEntry> canGo = offs.stream()
                .filter(e -> !e.getId().equals(kept.getId()))
                .filter(e -> requests.get(e.getWorkDate()) == null || !requests.get(e.getWorkDate()).isEmergency())
                .sorted(Comparator.<ScheduleEntry>comparingInt(e -> {
                    DayOffRequest r = requests.get(e.getWorkDate());
                    if (r == null) {
                        return 3; // a day off a manager gave earlier
                    }
                    return r.isReserved() && !r.isRegular() ? 0 : r.isRegular() ? 1 : 2;
                }).thenComparing(ScheduleEntry::getWorkDate))
                .toList();
        for (ScheduleEntry back : canGo) {
            if (extra <= 0) {
                break;
            }
            DayOffRequest r = requests.get(back.getWorkDate());
            if (r != null && r.getStatus() == DayOffStatus.APPROVED) {
                takeBack(r, back, kept.getWorkDate());
            } else if (r != null) {
                removeForSchedule(r, back);
            }
            Store home = homeStoreOf(person);
            back.setType(DayType.WORK);
            back.setStore(home != null && home.isOpenOn(back.getWorkDate()) ? home : null);
            back.setAutoAssigned(false);
            back.setManuallyEdited(true);
            extra--;
        }
    }

    /** Start of the reason on a day off a manager took back by changing the schedule. */
    public static final String TAKEN_BACK = "Changed on the schedule";

    /**
     * A manager made someone work a day they had off. If the person knew about that day off (one they asked for,
     * their regular day, or one already on a published schedule), it stays on record as turned down, with who and
     * when, and they're told: they can ask for it again as an emergency. A day the system only planned on a draft
     * they never saw is simply removed.
     *
     * @param newDayOff the day the manager gave them off instead, if any
     */
    private void takeBack(DayOffRequest r, ScheduleEntry entry, LocalDate newDayOff) {
        boolean known = !r.isReserved() || r.isRegular() || entry.getPublishedLabel() != null;
        if (!known) {
            removeForSchedule(r, entry);
            return;
        }
        String by = accessPolicy.current().fullName();
        Instant now = Instant.now();
        r.setPreviousStatus(r.getStatus());
        r.setStatus(DayOffStatus.REJECTED);
        r.setReviewedBy(by);
        r.setReviewedAt(now);
        r.setDecisionChangedBy(by);
        r.setDecisionChangedAt(now);
        r.setDecisionChangeReason(TAKEN_BACK + (newDayOff == null ? "." : ": off " + dayName(newDayOff) + " instead."));
        dayOffRepository.flush();
        notifier.dayOffTakenBack(r, newDayOff);
    }

    /**
     * Once at start-up: days off a manager gave on a made week (today on) before they were recorded, so they show in
     * the person's days off too. Days every store is closed, and days with any request already, are left alone.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void recordDaysGivenOnSchedule() {
        LocalDate today = LocalDate.now();
        int n = 0;
        for (WeeklySchedule s : scheduleRepository.findAll()) {
            if (s.getKind() != ScheduleKind.MAIN || s.getWeekEnd().isBefore(today)) {
                continue;
            }
            for (ScheduleEntry e : s.getEntries()) {
                if (e.getType() == DayType.OFF && !e.getWorkDate().isBefore(today)
                        && !dayOffRepository.existsByEmployeeIdAndOffDate(e.getEmployee().getId(), e.getWorkDate())
                        && giveOnSchedule(s, e, null, null)) {
                    n++;
                }
            }
        }
        if (n > 0) {
            org.slf4j.LoggerFactory.getLogger(ScheduleService.class)
                    .info("Recorded {} day(s) off given on the schedule before they were kept as days off", n);
        }
    }

    /** Reason on a day off a manager gave by changing the schedule (shown on the schedule as given by the manager). */
    public static final String GIVEN_ON_SCHEDULE = "Day off given by your manager";

    /**
     * The manager made a day a day off on a made week: it's recorded as an approved day off given by them (a
     * turned-down request that day becomes it). Days when every store is closed are just off, nothing to record.
     *
     * @return whether a day off is recorded
     */
    private boolean giveOnSchedule(WeeklySchedule schedule, ScheduleEntry entry, DayOffRequest onDay) {
        return giveOnSchedule(schedule, entry, onDay, accessPolicy.current().fullName());
    }

    private boolean giveOnSchedule(WeeklySchedule schedule, ScheduleEntry entry, DayOffRequest onDay, String by) {
        if (!anyStoreOpen(schedule, entry.getWorkDate())) {
            return false;
        }
        DayOffRequest r = onDay;
        if (r == null) {
            r = new DayOffRequest();
            r.setEmployee(entry.getEmployee());
            r.setOffDate(entry.getWorkDate());
        } else if (r.getStatus() == DayOffStatus.REJECTED) {
            // A day they were turned down for (or that was taken back earlier): now it's the manager's day off.
            r.setPreviousStatus(null);
            r.setDecisionChangedBy(null);
            r.setDecisionChangedAt(null);
            r.setDecisionChangeReason(null);
            r.setReserved(false);
            r.setRegular(false);
            r.setEmergency(false);
        }
        if (r.getId() == null || r.getStatus() == DayOffStatus.REJECTED) {
            r.setReason(GIVEN_ON_SCHEDULE);
        }
        r.setStatus(DayOffStatus.APPROVED);
        r.setReviewedBy(by);
        r.setReviewedAt(Instant.now());
        r.setReviewNote(by == null ? "Given on the schedule." : "Given by " + by + " on the schedule.");
        dayOffRepository.save(r);
        dayOffRepository.flush();
        return true;
    }

    /**
     * The week's schedule after a manual change (a swap, a drag, a store move): the person's day-off records follow
     * what the manager put there, as when one day is changed. Working on a day off takes it back; a new day off is
     * kept as given by the manager (or the one taken back earlier is theirs again). Drafts record nothing.
     */
    private void followOnRecords(WeeklySchedule schedule, ScheduleEntry e) {
        if (schedule.getKind() != ScheduleKind.MAIN) {
            settleDraftLabel(schedule, e);
            return;
        }
        DayOffRequest onDay = dayOffRepository.findByEmployeeIdAndOffDate(e.getEmployee().getId(), e.getWorkDate())
                .orElse(null);
        if (e.getType() == DayType.WORK) {
            if (onDay != null && onDay.getStatus() == DayOffStatus.APPROVED) {
                takeBack(onDay, e, null);
            }
            return;
        }
        if (onDay != null && (wasTakenBack(onDay) || setAside(onDay))) {
            restoreTakenBack(onDay);
            onDay = dayOffRepository.findByEmployeeIdAndOffDate(e.getEmployee().getId(), e.getWorkDate()).orElse(null);
            if (onDay == null || onDay.getStatus() != DayOffStatus.APPROVED) {
                e.setType(DayType.OFF);
                return;
            }
        } else if (onDay == null || onDay.getStatus() != DayOffStatus.APPROVED) {
            giveOnSchedule(schedule, e, onDay);
            e.setType(DayType.OFF);
            return;
        }
        e.setType(givenOnSchedule(onDay) ? DayType.OFF : DayType.REQUESTED_OFF);
    }

    /** On a draft, a moved day off reads "approved" only where this tab still has an approved day off. */
    private void settleDraftLabel(WeeklySchedule schedule, ScheduleEntry e) {
        if (e.getType() == DayType.REQUESTED_OFF && (schedule.getKind() == ScheduleKind.EMPTY
                || !hasApprovedDayOff(schedule, e.getEmployee(), e.getWorkDate()))) {
            e.setType(DayType.OFF);
        }
    }

    /** A day off a manager took back on the schedule (see {@link #takeBack}). */
    public static boolean wasTakenBack(DayOffRequest r) {
        return r.getStatus() == DayOffStatus.REJECTED && r.getPreviousStatus() == DayOffStatus.APPROVED
                && r.getDecisionChangeReason() != null && r.getDecisionChangeReason().startsWith(TAKEN_BACK);
    }

    /** Undoes {@link #takeBack}: the day off is approved again, as it was, and the person is told. */
    private void restoreTakenBack(DayOffRequest r) {
        boolean quiet = setAside(r);
        r.setStatus(r.getPreviousStatus() == null ? DayOffStatus.APPROVED : r.getPreviousStatus());
        r.setPreviousStatus(null);
        r.setDecisionChangedBy(null);
        r.setDecisionChangedAt(null);
        r.setDecisionChangeReason(null);
        dayOffRepository.flush();
        if (!quiet) {
            notifier.dayOffDecisionChanged(r, true, "Your manager changed the schedule back.");
        }
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
        DayType typeA = a.getType();
        Store storeA = a.getStore();
        a.setType(b.getType());
        a.setStore(b.getStore());
        b.setType(typeA);
        b.setStore(storeA);
        for (ScheduleEntry e : List.of(a, b)) {
            if (e.getType() == DayType.WORK && e.getStore() == null) {
                Store home = homeStoreOf(e.getEmployee());
                e.setStore(home != null && home.isOpenOn(e.getWorkDate()) ? home : null);
            }
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
            followOnRecords(schedule, e);
        }
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
        boolean ownDayOffMoves = schedule.getKind() == ScheduleKind.MAIN
                && from.getEmployee().getId().equals(to.getEmployee().getId()) && storeId == null
                && (from.getType() == DayType.WORK) != (to.getType() == DayType.WORK);
        if (ownDayOffMoves) {
            // On a made week the manager moves someone's day off (e.g. Monday to Tuesday): the old day's request (asked
            // for, regular or given by the system) is deleted, so they aren't off on both days.
            ScheduleEntry offDay = from.getType() == DayType.WORK ? to : from;
            ScheduleEntry newOffDay = offDay == from ? to : from;
            // The old day: taken back (they're told, with the new day), or simply removed if they never knew of it.
            dayOffRepository.findByEmployeeIdAndOffDate(offDay.getEmployee().getId(), offDay.getWorkDate())
                    .ifPresent(r -> {
                        if (r.getStatus() == DayOffStatus.APPROVED) {
                            takeBack(r, offDay, newOffDay.getWorkDate());
                        } else {
                            removeForSchedule(r, offDay);
                        }
                    });
            if (offDay.getType() == DayType.REQUESTED_OFF) {
                offDay.setType(DayType.OFF);
            }
            // The new day is their day off on their side too.
            giveOnSchedule(schedule, newOffDay, dayOffRepository
                    .findByEmployeeIdAndOffDate(newOffDay.getEmployee().getId(), newOffDay.getWorkDate()).orElse(null));
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
            followOnRecords(schedule, from);
            schedule.setUpdatedAt(Instant.now());
            return toResponse(schedule);
        }
        DayType toType = to.getType();
        Store toStore = to.getStore();
        Store newToStore = placeStore(schedule, to.getEmployee(), to.getWorkDate(), fromType, fromStore, storeId != null);
        Store newFromStore = placeStore(schedule, from.getEmployee(), from.getWorkDate(), toType, toStore, false);

        to.setType(fromType);
        to.setStore(newToStore);
        from.setType(toType);
        from.setStore(newFromStore);
        for (ScheduleEntry e : List.of(from, to)) {
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
            if (!ownDayOffMoves) {
                followOnRecords(schedule, e);
            }
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
            throw new BusinessRuleException((store == null ? "That store" : store.getName()) + " is closed that day.");
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
        // On a draft, blank days count too: dropping someone on a store places them there for the week.
        boolean draft = schedule.getKind() != ScheduleKind.MAIN;
        List<ScheduleEntry> workDays = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(employeeId))
                .filter(e -> e.getType() == DayType.WORK || (draft && e.getType() == DayType.UNSET))
                .toList();
        if (workDays.isEmpty()) {
            throw new BusinessRuleException("This employee has no work days on this schedule.");
        }
        // Days the store is closed keep their current assignment.
        List<ScheduleEntry> openDays = workDays.stream().filter(e -> store.isOpenOn(e.getWorkDate())).toList();
        if (openDays.isEmpty()) {
            throw new BusinessRuleException(store.getName() + " is closed on every day this employee works this week.");
        }
        // Blank days are only filled up to the days they work a week: the rest stay blank for their day off.
        boolean fullWeek = fullWeeks.findForArea(schedule.getArea().getId(), schedule.getWeekStart()).stream()
                .anyMatch(f -> f.getEmployee().getId().equals(employeeId));
        Employee person = workDays.getFirst().getEmployee();
        long working = schedule.getEntries().stream()
                .filter(e -> e.getEmployee().getId().equals(employeeId) && e.getType() == DayType.WORK).count();
        int target = fullWeek ? 7 : person.getWorkDaysPerWeek();
        for (ScheduleEntry e : openDays.stream().sorted(Comparator.comparing(ScheduleEntry::getWorkDate)).toList()) {
            if (e.getType() == DayType.UNSET) {
                if (working >= target) {
                    continue;
                }
                working++;
            }
            e.setType(DayType.WORK);
            e.setStore(store);
            e.setManuallyEdited(true);
            e.setAutoAssigned(false);
        }
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
        // Saving stores the week exactly as the manager left it. Only the days-off draft (still being planned) picks
        // up days off approved since; the empty draft and a generated week are the manager's.
        if (schedule.getKind() == ScheduleKind.DAYS_OFF) {
            ensureApprovedDaysOff(schedule);
        }
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
        if (entry.getType() != DayType.WORK) {
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
        if (schedule.getKind() != ScheduleKind.MAIN) {
            return publishDraft(schedule);
        }
        requireDraft(schedule);
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
        if (schedule.getKind() == ScheduleKind.SYSTEM) {
            // "By system" would build itself again on the next visit: keep it empty until "Build now".
            schedule.getEntries().clear();
            schedule.setDraftSavedAt(null);
            schedule.setStatus(ScheduleStatus.DRAFT);
            schedule.setSourceHash(SYSTEM_DELETED);
            schedule.setUpdatedAt(Instant.now());
            scheduleRepository.flush();
            return;
        }
        scheduleRepository.delete(schedule);
        scheduleRepository.flush();
    }

    /** {@link WeeklySchedule#getSourceHash()} of a "By system" tab the manager deleted. */
    static final String SYSTEM_DELETED = "deleted";

    @Transactional
    public ScheduleResponse revertToDraft(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        schedule.setStatus(ScheduleStatus.DRAFT);
        schedule.setPublishedAt(null);
        return toResponse(schedule);
    }

    /** The week's schedule has been generated (draft or published): from then on it's the manager's. */
    @Transactional(readOnly = true)
    public boolean weekMade(Long areaId, LocalDate anyDayInWeek) {
        return areaId != null
                && scheduleRepository.findByAreaIdAndWeekStart(areaId, DayOffService.weekStart(anyDayInWeek)).isPresent();
    }

    // ---------- Drafts built by hand ----------

    /** The week's draft of that kind (managers and admins), or empty when it hasn't been started. */
    @Transactional
    public Optional<ScheduleResponse> findDraft(Long areaId, LocalDate anyDayInWeek, ScheduleKind kind) {
        accessPolicy.requireManagerOf(areaId);
        return scheduleRepository.findByAreaIdAndWeekStartAndKind(areaId, DayOffService.weekStart(anyDayInWeek), kind)
                .map(s -> {
                    if (s.getKind() != ScheduleKind.MAIN) {
                        unlockRevokedDaysOff(s);
                    }
                    // The empty draft stays as the manager left it: approved requests are only a hint there.
                    if (s.getKind() != ScheduleKind.EMPTY) {
                        ensureApprovedDaysOff(s);
                    }
                    return toResponse(s);
                });
    }

    /** The week's schedules (main and drafts), for the tabs. */
    @Transactional(readOnly = true)
    public List<ScheduleDtos.DraftSummary> drafts(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        return scheduleRepository.findByAreaIdAndWeekStartOrderByKindAsc(areaId, DayOffService.weekStart(anyDayInWeek))
                .stream()
                .filter(s -> !SYSTEM_DELETED.equals(s.getSourceHash()))
                .map(s -> new ScheduleDtos.DraftSummary(s.getId(), s.getKind(), s.getStatus(),
                        (int) s.getEntries().stream().filter(e -> e.getType() == DayType.UNSET).count(),
                        s.getUpdatedAt(), s.getDraftSavedAt() != null))
                .toList();
    }

    /**
     * Starts (or starts over) a draft built by hand. {@link ScheduleKind#EMPTY}: every day blank; the manager gives the
     * days off (approved requests show as a hint) and the system can fill the rest. {@link ScheduleKind#DAYS_OFF}:
     * approved days off (locked), days every store is closed and each person's regular day off are filled in. The main
     * schedule isn't touched.
     */
    @Transactional
    public ScheduleResponse createDraft(Long areaId, LocalDate anyDayInWeek, ScheduleKind kind) {
        if (kind == null || kind == ScheduleKind.MAIN) {
            throw new BusinessRuleException("Pick the empty draft or the days-off draft.");
        }
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
        List<Employee> staff = employeeRepository
                .findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES).stream()
                .filter(Employee::worksShifts).toList();
        if (staff.isEmpty()) {
            throw new BusinessRuleException("Area '" + area.getName() + "' has no active staff to schedule.");
        }
        WeeklySchedule draft = scheduleRepository.findByAreaIdAndWeekStartAndKind(areaId, weekStart, kind)
                .orElseGet(() -> {
                    WeeklySchedule s = new WeeklySchedule();
                    s.setArea(area);
                    s.setWeekStart(weekStart);
                    s.setKind(kind);
                    return scheduleRepository.save(s);
                });
        draft.getEntries().clear();
        draft.setDraftSavedAt(null);
        draft.setStatus(ScheduleStatus.DRAFT);
        scheduleRepository.flush();

        Map<Long, Set<LocalDate>> approved = byPerson(offKeys(kind, areaId, weekStart, draft.getWeekEnd()));
        // Days the system holds for people (their regular day, or a day it picked): not approved yet, but planned.
        Map<Long, Set<LocalDate>> held = byPerson(heldKeys(kind, areaId, weekStart, draft.getWeekEnd()));
        Set<Long> fullWeek = fullWeeks.findForArea(areaId, weekStart).stream()
                .map(f -> f.getEmployee().getId()).collect(Collectors.toSet());
        List<Store> open = storeRepository.findByAreaIdOrderByNameAsc(areaId).stream().filter(Store::isActive).toList();
        for (Employee e : staff) {
            for (LocalDate date : weekDates(draft)) {
                DayType type = DayType.UNSET;
                if (kind == ScheduleKind.EMPTY) {
                    // Totally empty: the manager gives the days off, the system can do the rest.
                } else if (!open.isEmpty() && open.stream().noneMatch(st -> st.isOpenOn(date))) {
                    type = DayType.OFF;
                } else if (approved.getOrDefault(e.getId(), Set.of()).contains(date)) {
                    type = DayType.REQUESTED_OFF;
                } else if (kind == ScheduleKind.DAYS_OFF && !fullWeek.contains(e.getId())
                        && held.getOrDefault(e.getId(), Set.of()).contains(date)) {
                    type = DayType.OFF;
                } else if (kind == ScheduleKind.DAYS_OFF && !fullWeek.contains(e.getId())
                        && e.getRegularDaysOff().contains(date.getDayOfWeek())
                        && approved.getOrDefault(e.getId(), Set.of()).size()
                                + held.getOrDefault(e.getId(), Set.of()).size() < Math.max(0, 7 - e.getWorkDaysPerWeek())) {
                    // Their regular day, unless they already have their days off this week (e.g. they swapped it).
                    type = DayType.OFF;
                }
                draft.getEntries().add(new ScheduleEntry(draft, e, date, type, null));
            }
        }
        draft.setUpdatedAt(Instant.now());
        scheduleRepository.flush();
        return toResponse(draft);
    }

    /**
     * Lets the system finish a draft built by hand, by the same rules as Generate. What the manager already decided
     * stays. Each person gets the days off their week still needs (their usual number, or none for a full week) on
     * blank days: first days the stores can spare them, then their regular day or the day the system planned for
     * them, then the day with the most people
     * spare, avoiding two people of the same store being off together. Every other blank day becomes a work day, and
     * stores are filled (primaries at their store, secondaries covering, floaters only at their stores).
     */
    @Transactional
    public ScheduleResponse fillBlanks(Long scheduleId) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        unlockRevokedDaysOff(schedule);
        Long areaId = schedule.getArea().getId();
        // The empty draft is the manager's: the days off they gave come first. An approved request (asked for, or
        // given by the system earlier) is only used when the person still needs a day off; it never adds an extra one.
        Set<String> approvedOff = new HashSet<>();
        if (schedule.getKind() == ScheduleKind.EMPTY) {
            approvedOff.addAll(offKeys(schedule));
        } else {
            ensureApprovedDaysOff(schedule);
        }
        List<LocalDate> dates = weekDates(schedule);
        List<Store> areaStores = storeRepository.findByAreaIdOrderByNameAsc(areaId);
        List<Store> active = areaStores.stream().filter(Store::isActive).toList();
        Set<LocalDate> openDays = dates.stream()
                .filter(d -> active.isEmpty() || active.stream().anyMatch(st -> st.isOpenOn(d)))
                .collect(Collectors.toSet());
        Set<Long> fullWeek = fullWeeks.findForArea(areaId, schedule.getWeekStart()).stream()
                .map(f -> f.getEmployee().getId()).collect(Collectors.toSet());

        Map<Long, List<ScheduleEntry>> byEmployee = new LinkedHashMap<>();
        Map<LocalDate, Set<Long>> off = new HashMap<>();
        dates.forEach(d -> off.put(d, new HashSet<>()));
        for (ScheduleEntry e : schedule.getEntries()) {
            byEmployee.computeIfAbsent(e.getEmployee().getId(), k -> new ArrayList<>()).add(e);
            if (e.getType() == DayType.UNSET && !openDays.contains(e.getWorkDate())) {
                e.setType(DayType.OFF); // Every store is closed: nobody works.
            }
            if (e.getType() == DayType.OFF || e.getType() == DayType.REQUESTED_OFF) {
                off.get(e.getWorkDate()).add(e.getEmployee().getId());
            }
        }
        Coverage coverage = Coverage.of(areaStores,
                byEmployee.values().stream().map(l -> l.getFirst().getEmployee()).toList());
        // The days the system already planned as their day off (regular or picked), still waiting: tried first.
        Set<String> planned = new HashSet<>();
        for (LocalDate d : dates) {
            dayOffRepository.findForAreaOnDate(areaId, d, List.of(DayOffStatus.PENDING)).stream()
                    .filter(DayOffRequest::isReserved)
                    .forEach(r -> planned.add(r.getEmployee().getId() + "|" + d));
        }

        // People with the fewest blank days choose first: they have the least room.
        List<List<ScheduleEntry>> people = byEmployee.values().stream()
                .sorted(Comparator.comparingInt(l -> (int) l.stream().filter(e -> e.getType() == DayType.UNSET).count()))
                .toList();
        // Day-off draft: floaters are placed by hand, never by the system.
        boolean floatersByHand = schedule.getKind() == ScheduleKind.DAYS_OFF;
        for (List<ScheduleEntry> week : people) {
            Employee person = week.getFirst().getEmployee();
            if (floatersByHand && person.getStoreRole() == com.example.schedulingsoftware.employee.StoreRole.FLOATER) {
                continue;
            }
            int target = Math.min(fullWeek.contains(person.getId()) ? 7 : person.getWorkDaysPerWeek(), openDays.size());
            long offAlready = week.stream().filter(e -> openDays.contains(e.getWorkDate())
                    && (e.getType() == DayType.OFF || e.getType() == DayType.REQUESTED_OFF)).count();
            long need = openDays.size() - target - offAlready;
            for (int n = 0; n < need; n++) {
                ScheduleEntry pick = week.stream()
                        .filter(e -> e.getType() == DayType.UNSET)
                        .min(Comparator.<ScheduleEntry>comparingInt(e ->
                                        approvedOff.contains(person.getId() + "|" + e.getWorkDate()) ? 0 : 1)
                                .thenComparingInt(e ->
                                        coverage.canBeOff(e.getWorkDate(), off.get(e.getWorkDate()), person.getId()) ? 0 : 1)
                                .thenComparingInt(e -> sameStoreOff(person, off.get(e.getWorkDate()), byEmployee) ? 1 : 0)
                                .thenComparingInt(e -> person.getRegularDaysOff().contains(e.getWorkDate().getDayOfWeek())
                                        || planned.contains(person.getId() + "|" + e.getWorkDate()) ? 0 : 1)
                                .thenComparingInt(e -> -spareWith(coverage, e.getWorkDate(), off.get(e.getWorkDate()), person.getId()))
                                .thenComparing(ScheduleEntry::getWorkDate))
                        .orElse(null);
                if (pick == null) {
                    break;
                }
                // Their approved day off shows as that day off; any other is a plain day off.
                pick.setType(approvedOff.contains(person.getId() + "|" + pick.getWorkDate()) ? DayType.REQUESTED_OFF
                        : DayType.OFF);
                pick.setStore(null);
                off.get(pick.getWorkDate()).add(person.getId());
            }
        }
        for (ScheduleEntry e : schedule.getEntries()) {
            if (e.getType() == DayType.UNSET && !(floatersByHand
                    && e.getEmployee().getStoreRole() == com.example.schedulingsoftware.employee.StoreRole.FLOATER)) {
                e.setType(DayType.WORK);
                e.setStore(null);
                e.setAutoAssigned(false);
                e.setManuallyEdited(false);
            }
        }
        assignStores(schedule);
        schedule.setUpdatedAt(Instant.now());
        scheduleRepository.flush();
        return toResponse(schedule);
    }

    /**
     * Day-off draft: "Fill primaries" / "Fill secondaries". Each primary (or secondary) works at their home store on their
     * blank days, up to the days they work a week (the rest stay blank for their day off). Floaters are placed by hand.
     */
    @Transactional
    public ScheduleResponse fillRole(Long scheduleId, com.example.schedulingsoftware.employee.StoreRole role) {
        WeeklySchedule schedule = getManaged(scheduleId);
        requireDraft(schedule);
        if (role == null || role == com.example.schedulingsoftware.employee.StoreRole.FLOATER) {
            throw new BusinessRuleException("Place floaters by hand.");
        }
        Set<Long> fullWeek = fullWeeks.findForArea(schedule.getArea().getId(), schedule.getWeekStart()).stream()
                .map(f -> f.getEmployee().getId()).collect(Collectors.toSet());
        Map<Long, List<ScheduleEntry>> byEmployee = new LinkedHashMap<>();
        schedule.getEntries().forEach(e -> byEmployee.computeIfAbsent(e.getEmployee().getId(), k -> new ArrayList<>()).add(e));
        int placed = 0;
        for (List<ScheduleEntry> week : byEmployee.values()) {
            Employee person = week.getFirst().getEmployee();
            Store home = homeStoreOf(person);
            if (person.getStoreRole() != role || home == null) {
                continue;
            }
            int target = fullWeek.contains(person.getId()) ? 7 : person.getWorkDaysPerWeek();
            long working = week.stream().filter(e -> e.getType() == DayType.WORK).count();
            for (ScheduleEntry e : week.stream().sorted(Comparator.comparing(ScheduleEntry::getWorkDate)).toList()) {
                if (working >= target) {
                    break;
                }
                if (e.getType() == DayType.UNSET && home.isOpenOn(e.getWorkDate())) {
                    e.setType(DayType.WORK);
                    e.setStore(home);
                    e.setAutoAssigned(false);
                    e.setManuallyEdited(false);
                    working++;
                    placed++;
                }
            }
        }
        if (placed == 0) {
            throw new BusinessRuleException("Nothing to fill: every " + role.name().toLowerCase() + " is placed.");
        }
        schedule.setUpdatedAt(Instant.now());
        return toResponse(schedule);
    }

    /**
     * Tab "Schedule by System": built by the system from the week's days off, staff and stores. While nobody has changed
     * it (and it isn't published) it's built again whenever those change, so it's always current. Once a manager or
     * admin changes a day, it stays exactly as they left it. Past weeks are never built.
     */
    @Transactional
    public Optional<ScheduleResponse> systemSchedule(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        Optional<WeeklySchedule> found = scheduleRepository.findByAreaIdAndWeekStartAndKind(areaId, week,
                ScheduleKind.SYSTEM);
        boolean past = week.plusDays(6).isBefore(LocalDate.now());
        if (found.isPresent()) {
            WeeklySchedule s = found.get();
            if (SYSTEM_DELETED.equals(s.getSourceHash())) {
                return Optional.empty();
            }
            // Changed, saved or published by a manager: it stays exactly as they left it.
            boolean touched = s.getStatus() == ScheduleStatus.PUBLISHED || s.getDraftSavedAt() != null
                    || s.getEntries().stream().anyMatch(ScheduleEntry::isManuallyEdited);
            if (touched || past || Objects.equals(s.getSourceHash(), sourceHash(areaId, week))) {
                return findDraft(areaId, week, ScheduleKind.SYSTEM);
            }
        } else if (past) {
            return Optional.empty();
        }
        try {
            return Optional.of(buildSystem(areaId, week));
        } catch (BusinessRuleException nothingToBuild) {
            // e.g. no active staff yet: nothing to show.
            return Optional.empty();
        }
    }

    /** "Rebuild": throws away changes made on "Schedule by System" and lets the system build the week again. */
    @Transactional
    public ScheduleResponse rebuildSystem(Long areaId, LocalDate anyDayInWeek) {
        accessPolicy.requireManagerOf(areaId);
        return buildSystem(areaId, DayOffService.weekStart(anyDayInWeek));
    }

    private ScheduleResponse buildSystem(Long areaId, LocalDate week) {
        ScheduleResponse draft = createDraft(areaId, week, ScheduleKind.SYSTEM);
        fillBlanks(draft.id());
        WeeklySchedule s = scheduleRepository.findById(draft.id()).orElseThrow();
        for (ScheduleEntry e : s.getEntries()) {
            e.setSystemLabel(com.example.schedulingsoftware.notify.ScheduleNotifier.label(e));
            e.setManuallyEdited(false);
        }
        s.setSourceHash(sourceHash(areaId, week));
        s.setUpdatedAt(Instant.now());
        scheduleRepository.flush();
        return toResponse(s);
    }

    /** What "Schedule by System" is built from: days off (approved, and the days the system holds), staff and stores. */
    private String sourceHash(Long areaId, LocalDate week) {
        StringBuilder b = new StringBuilder();
        offKeys(ScheduleKind.SYSTEM, areaId, week, week.plusDays(6)).stream().map(k -> "A" + k).sorted()
                .forEach(b::append);
        heldKeys(ScheduleKind.SYSTEM, areaId, week, week.plusDays(6)).stream().map(k -> "H" + k).sorted()
                .forEach(b::append);
        employeeRepository.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, SCHEDULED_ROLES)
                .stream().filter(Employee::worksShifts)
                .map(e -> "E" + e.getId() + ":" + e.getWorkDaysPerWeek() + ":" + e.getStoreRole() + ":"
                        + (homeStoreOf(e) == null ? "" : homeStoreOf(e).getId()) + ":" + new java.util.TreeSet<>(e.floatStoreIds())
                        + ":" + new java.util.TreeSet<>(e.getRegularDaysOff()))
                .sorted().forEach(b::append);
        storeRepository.findByAreaIdOrderByNameAsc(areaId).stream()
                .map(st -> "S" + st.getId() + ":" + st.isActive() + ":"
                        + week.datesUntil(week.plusDays(7)).map(d -> String.valueOf(st.minStaffOn(d))).toList())
                .sorted().forEach(b::append);
        fullWeeks.findForArea(areaId, week).stream().map(f -> "F" + f.getEmployee().getId()).sorted().forEach(b::append);
        return Integer.toHexString(b.toString().hashCode()) + Integer.toHexString(b.length());
    }

    /** "Schedule by System": what the system had for a day a manager or admin changed (null when unchanged). */
    private static String changedFromSystem(WeeklySchedule schedule, ScheduleEntry e) {
        if (schedule.getKind() != ScheduleKind.SYSTEM || e.getSystemLabel() == null || !e.isManuallyEdited()) {
            return null;
        }
        String now = com.example.schedulingsoftware.notify.ScheduleNotifier.label(e);
        return e.getSystemLabel().equals(now) ? null : e.getSystemLabel();
    }

    /** People working that day beyond what the stores need, if this person were off too. */
    private static int spareWith(Coverage coverage, LocalDate date, Set<Long> off, Long employeeId) {
        Set<Long> more = new HashSet<>(off);
        more.add(employeeId);
        return coverage.day(date, more).spare();
    }

    /** Someone else from this person's home store (not a floater) is already off that day. */
    private static boolean sameStoreOff(Employee person, Set<Long> off, Map<Long, List<ScheduleEntry>> byEmployee) {
        Store home = homeStoreOf(person);
        if (home == null) {
            return false;
        }
        return off.stream().filter(id -> !id.equals(person.getId())).map(byEmployee::get)
                .filter(java.util.Objects::nonNull)
                .map(l -> homeStoreOf(l.getFirst().getEmployee()))
                .anyMatch(st -> st != null && st.getId().equals(home.getId()));
    }

    /** A day off that was revoked after the draft was made goes back to blank (it was never decided in the draft). */
    private void unlockRevokedDaysOff(WeeklySchedule draft) {
        Set<String> approved = offKeys(draft);
        for (ScheduleEntry e : draft.getEntries()) {
            if (e.getType() == DayType.REQUESTED_OFF && !approved.contains(e.getEmployee().getId() + "|" + e.getWorkDate())) {
                e.setType(DayType.UNSET);
                e.setStore(null);
            }
        }
    }

    /**
     * Publishes a draft built by hand: it becomes the week's schedule. Every day must be decided first. The main
     * schedule takes its days (made if there's none; a published one is updated, so only people whose days changed
     * hear about it), the draft stays as it is, and the days the system was holding for people that week are settled:
     * given where they're off, dropped where they work.
     */
    private ScheduleResponse publishDraft(WeeklySchedule draft) {
        requireDraft(draft);
        unlockRevokedDaysOff(draft);
        if (draft.getKind() != ScheduleKind.EMPTY) {
            ensureApprovedDaysOff(draft);
        }
        long blank = draft.getEntries().stream().filter(e -> e.getType() == DayType.UNSET).count();
        if (blank > 0) {
            throw new BusinessRuleException(blank + (blank == 1 ? " day is" : " days are") + " still empty. Press \"Fill the rest\".");
        }
        Long areaId = draft.getArea().getId();
        {
            // The manager scheduled someone to work on a day they had off: that day off is deleted, so the published
            // week keeps the manager's decision (they see the change with their week).
            Map<String, DayType> placed = new HashMap<>();
            draft.getEntries().forEach(d -> placed.put(d.getEmployee().getId() + "|" + d.getWorkDate(), d.getType()));
            Map<String, ScheduleEntry> draftEntries = new HashMap<>();
            draft.getEntries().forEach(d -> draftEntries.put(d.getEmployee().getId() + "|" + d.getWorkDate(), d));
            for (DayOffRequest r : dayOffRepository.findForArea(areaId, DayOffStatus.APPROVED, draft.getWeekStart(),
                    draft.getWeekEnd())) {
                String key = r.getEmployee().getId() + "|" + r.getOffDate();
                if (placed.get(key) == DayType.WORK) {
                    // Kept on record as changed by the manager, and they're told (as on the system schedule).
                    takeBack(r, draftEntries.get(key), null);
                }
            }
            dayOffRepository.flush();
        }
        WeeklySchedule main = scheduleRepository.findByAreaIdAndWeekStart(areaId, draft.getWeekStart())
                .orElseGet(() -> {
                    WeeklySchedule s = new WeeklySchedule();
                    s.setArea(draft.getArea());
                    s.setWeekStart(draft.getWeekStart());
                    return scheduleRepository.save(s);
                });
        Map<String, ScheduleEntry> current = new HashMap<>();
        main.getEntries().forEach(e -> current.put(e.getEmployee().getId() + "|" + e.getWorkDate(), e));
        Set<String> keep = new HashSet<>();
        for (ScheduleEntry d : draft.getEntries()) {
            String key = d.getEmployee().getId() + "|" + d.getWorkDate();
            keep.add(key);
            ScheduleEntry m = current.get(key);
            if (m == null) {
                m = new ScheduleEntry(main, d.getEmployee(), d.getWorkDate(), d.getType(), d.getStore());
                main.getEntries().add(m);
            }
            m.setType(d.getType());
            m.setStore(d.getType() == DayType.WORK ? d.getStore() : null);
            m.setAutoAssigned(d.isAutoAssigned());
            m.setManuallyEdited(d.isManuallyEdited());
        }
        main.getEntries().removeIf(m -> !keep.contains(m.getEmployee().getId() + "|" + m.getWorkDate()));
        // Days the system was holding: given where the published week has them off, dropped where they work.
        Map<String, DayType> types = new HashMap<>();
        draft.getEntries().forEach(d -> types.put(d.getEmployee().getId() + "|" + d.getWorkDate(), d.getType()));
        Instant now = Instant.now();
        for (LocalDate date : weekDates(draft)) {
            for (DayOffRequest r : dayOffRepository.findForAreaOnDate(areaId, date, List.of(DayOffStatus.PENDING))) {
                if (!r.isReserved()) {
                    continue;
                }
                DayType t = types.get(r.getEmployee().getId() + "|" + date);
                if (t == DayType.OFF || t == DayType.REQUESTED_OFF) {
                    r.setStatus(DayOffStatus.APPROVED);
                    r.setReviewNote(r.isRegular() ? "Regular day off, given by the system." : "Given by the system.");
                    r.setReviewedAt(now);
                    r.setReviewedBy(DayOffRequest.SYSTEM);
                } else {
                    dayOffRepository.delete(r);
                }
            }
        }
        dayOffRepository.flush();
        // Days the manager gave off on the draft: recorded as days off given by them, so each person sees them in
        // their days off too (a day with an emergency still waiting stays for a manager to decide).
        String by = accessPolicy.current().fullName();
        for (ScheduleEntry d : draft.getEntries()) {
            if (d.getType() != DayType.OFF && d.getType() != DayType.REQUESTED_OFF) {
                continue;
            }
            DayOffRequest onDay = dayOffRepository.findByEmployeeIdAndOffDate(d.getEmployee().getId(), d.getWorkDate())
                    .orElse(null);
            if (onDay == null || onDay.getStatus() == DayOffStatus.REJECTED) {
                giveOnSchedule(draft, d, onDay, by);
            }
        }
        main.setStatus(ScheduleStatus.DRAFT);
        main.setPublishedAt(null);
        main.setUpdatedAt(now);
        // The draft stays on its tab as published (a save point), and isn't rebuilt behind the manager's back.
        draft.markDraftSaved();
        scheduleRepository.flush();
        org.slf4j.LoggerFactory.getLogger(ScheduleService.class).info("{} published the {} draft of {} for the week of {}",
                accessPolicy.current().email(), draft.getKind(), draft.getArea().getName(), draft.getWeekStart());
        return publish(main.getId());
    }

    // ---------- Approved days off ----------

    /**
     * Puts a just-approved day off onto that week's schedule if the week was already made (draft or published), so
     * nobody stays scheduled to work on a day they were given off. Returns a sentence describing the change, or null
     * if there was nothing to change.
     */
    @Transactional
    public String applyApprovedDayOff(Employee employee, LocalDate date) {
        return applyApprovedDayOff(employee, date, false);
    }

    /**
     * @param byManager a manager or admin decided on this request just now. Only then does a schedule that's already
     *                  made change: once made, the week is theirs, and the system doesn't change it by itself.
     */
    @Transactional
    public String applyApprovedDayOff(Employee employee, LocalDate date, boolean byManager) {
        if (employee.getArea() == null || !byManager) {
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
        return revokeApprovedDayOff(employee, date, false);
    }

    /** @param byManager see {@link #applyApprovedDayOff(Employee, LocalDate, boolean)} */
    @Transactional
    public String revokeApprovedDayOff(Employee employee, LocalDate date, boolean byManager) {
        if (employee.getArea() == null || !byManager) {
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
        return (r.role() == Role.MANAGER || r.role() == Role.MARKET_MANAGER) ? 0 : r.trainee() ? 2 : 1;
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
        boolean weekPublished = scheduleRepository.findByAreaIdAndWeekStart(areaId, DayOffService.weekStart(date))
                .map(w -> w.getStatus() == ScheduleStatus.PUBLISHED).orElse(false);
        // People whose day off that date is only the system's pick: it moves if this person takes the day.
        Set<Long> canMove = new HashSet<>();
        for (DayOffRequest r : dayOffRepository.findForAreaOnDate(areaId, date, openStatuses)) {
            Employee e = r.getEmployee();
            if (reservedCanMove && r.isMovable(weekPublished)) {
                canMove.add(e.getId());
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
            // (A day off the schedule shows only because the system picked it doesn't count: it can move.)
            day.stream().filter(en -> en.getType() != DayType.WORK && !en.getEmployee().getId().equals(employee.getId()))
                    .filter(en -> !canMove.contains(en.getEmployee().getId()))
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
            summary = "Already off on " + day + " · no one affected.";
        } else if (!newShortages.isEmpty()) {
            level = ImpactLevel.SHORT;
            Shortage first = newShortages.getFirst();
            summary = first.storeName() + " drops to " + first.scheduled() + " of " + first.required() + " on "
                    + dayName(first.date()) + (newShortages.size() > 1 ? " · +" + (newShortages.size() - 1) + " more" : "") + ".";
        } else if (alreadyShortThatDay) {
            level = ImpactLevel.TIGHT;
            summary = day + " is short either way · " + workingAfter + " of " + needed + ".";
        } else if (workingAfter <= needed) {
            level = ImpactLevel.TIGHT;
            summary = "Covered on " + day + " · none to spare.";
        } else {
            level = ImpactLevel.OK;
            summary = workingAfter + " working on " + day + " · " + needed + " needed.";
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

    private boolean hasApprovedDayOff(Employee employee, LocalDate date) {
        return dayOffRepository.existsByEmployeeIdAndOffDateAndStatus(employee.getId(), date, DayOffStatus.APPROVED);
    }

    /** Approved off that day, as this tab sees it (see {@link #approvedFor}). */
    private boolean hasApprovedDayOff(WeeklySchedule schedule, Employee employee, LocalDate date) {
        if (schedule.getKind() == ScheduleKind.MAIN) {
            return hasApprovedDayOff(employee, date);
        }
        return dayOffRepository.findByEmployeeIdAndOffDate(employee.getId(), date).map(ScheduleService::countsOnDrafts)
                .orElse(false);
    }

    /** A day off a manager gave by changing a schedule (not asked for, nor approved on the day-off page). */
    public static boolean givenOnSchedule(DayOffRequest r) {
        return GIVEN_ON_SCHEDULE.equals(r.getReason());
    }

    /**
     * Whether a day off counts on the tabs other than the week's schedule. Each tab keeps its own manual changes, so
     * a day off a manager gave or took back by changing one schedule doesn't change the others: days given that way
     * are left out, and days taken back that way still count as they were.
     */
    static boolean countsOnDrafts(DayOffRequest r) {
        return (r.getStatus() == DayOffStatus.APPROVED && !givenOnSchedule(r)) || wasTakenBack(r);
    }

    /** The week's approved days off, as a tab sees them: all of them on the schedule, real decisions only on drafts. */
    private List<DayOffRequest> approvedFor(ScheduleKind kind, Long areaId, LocalDate from, LocalDate to) {
        if (kind == ScheduleKind.MAIN) {
            return dayOffRepository.findForArea(areaId, DayOffStatus.APPROVED, from, to);
        }
        List<DayOffRequest> all = new ArrayList<>(dayOffRepository.findForArea(areaId, DayOffStatus.APPROVED, from, to));
        all.addAll(dayOffRepository.findForArea(areaId, DayOffStatus.REJECTED, from, to));
        return all.stream().filter(ScheduleService::countsOnDrafts).toList();
    }

    private List<DayOffRequest> approvedFor(WeeklySchedule s) {
        return approvedFor(s.getKind(), s.getArea().getId(), s.getWeekStart(), s.getWeekEnd());
    }

    private static String key(Long employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    /** "employee|date" of every approved day off, as a tab sees it. */
    private Set<String> offKeys(ScheduleKind kind, Long areaId, LocalDate from, LocalDate to) {
        Set<String> keys = approvedFor(kind, areaId, from, to).stream()
                .map(r -> key(r.getEmployee().getId(), r.getOffDate())).collect(Collectors.toCollection(HashSet::new));
        return keys;
    }

    private Set<String> offKeys(WeeklySchedule s) {
        return offKeys(s.getKind(), s.getArea().getId(), s.getWeekStart(), s.getWeekEnd());
    }

    /** "employee|date" of every day the system holds for someone, as a tab sees it. */
    private Set<String> heldKeys(ScheduleKind kind, Long areaId, LocalDate from, LocalDate to) {
        Set<String> keys = dayOffRepository.findForArea(areaId, DayOffStatus.PENDING, from, to).stream()
                .filter(DayOffRequest::isReserved)
                .map(r -> key(r.getEmployee().getId(), r.getOffDate())).collect(Collectors.toCollection(HashSet::new));
        if (kind != ScheduleKind.MAIN) {
            // Held days a change on the week's schedule set aside still count on the other tabs.
            dayOffRepository.findForArea(areaId, DayOffStatus.REJECTED, from, to).stream()
                    .filter(r -> setAside(r) && r.getPreviousStatus() == DayOffStatus.PENDING)
                    .forEach(r -> keys.add(key(r.getEmployee().getId(), r.getOffDate())));
        }
        return keys;
    }

    private static Map<Long, Set<LocalDate>> byPerson(Set<String> keys) {
        Map<Long, Set<LocalDate>> m = new HashMap<>();
        for (String k : keys) {
            String[] p = k.split("\\|");
            m.computeIfAbsent(Long.valueOf(p[0]), x -> new HashSet<>()).add(LocalDate.parse(p[1]));
        }
        return m;
    }

    /** Reason on a planned day off a manual change on the week's schedule set aside (nobody was told of it). */
    public static final String SET_ASIDE = TAKEN_BACK + " (planned day, set aside)";

    public static boolean setAside(DayOffRequest r) {
        return r.getStatus() == DayOffStatus.REJECTED && SET_ASIDE.equals(r.getDecisionChangeReason());
    }

    /**
     * A manual change on the week's schedule drops a day off nobody knew of (one the system planned, or held). It's set
     * aside quietly, not deleted: the other tabs keep counting it, and Reset gives it back exactly. Hidden from lists.
     * A request someone asked for themselves and that is still waiting is simply withdrawn, as before.
     */
    private void removeForSchedule(DayOffRequest r, ScheduleEntry entry) {
        boolean planned = r.getStatus() == DayOffStatus.APPROVED
                || (r.getStatus() == DayOffStatus.PENDING && r.isReserved());
        if (!planned) {
            dayOffRepository.delete(r);
            dayOffRepository.flush();
            return;
        }
        r.setPreviousStatus(r.getStatus());
        r.setStatus(DayOffStatus.REJECTED);
        r.setDecisionChangedBy(accessPolicy.current().fullName());
        r.setDecisionChangedAt(Instant.now());
        r.setDecisionChangeReason(SET_ASIDE);
        dayOffRepository.flush();
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
        if (schedule.getKind() != ScheduleKind.MAIN && target.isManuallyEdited()) {
            // A draft day the manager set by hand is theirs: a day off approved since doesn't overwrite it.
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
            throw new BusinessRuleException(store.closureOn(date)
                    .map(c -> store.getName() + " is closed that day" + (c.getLabel() == null ? "." : " (" + c.getLabel() + ")."))
                    .orElse(store.getName() + " is closed on "
                            + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US) + "s."));
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
        for (DayOffRequest r : approvedFor(schedule)) {
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
                                    x.getStore() == null ? null : x.getStore().hoursOn(x.getWorkDate()).getOpensAt(),
                                    x.getStore() == null ? null : x.getStore().hoursOn(x.getWorkDate()).getClosesAt(),
                                    x.getStore() != null && !x.getStore().isOpenOn(x.getWorkDate()),
                                    plainDaysOff ? null : requestByDay.get(x.getEmployee().getId() + "|" + x.getWorkDate()),
                                    x.getType() == DayType.REQUESTED_OFF || schedule.getKind() == ScheduleKind.EMPTY
                                            ? approvedByDay.get(x.getEmployee().getId() + "|" + x.getWorkDate())
                                            : null,
                                    x.getType() == DayType.REQUESTED_OFF && !plainDaysOff
                                            ? sourceByDay.getOrDefault(x.getEmployee().getId() + "|" + x.getWorkDate(),
                                                    "REQUESTED")
                                            : null,
                                    changedFromSystem(schedule, x)))
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
                requests,
                schedule.getKind(),
                (int) schedule.getEntries().stream().filter(e -> e.getType() == DayType.UNSET).count());
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
                List<DayHours> daily = dates.stream().map(d -> DayHours.from(store.hoursOn(d)))
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
