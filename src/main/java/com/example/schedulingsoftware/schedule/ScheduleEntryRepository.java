package com.example.schedulingsoftware.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    boolean existsByStoreId(Long storeId);
}
