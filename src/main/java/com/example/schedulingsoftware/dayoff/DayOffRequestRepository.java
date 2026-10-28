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

    long countByEmployeeIdAndOffDateBetweenAndStatusIn(Long employeeId, LocalDate from, LocalDate to,
            Collection<DayOffStatus> statuses);

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
