package com.example.schedulingsoftware.performance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.example.schedulingsoftware.employee.Role;
import com.example.schedulingsoftware.eod.EodCategory;
import com.example.schedulingsoftware.eod.EodLineType;
import com.example.schedulingsoftware.eod.EodStatus;

/** Sales performance, built from the EOD credit ledger. Shaped so a commission plan can be applied to it later. */
public final class PerformanceDtos {

    private PerformanceDtos() {
    }

    public record CategoryBoxes(EodCategory category, String label, int boxes) {
    }

    /**
     * One person's numbers for a period. "Confirmed" counts only days a manager approved (what commission should be
     * paid on); the other figures include days still open or waiting for approval.
     *
     * @param shifts shifts worked on published schedules in the period
     * @param accessoriesPerShift accessories ÷ shifts (null with no shifts)
     */
    public record EmployeeStats(
            Long employeeId,
            String name,
            String employeeCode,
            Role role,
            String homeStoreName,
            boolean active,
            int boxes,
            List<CategoryBoxes> byCategory,
            BigDecimal accessories,
            BigDecimal soldFor,
            int sales,
            int sharedSales,
            int daysWithSales,
            int shifts,
            BigDecimal accessoriesPerShift,
            int confirmedBoxes,
            BigDecimal confirmedAccessories) {
    }

    /** One day of a series. */
    public record DayPoint(LocalDate date, int boxes, BigDecimal accessories, BigDecimal soldFor) {
    }

    public record Totals(int boxes, BigDecimal accessories, BigDecimal soldFor, int sales, int confirmedBoxes,
            BigDecimal confirmedAccessories, int reports, int approvedReports) {
    }

    public record Leaderboard(LocalDate from, LocalDate to, Long areaId, Long storeId, Totals totals,
            List<EmployeeStats> employees, List<DayPoint> days) {
    }

    /** One credit on a profile: a sale they made, a share of one, or a share of a day's accessories. */
    public record CreditItem(LocalDate date, String storeName, EodStatus status, EodLineType type,
            EodCategory category, String categoryLabel, String description, int boxes, BigDecimal accessories,
            BigDecimal soldFor, boolean shared, boolean rep, boolean daySplit) {
    }

    public record StoreStats(Long storeId, String storeName, int boxes, BigDecimal accessories, int sales) {
    }

    public record Profile(
            Long employeeId,
            String name,
            String employeeCode,
            Role role,
            String areaName,
            String homeStoreName,
            String email,
            boolean active,
            LocalDate from,
            LocalDate to,
            EmployeeStats stats,
            /** Every day of the period (zeros included), for charts. */
            List<DayPoint> days,
            List<StoreStats> stores,
            /** Newest first, up to 100. */
            List<CreditItem> recent,
            /** Their rank by boxes in their area for the period (1 = top), and how many people were ranked. */
            int rank,
            int ranked) {
    }
}
