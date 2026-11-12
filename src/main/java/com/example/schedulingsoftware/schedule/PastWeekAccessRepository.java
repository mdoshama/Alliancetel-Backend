package com.example.schedulingsoftware.schedule;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PastWeekAccessRepository extends JpaRepository<PastWeekAccess, Long> {

    List<PastWeekAccess> findByAreaIdAndWeekStartOrderByRequestedAtDesc(Long areaId, LocalDate weekStart);

    List<PastWeekAccess> findByStatusOrderByRequestedAtAsc(PastWeekAccess.Status status);

    List<PastWeekAccess> findByStatusInOrderByRequestedAtDesc(Collection<PastWeekAccess.Status> statuses);
}
