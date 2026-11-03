package com.example.schedulingsoftware.commission;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionAdjustmentRepository extends JpaRepository<CommissionAdjustment, Long> {

    List<CommissionAdjustment> findByDateBetweenOrderByDateAscIdAsc(LocalDate from, LocalDate to);
}
