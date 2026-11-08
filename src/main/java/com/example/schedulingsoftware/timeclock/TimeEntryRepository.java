package com.example.schedulingsoftware.timeclock;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, Long> {

    Optional<TimeEntry> findByEmployeeIdAndWorkDate(Long employeeId, LocalDate workDate);

    @Query("select t from TimeEntry t join fetch t.employee e left join fetch t.store "
            + "where t.workDate between :from and :to and (:areaId is null or e.area.id = :areaId)")
    List<TimeEntry> findForArea(@Param("areaId") Long areaId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
