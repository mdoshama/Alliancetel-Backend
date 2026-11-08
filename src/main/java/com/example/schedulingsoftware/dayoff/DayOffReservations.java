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
 * people working 6 days, 2 for 5 days), on the days the stores can best spare them. When someone then asks for a
 * different day, the request is approved straight away if the stores can cover it, and a reserved day is given
 * back (it becomes a work day again). If a request is turned down or cancelled, the missing day is reserved again.
 * Published weeks are never touched.
 */
@Slf4j
@Service
public class DayOffReservations {

    static final String RESERVED_REASON = "Reserved by the system";

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
        if (backgroundJobs) {
            scheduled();
        }
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
        int n = 0;
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
            r.setStatus(DayOffStatus.APPROVED);
            r.setReviewedAt(Instant.now());
            repository.save(r);
            scheduleService.applyApprovedDayOff(r.getEmployee(), date);
            n++;
        }
        return n;
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
        List<DayOffRequest> reserved = repository
                .findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(e.getId(), week, week.plusDays(6));
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
        return repository.countByEmployeeIdAndOffDateBetweenAndStatusInAndReservedFalse(e.getId(), week,
                week.plusDays(6), List.of(DayOffStatus.PENDING, DayOffStatus.APPROVED));
    }

    private boolean published(Long areaId, LocalDate weekStart) {
        return areaId != null && scheduleRepository.findByAreaIdAndWeekStart(areaId, weekStart)
                .map(w -> w.getStatus() == ScheduleStatus.PUBLISHED).orElse(false);
    }
}
