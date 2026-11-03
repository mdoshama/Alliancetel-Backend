package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.schedulingsoftware.common.InputRules;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class EodDtos {

    private EodDtos() {
    }

    /**
     * A sale to log (or the new version of one). For a phone or service sale: category, description, QPAY, subtotal
     * (as written on the receipt) and what the customer paid (QPAY + subtotal + any accessories). For an accessory sale only the amount ({@code soldFor}) is needed.
     *
     * @param repId who made the sale and gets the box (one person only); null = the signed-in user
     * @param deviceId a device from the device list: its name becomes the description (its list price is kept for
     *        reference)
     * @param shareWith colleagues who split this sale's accessories evenly with the rep (up to 3); the box stays with
     *        the rep
     */
    public record LineRequest(
            @NotNull Long storeId,
            @NotNull LocalDate date,
            @NotNull EodLineType type,
            EodCategory category,
            @Size(max = 120) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String description,
            @DecimalMin("0") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) BigDecimal qpay,
            @DecimalMin("0") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) BigDecimal subtotal,
            @NotNull @DecimalMin("0") @DecimalMax("100000") @Digits(integer = 6, fraction = 2) BigDecimal soldFor,
            @Size(max = 30) @Pattern(regexp = InputRules.SAFE_TEXT, message = InputRules.SAFE_TEXT_MSG) String customerRef,
            @Pattern(regexp = "^$|^\\d{4}$", message = "must be the last 4 digits of the IMEI") String imeiLast4,
            @Size(max = 20) @Pattern(regexp = InputRules.PHONE_CHARS, message = InputRules.PHONE_CHARS_MSG) String phone,
            Long repId,
            Long deviceId,
            @Size(max = 3, message = "can be shared with up to 3 people") List<Long> shareWith) {
    }

    /** One person's part of the day's accessories. */
    public record SplitShare(@NotNull Long employeeId,
            @NotNull @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal amount) {
    }

    /**
     * Splits the day's accessories by hand. The amounts must add up to the day's accessory total. An empty list goes
     * back to crediting each accessory to whoever sold it.
     */
    public record SplitRequest(@NotNull Long storeId, @NotNull LocalDate date,
            @NotNull @Size(max = 30) List<@jakarta.validation.Valid SplitShare> shares) {
    }

    /** Someone who can be picked as the rep or to split with; {@code scheduledHere} = on the schedule at this store. */
    public record Colleague(Long id, String name, boolean scheduledHere) {
    }

    /**
     * A person on the day's team: scheduled at the store that day, or with a sale or accessory credit there. Boxes and
     * accessories are their credit for the day.
     */
    public record TeamMember(Long id, String name, boolean scheduled, int boxes, BigDecimal accessories) {
    }

    /** The money counted at close. Blank amounts count as zero. */
    public record ClosingRequest(
            @NotNull Long storeId,
            @NotNull LocalDate date,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal qpayTotal,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal cash,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal credit,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal finance,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal layawayRedeem,
            @DecimalMin("0") @DecimalMax("1000000") @Digits(integer = 7, fraction = 2) BigDecimal newLayaway,
            @Size(max = 500) String note) {
    }

    public record CategoryOption(EodCategory value, String label, String help, boolean needsDevice) {
    }

    public record LineResponse(
            Long id,
            EodLineType type,
            EodCategory category,
            String categoryLabel,
            String description,
            BigDecimal qpay,
            /** The device list's price, for reference (0 when typed by hand). */
            BigDecimal retail,
            /** Old sales only (tax used to be worked out); 0 on new ones. */
            BigDecimal tax,
            BigDecimal taxRate,
            /** As the rep wrote it. */
            BigDecimal subtotal,
            BigDecimal soldFor,
            /** sold for − QPAY − subtotal */
            BigDecimal accessories,
            String customerRef,
            String imeiLast4,
            String phone,
            Long repId,
            String repName,
            Long deviceId,
            /** Colleagues sharing the credit. */
            List<Person> sharedWith,
            Instant createdAt,
            /** The signed-in user may change or remove it. */
            boolean canEdit) {
    }

    public record Person(Long id, String name) {
    }

    public record SplitShareView(Long employeeId, String name, BigDecimal amount) {
    }

    /** Sales of one category that day. */
    public record CategoryCount(EodCategory category, String label, int count) {
    }

    /**
     * One rep's credit: boxes by category (a box always belongs to one person) and accessory dollars (which can be
     * split: per sale, or for the whole day at close).
     */
    public record RepSummary(Long repId, String repName, int boxes, List<CategoryCount> byCategory,
            BigDecimal accessories, BigDecimal soldFor, int sharedSales) {
    }

    /**
     * The day's figures: grand total = subtotals + accessories; total sale = grand total + QPAY report
     * + new layaway; total drop = cash + credit + finance + layaway redeem; over/short = drop − sale.
     */
    public record Totals(
            /** The sales' subtotals added up. */
            BigDecimal subtotal,
            BigDecimal accessories,
            BigDecimal grandTotal,
            /** QPAY on the logged sales (for reference; the day's QPAY is the report total). */
            BigDecimal lineQpay,
            BigDecimal qpayTotal,
            BigDecimal newLayaway,
            BigDecimal totalSale,
            BigDecimal cash,
            BigDecimal credit,
            BigDecimal finance,
            BigDecimal layawayRedeem,
            BigDecimal totalDrop,
            /** Positive = over, negative = short. */
            BigDecimal overShort,
            int boxes) {
    }

    public record ReportResponse(
            /** Null until the first sale or closing figure is saved. */
            Long id,
            Long storeId,
            String storeName,
            Long areaId,
            LocalDate date,
            EodStatus status,
            BigDecimal qpayTotal,
            BigDecimal cash,
            BigDecimal credit,
            BigDecimal finance,
            BigDecimal layawayRedeem,
            BigDecimal newLayaway,
            String note,
            String submittedBy,
            Instant submittedAt,
            String approvedBy,
            Instant approvedAt,
            /** Who last reopened it for corrections (null once it's submitted again). */
            String reopenedBy,
            Instant reopenedAt,
            List<LineResponse> lines,
            Totals totals,
            List<CategoryCount> categories,
            List<RepSummary> reps,
            /** The day's team (from the schedule, plus anyone with credit there) and each person's credit. */
            List<TeamMember> team,
            /** The day's accessories split by hand at close; empty = credited to whoever sold them. */
            List<SplitShareView> accessorySplit,
            /** Whether the signed-in user can add sales, enter the closing figures, submit, approve or reopen. */
            boolean canAddSales,
            boolean canClose,
            boolean canApprove,
            boolean canReopen,
            /** The signed-in user (the default rep). */
            Long myId) {
    }

    /** What an employee sees first: the store they're working at today (from the published schedule). */
    public record TodayResponse(LocalDate date, Long storeId, String storeName, String reason,
            List<StoreOption> stores, ReportResponse report) {
    }

    public record StoreOption(Long id, String name, String label) {
    }

    /** One store's EOD state on a day, for the manager's overview. */
    public record StoreDay(LocalDate date, Long storeId, String storeName, String storeLabel, Long reportId, EodStatus status,
            int sales, BigDecimal totalSale, BigDecimal totalDrop, BigDecimal overShort, String submittedBy,
            Instant submittedAt) {
    }

    public record DayOverview(LocalDate date, List<StoreDay> stores, int submitted, int missing) {
    }

    /** A month of EODs for an area (the old SUMMARY tab), plus each rep's counts for commission. */
    public record MonthSummary(String month, Long areaId, List<StoreDay> days, Totals totals,
            List<CategoryCount> categories, List<RepSummary> reps, int reports, int approved) {
    }
}
