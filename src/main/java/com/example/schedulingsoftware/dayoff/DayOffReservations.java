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

    public DayOffReservations(DayOffRequestRepository repository, WeekRequests weekRequests,
            AreaRepository areaRepository, WeeklyScheduleRepository scheduleRepository,
            ScheduleService scheduleService, RequestPortal portal,
            @Value("${alliancetel.scheduling.reserve-days-off:true}") boolean enabled,
            @Value("${alliancetel.background-jobs:true}") boolean backgroundJobs) {
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
        LocalDate today = portal.today();
        // Regular days off first, whatever the staffing: managers decide on them (they're never approved here).
        int n = reserveRegularDays(areaId, weekStart, today);
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
     * Reserves each person's regular days off in that week, up to their usual number of days off, even when the
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
            long already = repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndReservedFalse(e.getId(), week,
                    week.plusDays(6), List.of(DayOffStatus.PENDING, DayOffStatus.APPROVED))
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
     * Gives back reserved days so the person isn't off more than their usual number that week (counting their own
     * pending and approved requests). Each one becomes a work day again on a schedule that's already made.
     *
     * @return the dates given back
     */
    @Transactional
    public List<LocalDate> trim(Employee e, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        // Reserved days still in play (not turned down), the ones not yet approved given back first.
        List<DayOffRequest> reserved = repository
                .findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(e.getId(), week, week.plusDays(6))
                .stream().filter(r -> r.getStatus() != DayOffStatus.REJECTED)
                // Approved last, and a regular day off after other reserved days.
                .sorted(java.util.Comparator.comparing((DayOffRequest r) -> r.getStatus() == DayOffStatus.APPROVED)
                        .thenComparing(DayOffRequest::isRegular))
                .toList();
        if (reserved.isEmpty() || published(e.getArea() == null ? null : e.getArea().getId(), week)) {
            return List.of();
        }
        long own = ownDaysOff(e, week);
        int usual = Math.max(0, 7 - e.getWorkDaysPerWeek());
        long extra = own + reserved.size() - usual;
        java.util.ArrayList<LocalDate> released = new java.util.ArrayList<>();
        for (DayOffRequest r : reserved) {
            if (extra <= 0) {
                break;
            }
            released.add(r.getOffDate());
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

    private boolean published(Long areaId, LocalDate weekStart) {
        return areaId != null && scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .map(w -> w.getStatus() == ScheduleStatus.PUBLISHED).orElse(false);
    }
}
