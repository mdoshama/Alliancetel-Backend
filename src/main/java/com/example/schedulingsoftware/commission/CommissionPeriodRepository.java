package com.example.schedulingsoftware.commission;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionPeriodRepository extends JpaRepository<CommissionPeriod, Long> {

    Optional<CommissionPeriod> findByMonth(String month);
}
