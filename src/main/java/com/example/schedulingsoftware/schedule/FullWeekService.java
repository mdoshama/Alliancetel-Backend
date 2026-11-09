package com.example.schedulingsoftware.schedule;

import java.time.Instant;
import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;
import com.example.schedulingsoftware.common.BusinessRuleException;
import com.example.schedulingsoftware.dayoff.DayOffRequestRepository;
import com.example.schedulingsoftware.dayoff.DayOffReservations;
import com.example.schedulingsoftware.dayoff.DayOffService;
import com.example.schedulingsoftware.dayoff.DayOffStatus;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Managers and admins let someone work all 7 days of a week (they asked for extra shifts). Their reserved days that
 * week are released; turning it off reserves their usual days again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FullWeekService {

    private final FullWeekRepository repository;
    private final EmployeeService employeeService;
    private final AccessPolicy accessPolicy;
    private final DayOffRequestRepository dayOffRepository;
    private final DayOffReservations reservations;

    @Transactional
    public void set(Long employeeId, LocalDate anyDayInWeek, boolean on) {
        Employee e = employeeService.getEmployee(employeeId);
        accessPolicy.requireManagerOf(e.getArea() == null ? null : e.getArea().getId());
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        if (week.isBefore(DayOffService.weekStart(LocalDate.now()))) {
            throw new BusinessRuleException("That week has already passed.");
        }
        if (!e.worksShifts()) {
            throw new BusinessRuleException(e.getFullName() + " isn't on the schedule.");
        }
        var existing = repository.findByEmployeeIdAndWeekStart(employeeId, week);
        if (on) {
            if (existing.isPresent()) {
                return;
            }
            if (e.getWorkDaysPerWeek() >= 7) {
                throw new BusinessRuleException(e.getFullName() + " already works 7 days every week.");
            }
            FullWeek f = new FullWeek();
            f.setEmployee(e);
            f.setWeekStart(week);
            f.setSetBy(accessPolicy.current().fullName());
            f.setSetAt(Instant.now());
            repository.save(f);
            // Their reserved days that week aren't needed (days they asked for themselves stay for a decision).
            dayOffRepository.deleteAll(dayOffRepository
                    .findByEmployeeIdAndReservedTrueAndOffDateBetweenOrderByOffDateDesc(employeeId, week, week.plusDays(6))
                    .stream().filter(r -> r.getStatus() == DayOffStatus.PENDING).toList());
            log.info("{} set {} to work all 7 days in the week of {}", accessPolicy.current().email(),
                    e.getEmployeeCode(), week);
        } else if (existing.isPresent()) {
            repository.delete(existing.get());
            repository.flush();
            // Back to their usual week: reserve their days off again if the week takes reservations.
            reservations.refill(e, week);
        }
    }
}
