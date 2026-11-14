package com.example.schedulingsoftware.notify;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.schedulingsoftware.employee.BirthdayService;
import com.example.schedulingsoftware.employee.BirthdayService.UpcomingBirthday;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.employee.EmployeeRepository;
import com.example.schedulingsoftware.employee.Role;

import lombok.extern.slf4j.Slf4j;

/**
 * Emails admins and managers about coming birthdays so they can arrange something. By default a week before and on
 * the day itself. Admins hear about everyone; managers about people in their own area. Nobody is told about their
 * own birthday. One email per person per day at most, listing every birthday due a reminder that day; it is sent
 * through the usual email queue, so it also shows on the Messages page.
 */
@Component
@Slf4j
public class BirthdayReminders {

    private final EmployeeRepository employeeRepository;
    private final NotificationRepository repository;
    private final WhatsAppSettings settings;
    private final boolean enabled;
    private final List<Integer> daysBefore;
    private final ZoneId zone;

    public BirthdayReminders(EmployeeRepository employeeRepository, NotificationRepository repository,
            WhatsAppSettings settings,
            @Value("${alliancetel.birthdays.reminders-enabled:true}") boolean enabled,
            @Value("${alliancetel.birthdays.remind-days-before:7,0}") String daysBefore,
            @Value("${alliancetel.birthdays.zone:America/Los_Angeles}") String zone) {
        this.employeeRepository = employeeRepository;
        this.repository = repository;
        this.settings = settings;
        this.enabled = enabled;
        this.daysBefore = Arrays.stream(daysBefore.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf)
                .filter(d -> d >= 0 && d <= 60).distinct().sorted().toList();
        this.zone = ZoneId.of(zone);
    }

    /**
     * Checks every hour through the working day (8am–6pm company time), so a restart or a deploy in the morning
     * doesn't skip a day; the once-a-day rule stops repeats.
     */
    @Scheduled(cron = "${alliancetel.birthdays.reminder-cron:0 5 8-18 * * *}", zone = "${alliancetel.birthdays.zone:America/Los_Angeles}")
    @Transactional
    public void scheduled() {
        int n = sendDue(LocalDate.now(zone));
        if (n > 0) {
            log.info("Queued {} birthday reminder email(s)", n);
        }
    }

    /** @return how many reminder emails were queued */
    @Transactional
    public int sendDue(LocalDate today) {
        if (!enabled || daysBefore.isEmpty()) {
            return 0;
        }
        int maxDays = daysBefore.getLast();
        List<UpcomingBirthday> due = BirthdayService
                .upcoming(employeeRepository.findByActiveTrueAndBirthDateIsNotNull(), today, maxDays).stream()
                .filter(b -> daysBefore.contains(b.daysUntil()))
                .toList();
        if (due.isEmpty()) {
            return 0;
        }
        java.time.Instant startOfDay = today.atStartOfDay(zone).toInstant();
        int queued = 0;
        for (Employee to : employeeRepository.findByActiveTrueAndRoleIn(EnumSet.of(Role.ADMIN, Role.MANAGER,
                Role.MARKET_MANAGER))) {
            if (to.getEmail() == null) {
                continue;
            }
            java.util.Set<Long> areas = to.managedAreaIds();
            List<UpcomingBirthday> theirs = due.stream()
                    .filter(b -> !b.employeeId().equals(to.getId()))
                    .filter(b -> to.getRole() == Role.ADMIN || (b.areaId() != null && areas.contains(b.areaId())))
                    .toList();
            if (theirs.isEmpty() || repository.existsByEmployee_IdAndKindAndCreatedAtGreaterThanEqual(to.getId(),
                    NotificationKind.BIRTHDAY_REMINDER, startOfDay)) {
                continue;
            }
            repository.save(email(to, theirs, to.getRole() == Role.ADMIN));
            queued++;
        }
        return queued;
    }

    private Notification email(Employee to, List<UpcomingBirthday> birthdays, boolean showArea) {
        List<String> today = birthdays.stream().filter(b -> b.daysUntil() == 0).map(b -> b.fullName()).toList();
        String subject = today.isEmpty()
                ? "Birthday coming up: " + names(birthdays)
                : "Birthday today: " + String.join(", ", today);
        StringBuilder text = new StringBuilder("Hi ").append(to.getFirstName()).append(",\n");
        birthdays.stream().collect(Collectors.groupingBy(UpcomingBirthday::daysUntil,
                java.util.TreeMap::new, Collectors.toList())).forEach((days, group) -> {
                    LocalDate date = group.getFirst().nextBirthday();
                    text.append("\n").append(days == 0 ? "Today (" + ScheduleNotifier.day(date, true) + ")"
                            : (days == 1 ? "Tomorrow" : "In " + days + " days") + " — "
                                    + ScheduleNotifier.day(date, true)).append(":\n");
                    for (UpcomingBirthday b : group) {
                        text.append("• ").append(b.fullName()).append(where(b, showArea)).append("\n");
                    }
                });
        text.append(today.isEmpty() ? "\nA good time to plan a card, cake or a small celebration with the team."
                : "\nDon't forget to wish them a happy birthday!");
        text.append("\n\nSee all upcoming birthdays: ").append(settings.link("/dashboard"));

        Notification n = new Notification();
        n.setEmployee(to);
        n.setAreaId(to.getArea() == null ? null : to.getArea().getId());
        n.setKind(NotificationKind.BIRTHDAY_REMINDER);
        n.setChannel(NotificationChannel.EMAIL);
        n.setEmail(to.getEmail());
        n.setTemplate("email");
        n.setParamList(List.of());
        n.setSubject(cut(subject, 200));
        n.setBody(cut(text.toString(), 4000));
        n.setPreview(cut(subject + " — " + text.toString().replaceAll("\\s+", " ").trim(), 1500));
        if (!to.isEmailOptIn()) {
            n.setStatus(NotificationStatus.NOT_SENT);
            n.setError("Turned off email updates.");
        }
        return n;
    }

    private static String where(UpcomingBirthday b, boolean showArea) {
        List<String> parts = new ArrayList<>();
        if (b.homeStoreName() != null) {
            parts.add(b.homeStoreName());
        }
        if (showArea && b.areaName() != null) {
            parts.add(b.areaName());
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private static String names(List<UpcomingBirthday> list) {
        String first = list.getFirst().fullName();
        return list.size() == 1 ? first : first + " and " + (list.size() - 1) + " more";
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
