package com.example.schedulingsoftware.timeclock;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.auth.CurrentUser;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.common.InputRules;
import com.example.schedulingsoftware.common.ResourceNotFoundException;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.schedule.ScheduleEntry;
import com.example.schedulingsoftware.schedule.ScheduleEntryRepository;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreDayHours;
import com.example.schedulingsoftware.store.StoreRepository;
import com.example.schedulingsoftware.timeclock.TimeClockDtos.Day;
import com.example.schedulingsoftware.timeclock.TimeClockDtos.Row;

import lombok.extern.slf4j.Slf4j;

/**
 * The time clock. Back office staff (and admins) clock store staff in, watching the store cameras; clocking in is
 * required for everyone on the published schedule, clocking out is optional. Managers see their own area.
 */
@Slf4j
@Service
public class TimeClockService {

    private static final Set<Role> STAFF = EnumSet.of(Role.MANAGER, Role.EMPLOYEE);

    private final TimeEntryRepository repository;
    private final ScheduleEntryRepository shifts;
    private final EmployeeRepository employees;
    private final StoreRepository stores;
    private final AccessPolicy accessPolicy;
    private final ZoneId zone;
    private final int graceMinutes;

    public TimeClockService(TimeEntryRepository repository, ScheduleEntryRepository shifts,
            EmployeeRepository employees, StoreRepository stores, AccessPolicy accessPolicy,
            @Value("${alliancetel.scheduling.zone:America/Los_Angeles}") String zone,
            @Value("${alliancetel.time-clock.grace-minutes:10}") int graceMinutes) {
        this.repository = repository;
        this.shifts = shifts;
        this.employees = employees;
        this.stores = stores;
        this.accessPolicy = accessPolicy;
        this.zone = ZoneId.of(zone);
        this.graceMinutes = graceMinutes;
    }

    /** The day's sheet: everyone on the published schedule (by store), and everyone else who could be clocked in. */
    @Transactional(readOnly = true)
    public Day day(Long requestedAreaId, LocalDate requestedDate) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        LocalDate today = LocalDate.now(zone);
        LocalDate date = requestedDate != null ? requestedDate : today;
        LocalTime now = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        Map<Long, TimeEntry> byEmployee = new HashMap<>();
        repository.findForArea(areaId, date, date).forEach(t -> byEmployee.put(t.getEmployee().getId(), t));

        List<Row> rows = new ArrayList<>();
        Set<Long> scheduled = new HashSet<>();
        for (ScheduleEntry shift : shifts.findPublishedShifts(areaId, date, date)) {
            Employee e = shift.getEmployee();
            if (!e.isActive() || !scheduled.add(e.getId())) {
                continue;
            }
            rows.add(row(e, shift.getStore(), true, byEmployee.get(e.getId()), date, today, now));
        }
        rows.sort(Comparator.comparing((Row r) -> r.storeName() == null ? "~" : r.storeName())
                .thenComparing(Row::name));

        List<Row> others = new ArrayList<>();
        List<Employee> staff = areaId == null
                ? employees.findAll().stream().filter(e -> e.isActive() && STAFF.contains(e.getRole())).toList()
                : employees.findByAreaIdAndActiveTrueAndRoleInOrderByLastNameAscFirstNameAsc(areaId, STAFF);
        for (Employee e : staff) {
            if (!scheduled.contains(e.getId())) {
                TimeEntry t = byEmployee.get(e.getId());
                others.add(row(e, t != null ? t.getStore() : e.getHomeStore(), false, t, date, today, now));
            }
        }
        // Unscheduled people who were clocked in come first.
        others.sort(Comparator.comparing((Row r) -> r.clockIn() == null).thenComparing(Row::name));

