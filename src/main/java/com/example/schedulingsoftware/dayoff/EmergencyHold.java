package com.example.schedulingsoftware.dayoff;

import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * An emergency puts the person's week on hold: while it waits, none of their days off that week are given by the
 * system. Days the system gave (or would give) go back to waiting, next to the emergency, so a manager or admin
 * approves or rejects each day.
 */
@Component
@RequiredArgsConstructor
public class EmergencyHold {

    public static final String NOTE = "Waiting with the emergency.";

    private final DayOffRequestRepository repository;

    /**
     * At start-up: weeks with an emergency already waiting (sent before this rule) are put on hold the same way, so
     * their system-given days wait for a manager too. Safe to repeat.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @Transactional
    public void holdWaitingWeeks() {
        java.util.Set<String> done = new java.util.HashSet<>();
        int n = 0;
        for (DayOffRequest e : repository.findByEmergencyTrueAndStatusAndOffDateGreaterThanEqual(DayOffStatus.PENDING,
                LocalDate.now())) {
            LocalDate week = DayOffService.weekStart(e.getOffDate());
            if (done.add(e.getEmployee().getId() + "|" + week)) {
                n += holdWeek(e.getEmployee().getId(), week);
            }
        }
        if (n > 0) {
            org.slf4j.LoggerFactory.getLogger(EmergencyHold.class).info("Put {} day(s) off on hold next to emergencies", n);
        }
    }

    /** Whether they have an emergency still waiting in the week of that date. */
    public boolean emergencyWaiting(Long employeeId, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        return repository.existsByEmployeeIdAndEmergencyTrueAndStatusAndOffDateBetween(employeeId,
                DayOffStatus.PENDING, week, week.plusDays(6));
    }

    /** Puts one day back to waiting for a manager (nobody has decided it). */
    public void hold(DayOffRequest r) {
        r.setStatus(DayOffStatus.PENDING);
        r.setHeldForEmergency(true);
        r.setReviewedBy(null);
        r.setReviewedAt(null);
        r.setReviewNote(NOTE);
    }

    /**
     * Their week with an emergency waiting: every day off the system gave by itself (or holds for them) waits for a
     * manager too. Days a manager or admin decided stay as they are.
     *
     * @return how many days were put on hold
     */
    @Transactional
    public int holdWeek(Long employeeId, LocalDate anyDayInWeek) {
        LocalDate week = DayOffService.weekStart(anyDayInWeek);
        int n = 0;
        for (DayOffRequest r : repository.findByEmployeeIdAndOffDateBetween(employeeId, week, week.plusDays(6))) {
            if (r.isEmergency() || r.isHeldForEmergency()) {
                continue;
            }
            boolean bySystem = r.getStatus() == DayOffStatus.APPROVED && DayOffRequest.SYSTEM.equals(r.getReviewedBy())
                    && r.getDecisionChangedAt() == null;
            boolean held = r.getStatus() == DayOffStatus.PENDING && r.isReserved();
            if (bySystem || held) {
                hold(r);
                n++;
            }
        }
        repository.flush();
        return n;
    }
}
