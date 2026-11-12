package com.example.schedulingsoftware.dayoff;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.area.AreaRepository;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.schedule.ScheduleService;
import com.example.schedulingsoftware.schedule.ScheduleStatus;
import com.example.schedulingsoftware.schedule.WeekRequests;
import com.example.schedulingsoftware.schedule.WeeklyScheduleRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Reserved days off. As soon as a week opens for requests, everyone gets their usual days off reserved (1 for
 * people working 6 days, 2 for 5 days), on the days the stores can best spare them. A reserved day is only held:
 * like any request it waits for a manager or admin to approve it (managers can approve them all at once). When
 * someone asks for a different day, that request also waits; once it's approved, their reserved day is given back
 * (it becomes a work day again). If a reserved day is turned down, another day is reserved instead.
 * Published weeks are never touched.
 */
@Slf4j
@Service
public class DayOffReservations {

    static final String RESERVED_REASON = "Reserved by the system";
    static final String REGULAR_REASON = "Regular day off";

    private final DayOffRequestRepository repository;
    private final WeekRequests weekRequests;
    private final AreaRepository areaRepository;
    private final WeeklyScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;
    private final RequestPortal portal;
    private final boolean enabled;
    private final boolean backgroundJobs;
    private final com.example.schedulingsoftware.live.LiveUpdates liveUpdates;

    public DayOffReservations(DayOffRequestRepository repository, WeekRequests weekRequests,
            AreaRepository areaRepository, WeeklyScheduleRepository scheduleRepository,
            ScheduleService scheduleService, RequestPortal portal,
            @Value("${alliancetel.scheduling.reserve-days-off:true}") boolean enabled,
            @Value("${alliancetel.background-jobs:true}") boolean backgroundJobs,
            com.example.schedulingsoftware.live.LiveUpdates liveUpdates) {
        this.liveUpdates = liveUpdates;
        this.backgroundJobs = backgroundJobs;
        this.repository = repository;
        this.weekRequests = weekRequests;
        this.areaRepository = areaRepository;
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
        this.portal = portal;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Every hour: makes sure everyone has their days off in the weeks coming up (new staff, refused requests, …). */
    @Scheduled(cron = "${alliancetel.scheduling.reserve-cron:0 20 * * * *}",
            zone = "${alliancetel.scheduling.zone:America/Los_Angeles}")
    @Transactional
    public void scheduled() {
        int n = reserveUpcoming();
        if (n > 0) {
            log.info("Reserved {} day(s) off", n);
            liveUpdates.publishSystemChange("dayoff");
        }
    }

    /** Right after the app starts (e.g. a deploy), so nobody waits for the hourly run. */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void onStartup() {
        undoSelfApprovedReservations();
        if (backgroundJobs) {
            scheduled();
        }
    }

    /**
     * An earlier version approved reserved days by itself. Once, those (today on, in weeks not yet published) go back
     * to pending and become work days again on the schedule, so only a manager or admin approves days off.
     *
     * @return how many were put back to pending
     */
    @Transactional
    public int undoSelfApprovedReservations() {
        RequestPortalSetting setting = portal.setting();
        if (setting.getReservedApprovalsUndoneAt() != null) {
            return 0;
        }
        int n = 0;
        for (DayOffRequest r : repository.findByReservedTrueAndStatusAndOffDateGreaterThanEqual(DayOffStatus.APPROVED,
                portal.today())) {
            Employee e = r.getEmployee();
            if (r.getDecisionChangedAt() != null
                    || published(e.getArea() == null ? null : e.getArea().getId(), DayOffService.weekStart(r.getOffDate()))) {
                continue;
            }
            r.setStatus(DayOffStatus.PENDING);
            r.setReviewedAt(null);
            r.setReviewNote(null);
            repository.flush();
            scheduleService.revokeApprovedDayOff(e, r.getOffDate());
            n++;
        }
        setting.setReservedApprovalsUndoneAt(Instant.now());
        portal.save(setting);
        if (n > 0) {
            log.info("Put {} reserved day(s) the system had approved back to pending", n);
        }
        return n;
    }

    /** @return how many days were reserved */
    @Transactional
    public int reserveUpcoming() {
        if (!enabled) {
            return 0;
        }
        int n = 0;
        for (Area area : areaRepository.findAll()) {
            if (!area.isActive()) {
                continue;
            }
            for (LocalDate week : portal.reservableWeeks()) {
                n += reserveWeek(area.getId(), week);
            }
        }
        return n;
    }

    /** Reserves the missing days off in one area's week (skipped once that week is published). */
    @Transactional
    public int reserveWeek(Long areaId, LocalDate weekStart) {
        if (!enabled || published(areaId, weekStart)) {
            return 0;
        }
        return fill(areaId, weekStart);
    }

    /**
     * Right before a week is generated: makes sure everyone has their days off. In order: their regular day off,
     * then what they asked for (already in), and for anyone still short the system reserves the best days (stores
     * covered, one person per store, primaries, secondaries and floaters respected). Generating then gives them.
     * Runs whatever the request window or the reservation switch says, since the week is being made now.
     */
    @Transactional
    public int fillBeforeGenerating(Long areaId, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        return published(areaId, week) ? 0 : fill(areaId, week);
    }

    private int fill(Long areaId, LocalDate weekStart) {
        LocalDate today = portal.today();
        // Regular days off first, whatever the staffing: managers decide on them (they're never approved here).
        int n = reserveRegularDays(areaId, weekStart, today);
        // Days the system picked on a day the stores turned out short (someone else got that day too, a store's
        // minimum went up, …) are given back, so those people work there and get a better day below.
        List<DayOffRequest> misplaced = weekRequests.misplacedReserved(areaId, weekStart);
        if (!misplaced.isEmpty()) {
            repository.deleteAll(misplaced);
            repository.flush();
            log.info("Moved {} day(s) off the system picked on short days (area {}, week of {})", misplaced.size(),
                    areaId, weekStart);
        }
        for (WeekRequests.Suggestion s : weekRequests.suggestFor(areaId, weekStart)) {
            // Only future days, and never twice on the same date.
            LocalDate date = s.options().stream().filter(d -> d.isAfter(today)).findFirst().orElse(null);
            if (date == null || repository.existsByEmployeeIdAndOffDate(s.employeeId(), date)) {
                continue;
            }
            DayOffRequest r = new DayOffRequest();
            r.setEmployee(weekRequests.employee(s.employeeId()));
            r.setOffDate(date);
            r.setReason(RESERVED_REASON);
            r.setReserved(true);
            // Only reserved: it waits for a manager or admin like any request (nothing is approved automatically).
            r.setStatus(DayOffStatus.PENDING);
            repository.save(r);
            n++;
        }
        return n;
    }

    /**
     * Reserves each person's regular days off in that week, up to their usual number of days off less the days they
     * asked for themselves (a day they pick replaces their regular day that week), even when the
     * stores would be short or a colleague at the same store is off too: it stays pending and managers decide. A day
     * they already have any request for (including one turned down this week) is left alone.
     */
    private int reserveRegularDays(Long areaId, LocalDate weekStart, LocalDate today) {
        LocalDate week = DayOffService.weekStart(weekStart);
        java.util.Set<Long> fullWeek = weekRequests.fullWeekIds(areaId, week);
        int n = 0;
        for (Employee e : weekRequests.staffOf(areaId)) {
            if (e.getRegularDaysOff().isEmpty() || fullWeek.contains(e.getId())) {
                continue;
            }
            int usual = Math.max(0, 7 - e.getWorkDaysPerWeek());
            // Days they asked for themselves replace their regular day that week; an emergency doesn't (it comes on top).
            long already = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalseAndReservedFalse(
                    e.getId(), week, week.plusDays(6), List.of(DayOffStatus.PENDING, DayOffStatus.APPROVED))
                    + repository.findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(e.getId(), week,
                            week.plusDays(6)).stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED).count();
            for (java.time.DayOfWeek dow : e.getRegularDaysOff().stream().sorted().toList()) {
                if (already >= usual) {
                    break;
                }
                LocalDate date = week.plusDays(dow.getValue() - 1L);
                if (!date.isAfter(today) || repository.existsByEmployeeIdAndOffDate(e.getId(), date)) {
                    continue;
                }
                DayOffRequest r = new DayOffRequest();
                r.setEmployee(e);
                r.setOffDate(date);
                r.setReason(REGULAR_REASON);
                r.setReserved(true);
                r.setRegular(true);
                r.setStatus(DayOffStatus.PENDING);
                repository.save(r);
                already++;
                n++;
            }
        }
        return n;
    }

