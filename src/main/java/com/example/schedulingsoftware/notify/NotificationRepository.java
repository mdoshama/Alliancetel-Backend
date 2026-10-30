package com.example.schedulingsoftware.notify;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    @Query("""
            select n from Notification n left join fetch n.employee
            where n.status = com.example.schedulingsoftware.notify.NotificationStatus.QUEUED and n.nextAttemptAt <= :now
            order by n.id
            """)
    List<Notification> findDue(@Param("now") Instant now, Pageable limit);

    @Query("""
            select n from Notification n
            where (:areaId is null or n.areaId = :areaId) and (:status is null or n.status = :status)
              and (:channel is null or n.channel = :channel)
            """)
    Page<Notification> search(@Param("areaId") Long areaId, @Param("status") NotificationStatus status,
            @Param("channel") NotificationChannel channel, Pageable page);

    long countByAreaIdAndStatus(Long areaId, NotificationStatus status);

    long countByStatus(NotificationStatus status);
}
