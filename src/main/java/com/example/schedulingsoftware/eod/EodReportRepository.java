package com.example.schedulingsoftware.eod;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EodReportRepository extends JpaRepository<EodReport, Long> {

    Optional<EodReport> findByStoreIdAndDate(Long storeId, LocalDate date);

    List<EodReport> findByStoreAreaIdAndDateBetweenOrderByDateAscStoreNameAsc(Long areaId, LocalDate from, LocalDate to);

    List<EodReport> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);
}
