package com.example.schedulingsoftware.notify;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.example.schedulingsoftware.area.Area;
import com.example.schedulingsoftware.dayoff.DayOffRequest;
import com.example.schedulingsoftware.employee.Employee;
import com.example.schedulingsoftware.schedule.DayType;
import com.example.schedulingsoftware.schedule.ScheduleEntry;
import com.example.schedulingsoftware.schedule.WeeklySchedule;
import com.example.schedulingsoftware.store.Store;
import com.example.schedulingsoftware.store.StoreDayHours;

import lombok.RequiredArgsConstructor;

/**
 * Writes the messages (email, and WhatsApp once connected) people get about their schedule and queues them (saved in the same transaction as
 * the change). Each message is a Meta-approved template; {@link #TEMPLATE_TEXT} is the text to create in Meta.
 */
@Service
@RequiredArgsConstructor
public class ScheduleNotifier {

    /** Template bodies to create (category Utility) in WhatsApp Manager, with these exact {{n}} values. */
    public static final Map<NotificationKind, String> TEMPLATE_TEXT = Map.of(
            NotificationKind.WEEK_PUBLISHED,
            "Hi {{1}}, your AllianceTel schedule for {{2}} is posted: {{3}}. See it anytime at {{4}}.",
            NotificationKind.WEEK_CHANGED,
            "Hi {{1}}, your AllianceTel schedule for {{2}} has changed: {{3}}. See your full week at {{4}}.",
            NotificationKind.DAY_OFF_DECISION,
            "Hi {{1}}, your day-off request for {{2}} was {{3}}. {{4}}",
            NotificationKind.ANNOUNCEMENT,
            "Message from {{1}} for the AllianceTel {{2}} team: {{3}}");

    private static final String NO_PHONE = "No phone number on file.";
    private static final String NO_CONSENT = "Hasn't agreed to WhatsApp messages yet.";
    private static final String EMAIL_OFF = "Turned off email updates.";

    private final NotificationRepository repository;
    private final WhatsAppSettings settings;

    /**
     * Right after a week is published. The first time, everyone gets their whole week; when it is published again
     * after changes, only people whose days changed get a message, listing just those days.
     */
    public void weekPublished(WeeklySchedule schedule) {
        boolean firstTime = schedule.getEntries().stream().allMatch(e -> e.getPublishedLabel() == null);
        byEmployee(schedule).forEach((employee, days) -> {
            if (firstTime) {
                queueWeek(schedule, employee, days);
            } else {
                queueChanges(schedule, employee, days);
            }
        });
        snapshot(schedule);
    }

    /**
     * After a published week changed without being republished (e.g. an approved day off): tells the people whose
     * days changed. {@code skipEmployeeId} hears about it another way (their day-off decision) and is skipped.
     */
    public void publishedWeekChanged(WeeklySchedule schedule, Long skipEmployeeId) {
        byEmployee(schedule).forEach((employee, days) -> {
            if (!employee.getId().equals(skipEmployeeId)) {
                queueChanges(schedule, employee, days);
            }
        });
        snapshot(schedule);
    }

    /** Tells the person their day-off request was approved or rejected. */
    public void dayOffDecided(DayOffRequest request, boolean approved, String note) {
        Employee e = request.getEmployee();
        String when = day(request.getOffDate(), true);
        String result = approved ? "approved" : "not approved";
        String extra;
        if (note != null && !note.isBlank()) {
            extra = "Note from your manager: " + note.trim();
        } else {
            extra = approved ? "Enjoy your day off." : "Ask your manager if you have questions.";
        }
        queue(e, NotificationKind.DAY_OFF_DECISION, List.of(e.getFirstName(), when, result, extra),
                "Your day off on " + when + " was " + result,
                "Hi " + e.getFirstName() + ",\n\nYour day-off request for " + when + " was " + result + ".\n\n" + extra);
    }

