package com.example.schedulingsoftware.employee;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.auth.AccessPolicy;

/**
 * Upcoming birthdays, so admins and managers can plan something (a card, a cake). Admins see everyone; managers see
 * their own area. Someone born on February 29 celebrates on February 28 in other years.
 */
@Service
public class BirthdayService {

    /** Longest look-ahead the list allows. */
    static final int MAX_DAYS = 366;

    private final EmployeeRepository employeeRepository;
    private final AccessPolicy accessPolicy;
    private final ZoneId zone;

    public BirthdayService(EmployeeRepository employeeRepository, AccessPolicy accessPolicy,
            @Value("${alliancetel.birthdays.zone:America/Los_Angeles}") String zone) {
        this.employeeRepository = employeeRepository;
        this.accessPolicy = accessPolicy;
        this.zone = ZoneId.of(zone);
    }

    /** One coming birthday. {@code turning} is the age they'll be on that day. */
    public record UpcomingBirthday(
            Long employeeId,
            String fullName,
            Role role,
            Long areaId,
            String areaName,
            String homeStoreName,
            LocalDate birthDate,
            LocalDate nextBirthday,
            int daysUntil,
            int turning) {
    }

    /** Today, in the company's time zone. */
    public LocalDate today() {
        return LocalDate.now(zone);
    }

    /** Birthdays from today through the next {@code days} days (default 30), soonest first. */
    @Transactional(readOnly = true)
    public List<UpcomingBirthday> upcoming(Long requestedAreaId, Integer days) {
        Long areaId = accessPolicy.restrictToArea(requestedAreaId);
        int window = days == null ? 30 : Math.max(0, Math.min(days, MAX_DAYS));
        return upcoming(employeeRepository.findByActiveTrueAndBirthDateIsNotNull(), today(), window).stream()
                .filter(b -> areaId == null || areaId.equals(b.areaId()))
                .toList();
    }

    /** Pure calculation, also used by the reminder emails. */
    public static List<UpcomingBirthday> upcoming(List<Employee> people, LocalDate today, int days) {
        return people.stream()
                .filter(e -> e.isActive() && e.getBirthDate() != null)
                .map(e -> of(e, today))
                .filter(b -> b.daysUntil() <= days)
                .sorted(Comparator.comparingInt(UpcomingBirthday::daysUntil)
                        .thenComparing(UpcomingBirthday::fullName))
                .toList();
    }

    static UpcomingBirthday of(Employee e, LocalDate today) {
        LocalDate next = nextBirthday(e.getBirthDate(), today);
        return new UpcomingBirthday(e.getId(), e.getFullName(), e.getRole(),
                e.getArea() == null ? null : e.getArea().getId(),
                e.getArea() == null ? null : e.getArea().getName(),
                e.getHomeStore() == null ? null : e.getHomeStore().getName(),
                e.getBirthDate(), next, (int) ChronoUnit.DAYS.between(today, next),
                next.getYear() - e.getBirthDate().getYear());
    }

    /** The next time the birthday comes round, today included. Feb 29 falls on Feb 28 outside leap years. */
    public static LocalDate nextBirthday(LocalDate birthDate, LocalDate today) {
        MonthDay md = MonthDay.from(birthDate);
        LocalDate thisYear = md.atYear(today.getYear());
        return thisYear.isBefore(today) ? md.atYear(today.getYear() + 1) : thisYear;
    }
}