        int clockedIn = (int) rows.stream().filter(r -> r.clockIn() != null).count();
        int missing = (int) rows.stream().filter(Row::missing).count();
        int late = (int) rows.stream().filter(r -> r.minutesLate() > 0).count();
        return new Day(date, date.equals(today), rows.size(), clockedIn, missing, late, canEdit(), graceMinutes, rows,
                others);
    }

    private Row row(Employee e, Store store, boolean scheduled, TimeEntry t, LocalDate date, LocalDate today,
            LocalTime now) {
        Store where = t != null && t.getStore() != null ? t.getStore() : store;
        LocalTime opens = opensAt(where, date);
        int late = 0;
        if (t != null && opens != null && t.getClockIn().isAfter(opens.plusMinutes(graceMinutes))) {
            late = (int) Duration.between(opens, t.getClockIn()).toMinutes();
        }
        boolean missing = scheduled && t == null && (date.isBefore(today)
                || (date.equals(today) && (opens == null || now.isAfter(opens.plusMinutes(graceMinutes)))));
        Double hours = t != null && t.getClockOut() != null
                ? Math.round(Duration.between(t.getClockIn(), t.getClockOut()).toMinutes() / 6.0) / 10.0
                : null;
        return new Row(e.getId(), e.getFullName(), e.getEmployeeCode(),
                e.getArea() == null ? null : e.getArea().getName(),
                where == null ? null : where.getId(), where == null ? null : where.getName(),
                where == null ? null : where.getDisplayColor(), scheduled, opens,
                t == null ? null : t.getId(), t == null ? null : t.getClockIn(), t == null ? null : t.getClockOut(),
                t == null ? null : t.getClockedInBy(), t == null ? null : t.getClockedOutBy(),
                t == null ? null : t.getNote(), late, missing, hours);
    }

    private static LocalTime opensAt(Store store, LocalDate date) {
        if (store == null) {
            return null;
        }
        StoreDayHours h = store.hoursOn(date.getDayOfWeek());
        return h == null || h.isClosed() ? null : h.getOpensAt();
    }

    /** Clocks someone in (required each work day). Time defaults to now; store to where they're scheduled. */
    @Transactional
    public Row clockIn(TimeClockDtos.ClockInRequest request) {
        CurrentUser user = requireEditor();
        Employee e = employees.findById(request.employeeId())
                .orElseThrow(() -> new ResourceNotFoundException("Employee", request.employeeId()));
        if (!e.isActive() || !STAFF.contains(e.getRole())) {
            throw new BusinessRuleException(e.getFullName() + " isn't store staff who can be clocked in.");
        }
        LocalDate today = LocalDate.now(zone);
        LocalDate date = request.date();
        if (date.isAfter(today)) {
            throw new BusinessRuleException("You can't clock someone in for a day that hasn't come yet.");
        }
        LocalTime time = request.time() != null ? request.time().truncatedTo(ChronoUnit.MINUTES) : null;
        if (time == null) {
            if (!date.equals(today)) {
                throw new BusinessRuleException("Enter the time they started on " + date + ".");
            }
            time = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        }
        if (date.equals(today) && time.isAfter(LocalTime.now(zone).plusMinutes(1))) {
            throw new BusinessRuleException("The clock-in time can't be in the future.");
        }
        TimeEntry existing = repository.findByEmployeeIdAndWorkDate(e.getId(), date).orElse(null);
        if (existing != null) {
            throw new BusinessRuleException(e.getFirstName() + " was already clocked in at " + existing.getClockIn()
                    + " by " + existing.getClockedInBy() + ". Edit that entry instead.");
        }
        Store store = request.storeId() != null ? stores.findById(request.storeId())
                .orElseThrow(() -> new ResourceNotFoundException("Store", request.storeId())) : scheduledStore(e, date);
        TimeEntry t = new TimeEntry();
        t.setEmployee(e);
        t.setWorkDate(date);
        t.setStore(store);
        t.setClockIn(time);
        t.setClockedInBy(user.fullName());
        t.setNote(InputRules.clean(request.note()));
        repository.save(t);
        log.info("{} clocked in {} at {} on {}", user.email(), e.getEmployeeCode(), time, date);
        return row(e, store, store != null && scheduledStore(e, date) != null, t, date, today,
                LocalTime.now(zone));
    }

    /** Clocks someone out (optional). Time defaults to now. */
    @Transactional
    public Row clockOut(Long entryId, java.time.LocalTime requested) {
        CurrentUser user = requireEditor();
        TimeEntry t = get(entryId);
        LocalDate today = LocalDate.now(zone);
        LocalTime time = requested != null ? requested.truncatedTo(ChronoUnit.MINUTES) : null;
        if (time == null) {
            if (!t.getWorkDate().equals(today)) {
                throw new BusinessRuleException("Enter the time they finished.");
            }
            time = LocalTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        }
        if (!time.isAfter(t.getClockIn())) {
            throw new BusinessRuleException("Clock-out has to be after clock-in (" + t.getClockIn() + ").");
        }
        t.setClockOut(time);
        t.setClockedOutBy(user.fullName());
        return toRow(t);
    }

    /** Corrects an entry's times or note (clock-out can be cleared). */
    @Transactional
    public Row correct(Long entryId, TimeClockDtos.CorrectRequest request) {
        requireEditor();
        TimeEntry t = get(entryId);
        LocalTime in = request.clockIn().truncatedTo(ChronoUnit.MINUTES);
        LocalTime out = request.clockOut() == null ? null : request.clockOut().truncatedTo(ChronoUnit.MINUTES);
        if (out != null && !out.isAfter(in)) {
            throw new BusinessRuleException("Clock-out has to be after clock-in.");
        }
        t.setClockIn(in);
        t.setClockOut(out);
        if (out == null) {
            t.setClockedOutBy(null);
        }
        t.setNote(InputRules.clean(request.note()));
        return toRow(t);
    }

    /** Removes a clock-in made by mistake. */
    @Transactional
    public void delete(Long entryId) {
        CurrentUser user = requireEditor();
        TimeEntry t = get(entryId);
        log.info("{} removed the clock-in of {} on {}", user.email(), t.getEmployee().getEmployeeCode(),
                t.getWorkDate());
        repository.delete(t);
    }

    private Row toRow(TimeEntry t) {
        LocalDate today = LocalDate.now(zone);
        return row(t.getEmployee(), t.getStore(), scheduledStore(t.getEmployee(), t.getWorkDate()) != null, t,
                t.getWorkDate(), today, LocalTime.now(zone));
    }

    private Store scheduledStore(Employee e, LocalDate date) {
        return shifts.findPublishedDay(e.getId(), date).stream()
                .filter(s -> s.getType() == com.example.schedulingsoftware.schedule.DayType.WORK)
                .map(ScheduleEntry::getStore).filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    private TimeEntry get(Long id) {
        TimeEntry t = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Time entry", id));
        accessPolicy.restrictToArea(t.getEmployee().getArea() == null ? null : t.getEmployee().getArea().getId());
        return t;
    }

    private boolean canEdit() {
        CurrentUser u = accessPolicy.current();
        return u.isAdmin() || u.isBackOffice();
    }

    private CurrentUser requireEditor() {
        CurrentUser u = accessPolicy.current();
        if (!u.isAdmin() && !u.isBackOffice()) {
            throw new AccessDeniedException("Only back office and admins clock people in and out.");
        }
        return u;
    }
}
