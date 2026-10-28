package com.example.schedulingsoftware.dayoff;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DayOffRequestRepository
        extends JpaRepository<DayOffRequest, Long>, JpaSpecificationExecutor<DayOffRequest> {

    boolean existsByEmployeeIdAndOffDate(Long employeeId, LocalDate offDate);

    boolean existsByEmployeeIdAndOffDateAndStatus(Long employeeId, LocalDate offDate, DayOffStatus status);

    @Query("""
            select r from DayOffRequest r join fetch r.employee e
            where e.area.id = :areaId and r.offDate = :date and r.status in :statuses
            order by r.id
            """)
    List<DayOffRequest> findForAreaOnDate(@Param("areaId") Long areaId, @Param("date") LocalDate date,
            @Param("statuses") Collection<DayOffStatus> statuses);

    long countByEmployeeIdAndOffDateBetweenAndStatusIn(Long employeeId, LocalDate from, LocalDate to,
            Collection<DayOffStatus> statuses);

    /** Like the count above, but leaving out emergency requests (they don't use up the weekly limit). */
    long countByEmployeeIdAndOffDateBetweenAndStatusInAndEmergencyFalse(Long employeeId, LocalDate from, LocalDate to,
            Collection<DayOffStatus> statuses);

    /** Days off (in the given statuses) of other active people based at the same store, in a date range. */
    @Query("""
            select r from DayOffRequest r join fetch r.employee e
            where e.homeStore.id = :storeId and e.active = true and e.id <> :employeeId
              and r.offDate between :from and :to and r.status in :statuses
            order by r.offDate, r.id
            """)
    List<DayOffRequest> findSameStore(@Param("storeId") Long storeId, @Param("employeeId") Long employeeId,
            @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("statuses") Collection<DayOffStatus> statuses);

    @Query("""
            select r from DayOffRequest r join fetch r.employee e
            where e.area.id = :areaId and r.status = :status and r.offDate between :from and :to
            """)
    List<DayOffRequest> findForArea(@Param("areaId") Long areaId, @Param("status") DayOffStatus status,
            @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select count(r) from DayOffRequest r
            where r.employee.area.id = :areaId and r.status = :status and r.offDate between :from and :to
            """)
    long countForArea(@Param("areaId") Long areaId, @Param("status") DayOffStatus status,
            @Param("from") LocalDate from, @Param("to") LocalDate to);
}