    /**
     * Checks new regular days off before they're saved, so the same weekday can't be given to too many people:
     * only one person per store on a weekday, and with everyone who has that weekday off the stores must still be
     * covered (primaries stay at their store, floaters only cover their own stores).
     */
    public void checkRegularDays(Employee e, java.util.Set<java.time.DayOfWeek> days) {
        if (e.getArea() == null || days.isEmpty()) {
            return;
        }
        Long areaId = e.getArea().getId();
        List<Employee> staff = weekRequests.staffOf(areaId);
        LocalDate tomorrow = portal.today().plusDays(1);
        for (java.time.DayOfWeek dow : days) {
            String dayName = dow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US);
            List<Employee> sameDay = staff.stream()
                    .filter(o -> !o.getId().equals(e.getId()) && o.getRegularDaysOff().contains(dow))
                    .toList();
            if (e.getHomeStore() != null) {
                Employee colleague = sameDay.stream()
                        .filter(o -> o.getHomeStore() != null && o.getHomeStore().getId().equals(e.getHomeStore().getId()))
                        .findFirst().orElse(null);
                if (colleague != null) {
                    throw new BusinessRuleException(colleague.getFullName() + " already has " + dayName + "s off at "
                            + e.getHomeStore().getName() + ". Only one person per store can be off on the same day, "
                            + "so pick another day for " + e.getFirstName() + ".");
                }
            }
            java.util.Set<Long> off = new java.util.HashSet<>();
            sameDay.forEach(o -> off.add(o.getId()));
            off.add(e.getId());
            LocalDate date = tomorrow.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow));
            int shortBy = scheduleService.shortWith(areaId, date, off);
            if (shortBy > 0) {
                String others = sameDay.isEmpty() ? ""
                        : " (" + String.join(", ", sameDay.stream().map(Employee::getFirstName).limit(6).toList())
                                + (sameDay.size() == 1 ? " is" : " are") + " already off every " + dayName + ")";
                throw new BusinessRuleException("With " + e.getFirstName() + " off every " + dayName + " too, the "
                        + "stores would be " + shortBy + (shortBy == 1 ? " person" : " people") + " short" + others
                        + ". Pick another day.");
            }
        }
    }

    /**
     * A manager or admin changed someone's regular days off: their days the system reserved in weeks still open
     * (and not published) are dropped and reserved again, so the new regular days show up right away. Days they
     * asked for themselves, and anything already approved or turned down, are kept.
     */
    @Transactional
    public void replanFor(Employee e) {
        if (!enabled || e.getArea() == null) {
            return;
        }
        Long areaId = e.getArea().getId();
        for (LocalDate week : portal.reservableWeeks()) {
            if (published(areaId, week)) {
                continue;
            }
            List<DayOffRequest> held = repository
                    .findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(e.getId(), week, week.plusDays(6))
                    .stream().filter(r -> r.getStatus() == DayOffStatus.PENDING).toList();
            if (!held.isEmpty()) {
                repository.deleteAll(held);
                repository.flush();
            }
            reserveWeek(areaId, week);
        }
    }

    /** After a request was turned down or cancelled: reserves the day they're now missing, if the week allows. */
    @Transactional
    public void refill(Employee e, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        if (enabled && e.getArea() != null && portal.reservableWeeks().contains(week)) {
            reserveWeek(e.getArea().getId(), week);
        }
    }

    /**
     * Gives back held days (reserved by the system, and with {@code regularToo} their regular days) so the person
     * isn't off more than their usual number that week, counting their own approved days. Days the system picked go
     * first, then regular days; not yet approved before approved. Each becomes a work day again on a schedule that's
     * already made. Published weeks are left alone.
     *
     * @param regularToo true when they chose a day themselves: a day they asked for replaces their regular day that
     *                   week. An emergency comes on top of it, so it leaves the regular day alone.
     * @return the days given back
     */
    @Transactional
    public List<DayOffRequest> trim(Employee e, LocalDate anyDayInWeek, boolean regularToo) {
        return trim(e, anyDayInWeek, regularToo, false);
    }

    /**
     * Same, and with {@code evenPublished} also on a published week (a day they chose was just approved there: the
     * day held for them goes back to work, so they have one day off, not two).
     */
    @Transactional
    public List<DayOffRequest> trim(Employee e, LocalDate anyDayInWeek, boolean regularToo, boolean evenPublished) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        if (!evenPublished && published(e.getArea() == null ? null : e.getArea().getId(), week)) {
            return List.of();
        }
        List<DayOffRequest> held = repository
                .findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(e.getId(), week, week.plusDays(6))
                .stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED).toList();
        List<DayOffRequest> canGo = held.stream()
                .filter(r -> regularToo || !r.isRegular())
                .sorted(java.util.Comparator.comparing(DayOffRequest::isRegular)
                        .thenComparing(r -> r.getStatus() == DayOffStatus.APPROVED))
                .toList();
        int usual = Math.max(0, 7 - e.getWorkDaysPerWeek());
        long extra = ownDaysOff(e, week) + held.size() - usual;
        java.util.ArrayList<DayOffRequest> released = new java.util.ArrayList<>();
        for (DayOffRequest r : canGo) {
            if (extra <= 0) {
                break;
            }
            released.add(r);
            repository.delete(r);
            repository.flush();
            scheduleService.revokeApprovedDayOff(e, r.getOffDate());
            extra--;
        }
        return released;
    }

    /**
     * Releases a colleague's reserved day so someone's real request can take it. Call {@link #refill} for them once
     * that request is saved, so they get another day (not the same one back).
     */
    @Transactional
    public void moveAside(DayOffRequest reservedDay) {
        Employee other = reservedDay.getEmployee();
        LocalDate date = reservedDay.getOffDate();
        repository.delete(reservedDay);
        repository.flush();
        scheduleService.revokeApprovedDayOff(other, date);
    }

    private long ownDaysOff(Employee e, LocalDate week) {
        // Only approved days count: a request waiting for a manager keeps the reserved day until it's approved.
        return repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndReservedFalse(e.getId(), week,
                week.plusDays(6), List.of(DayOffStatus.APPROVED));
    }

    /** Whether that week's schedule is published (published weeks keep the days off they were published with). */
    public boolean published(Long areaId, LocalDate weekStart) {
        return areaId != null && scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .map(w -> w.getStatus() == ScheduleStatus.PUBLISHED).orElse(false);
    }
}
