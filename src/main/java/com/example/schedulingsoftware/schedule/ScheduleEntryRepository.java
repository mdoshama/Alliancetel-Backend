package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    boolean existsByStoreId(Long storeId);

    /** Someone's day on published schedules (to know which store they're working at). */
    @Query("select e from ScheduleEntry e where e.employee.id = :employeeId and e.workDate = :date "
            + "and e.schedule.status = com.example.schedulingsoftware.schedule.ScheduleStatus.PUBLISHED")
    List<ScheduleEntry> findPublishedDay(@Param("employeeId") Long employeeId, @Param("date") LocalDate date);

    /** Who works at a store on a day, on published schedules. */
    @Query("select e from ScheduleEntry e where e.store.id = :storeId and e.workDate = :date "
            + "and e.type = com.example.schedulingsoftware.schedule.DayType.WORK "
            + "and e.schedule.status = com.example.schedulingsoftware.schedule.ScheduleStatus.PUBLISHED")
    List<ScheduleEntry> findWorkingAtStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /** Shifts worked in an area (all areas when areaId is null) between two dates, on published schedules. */
    @Query("select e from ScheduleEntry e where (:areaId is null or e.schedule.area.id = :areaId) "
            + "and e.workDate between :from and :to "
            + "and e.type = com.example.schedulingsoftware.schedule.DayType.WORK "
            + "and e.schedule.status = com.example.schedulingsoftware.schedule.ScheduleStatus.PUBLISHED")
    List<ScheduleEntry> findPublishedShifts(@Param("areaId") Long areaId, @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
