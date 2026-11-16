package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyScheduleRepository extends JpaRepository<WeeklySchedule, Long> {

    /** The week's main schedule (the one that is published). */
    default Optional<WeeklySchedule> findByAreaIdAndWeekStart(Long areaId, LocalDate weekStart) {
        return findByAreaIdAndWeekStartAndKind(areaId, weekStart, ScheduleKind.MAIN);
    }

    Optional<WeeklySchedule> findByAreaIdAndWeekStartAndKind(Long areaId, LocalDate weekStart, ScheduleKind kind);

    /** Every schedule of the week: the main one and the drafts. */
    java.util.List<WeeklySchedule> findByAreaIdAndWeekStartOrderByKindAsc(Long areaId, LocalDate weekStart);
}
