package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyScheduleRepository extends JpaRepository<WeeklySchedule, Long> {

    Optional<WeeklySchedule> findByAreaIdAndWeekStart(Long areaId, LocalDate weekStart);
}
