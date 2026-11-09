package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FullWeekRepository extends JpaRepository<FullWeek, Long> {

    Optional<FullWeek> findByEmployeeIdAndWeekStart(Long employeeId, LocalDate weekStart);

    boolean existsByEmployeeIdAndWeekStart(Long employeeId, LocalDate weekStart);

    @Query("select f from FullWeek f where f.employee.area.id = :areaId and f.weekStart = :weekStart")
    List<FullWeek> findForArea(@Param("areaId") Long areaId, @Param("weekStart") LocalDate weekStart);
}