    /** Tells the person a decision already made on their day off was changed, and why. */
    public void dayOffDecisionChanged(DayOffRequest request, boolean nowApproved, String reason) {
        Employee e = request.getEmployee();
        String when = day(request.getOffDate(), true);
        String result = nowApproved ? "changed to approved" : "changed to not approved";
        String extra = "Reason: " + reason.trim() + (nowApproved ? " Enjoy your day off."
                : " You're scheduled to work that day; check your schedule.");
        queue(e, NotificationKind.DAY_OFF_DECISION, List.of(e.getFirstName(), when, result, extra),
                "Update: your day off on " + when + " was " + result,
                "Hi " + e.getFirstName() + ",\n\nThe decision on your day-off request for " + when + " was " + result
                        + ".\n\n" + extra);
    }

    /** A message from a manager or admin to everyone active in an area. @return people it was queued for */
    public int announce(Area area, List<Employee> people, String fromName, String message) {
        int n = 0;
        for (Employee e : people) {
            if (e.isActive()) {
                queue(e, NotificationKind.ANNOUNCEMENT, List.of(fromName, area.getName(), message),
                        "Message from " + fromName + " (" + area.getName() + ")",
                        "Hi " + e.getFirstName() + ",\n\n" + message + "\n\n— " + fromName + ", AllianceTel "
                                + area.getName());
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------------------------

    private void queueWeek(WeeklySchedule schedule, Employee employee, List<ScheduleEntry> days) {
        List<String> parts = days.stream().map(d -> day(d.getWorkDate(), false) + " " + label(d)).toList();
        String week = weekLabel(schedule);
        queue(employee, NotificationKind.WEEK_PUBLISHED, List.of(employee.getFirstName(), week,
                String.join(" · ", parts), link(schedule)),
                "Your schedule for " + week,
                "Hi " + employee.getFirstName() + ",\n\nYour AllianceTel schedule for " + week + " is posted:\n\n"
                        + bullets(parts) + "\n\nSee it anytime: " + link(schedule));
    }

    private void queueChanges(WeeklySchedule schedule, Employee employee, List<ScheduleEntry> days) {
        if (days.stream().allMatch(d -> d.getPublishedLabel() == null)) {
            // Published before messages existed: nothing to compare with, so start tracking from now on quietly
            // rather than sending the whole week as "changed".
            return;
        }
        List<String> changes = new ArrayList<>();
        for (ScheduleEntry d : days) {
            String now = label(d);
            String before = d.getPublishedLabel();
            if (before == null) {
                changes.add(day(d.getWorkDate(), false) + " " + now);
            } else if (!before.equals(now)) {
                changes.add(day(d.getWorkDate(), false) + " now " + now + " (was " + before + ")");
            }
        }
        if (!changes.isEmpty()) {
            String week = weekLabel(schedule);
            queue(employee, NotificationKind.WEEK_CHANGED, List.of(employee.getFirstName(), week,
                    String.join(" · ", changes), link(schedule)),
                    "Your schedule for " + week + " changed",
                    "Hi " + employee.getFirstName() + ",\n\nThese days in your schedule for " + week + " changed:\n\n"
                            + bullets(changes) + "\n\nSee your full week: " + link(schedule));
        }
    }

    /** Remembers how each day reads now, to compare against next time. */
    private static void snapshot(WeeklySchedule schedule) {
        schedule.getEntries().forEach(e -> e.setPublishedLabel(label(e)));
    }

    /**
     * Queues the message on each channel: email (on by default; people can turn it off) and WhatsApp (only once it's
     * connected, so the Messages page isn't full of WhatsApp messages that could never be sent).
     */
    private void queue(Employee e, NotificationKind kind, List<String> rawParams, String subject, String text) {
        if (!e.isActive()) {
            return;
        }
        List<String> params = rawParams.stream().map(ScheduleNotifier::tidy).toList();
        String waText = cut(fill(TEMPLATE_TEXT.get(kind), params), 1500);
        if (e.getEmail() != null) {
            Notification mail = base(e, kind, NotificationChannel.EMAIL);
            mail.setEmail(e.getEmail());
            mail.setTemplate("email");
            mail.setParamList(List.of());
            mail.setSubject(cut(tidy(subject), 200));
            mail.setBody(cut(text, 4000));
            mail.setPreview(cut(subject + " — " + text.replaceAll("\\s+", " ").trim(), 1500));
            if (!e.isEmailOptIn()) {
                mail.setStatus(NotificationStatus.NOT_SENT);
                mail.setError(EMAIL_OFF);
            }
            repository.save(mail);
        }
        if (settings.isConnected()) {
            Notification wa = base(e, kind, NotificationChannel.WHATSAPP);
            wa.setPhone(e.getPhone());
            wa.setTemplate(settings.template(kind));
            wa.setParamList(params);
            wa.setPreview(waText);
            if (e.getPhone() == null) {
                wa.setStatus(NotificationStatus.NOT_SENT);
                wa.setError(NO_PHONE);
            } else if (!e.isWhatsappOptIn()) {
                wa.setStatus(NotificationStatus.NOT_SENT);
                wa.setError(NO_CONSENT);
            }
            repository.save(wa);
        }
    }

    private static Notification base(Employee e, NotificationKind kind, NotificationChannel channel) {
        Notification n = new Notification();
        n.setEmployee(e);
        n.setAreaId(e.getArea() == null ? null : e.getArea().getId());
        n.setKind(kind);
        n.setChannel(channel);
        return n;
    }

    private static String bullets(List<String> lines) {
        return String.join("\n", lines.stream().map(l -> "• " + l).toList());
    }

    private static Map<Employee, List<ScheduleEntry>> byEmployee(WeeklySchedule schedule) {
        Map<Employee, List<ScheduleEntry>> result = new LinkedHashMap<>();
        schedule.getEntries().stream()
                .sorted(Comparator.comparing((ScheduleEntry e) -> e.getEmployee().getId()).thenComparing(ScheduleEntry::getWorkDate))
                .forEach(e -> result.computeIfAbsent(e.getEmployee(), k -> new ArrayList<>()).add(e));
        return result;
    }

    /** "Oceanside 10am–8pm", "Work" (no store), or "Off". */
    public static String label(ScheduleEntry e) {
        if (e.getType() != DayType.WORK) {
            return "Off";
        }
        Store store = e.getStore();
        if (store == null) {
            return "Work";
        }
        StoreDayHours h = store.hoursOn(e.getWorkDate().getDayOfWeek());
        if (h.isClosed() || h.getOpensAt() == null) {
            return store.getName();
        }
        return store.getName() + " " + time(h.getOpensAt()) + "–" + time(h.getClosesAt());
    }

    /** 10:00 → "10am", 9:30 → "9:30am", 12:00 → "12pm". */
    static String time(LocalTime t) {
        int h = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        String suffix = t.getHour() < 12 ? "am" : "pm";
        return t.getMinute() == 0 ? h + suffix : h + ":" + String.format("%02d", t.getMinute()) + suffix;
    }

    /** "Mon 11/2", or with long = true "Wednesday, Nov 4". */
    static String day(LocalDate d, boolean full) {
        if (full) {
            return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US) + ", "
                    + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getDayOfMonth();
        }
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.getMonthValue() + "/" + d.getDayOfMonth();
    }

    private static String weekLabel(WeeklySchedule s) {
        LocalDate a = s.getWeekStart();
        LocalDate b = s.getWeekEnd();
        return a.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " " + a.getDayOfMonth() + "–"
                + (a.getMonth() == b.getMonth() ? "" : b.getMonth().getDisplayName(TextStyle.SHORT, Locale.US) + " ")
                + b.getDayOfMonth();
    }

    private String link(WeeklySchedule s) {
        return settings.link("/schedules?week=" + s.getWeekStart());
    }

    /** WhatsApp template values can't have line breaks, tabs or long runs of spaces, or be empty. */
    static String tidy(String value) {
        String v = Objects.requireNonNullElse(value, "").replaceAll("[\\p{Cntrl}]", " ").replaceAll(" {2,}", " ").trim();
        return v.isEmpty() ? "-" : cut(v, 900);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    static String fill(String template, List<String> params) {
        String out = template;
        for (int i = 0; i < params.size(); i++) {
            out = out.replace("{{" + (i + 1) + "}}", params.get(i));
        }
        return out;
    }
}
