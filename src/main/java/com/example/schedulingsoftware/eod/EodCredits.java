package com.example.schedulingsoftware.eod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.example.schedulingsoftware.employee.Employee;

/**
 * The credit ledger: who gets credit for what on an end-of-day report. This is the one place the rules live, so the
 * EOD screen, the performance reports and (later) commission all count the same way:
 * <ul>
 * <li>A box always goes to the one rep who made the sale.</li>
 * <li>A sale's accessories go to the rep, split evenly with anyone the sale was shared with.</li>
 * <li>If the closer split the day's accessories by hand, that split decides the day's accessory credit instead.</li>
 * <li>Leftover cents go to the first person, so the parts always add up to the whole.</li>
 * </ul>
 */
public final class EodCredits {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private EodCredits() {
    }

    /**
     * One credit to one person. {@code lineId} is null for a share of the day's hand-made accessory split.
     *
     * @param shared the sale's accessories were split with someone (or this is the person's part of it)
     */
    public record Credit(Long employeeId, String employeeName, Long reportId, Long storeId, String storeName,
            LocalDate date, EodStatus status, Long lineId, EodLineType type, EodCategory category, String description,
            int boxes, BigDecimal accessories, BigDecimal soldFor, boolean shared, boolean rep,
            /** The store's sales tax rate (percent) on this sale, for taking tax off accessories. */
            BigDecimal taxRate) {
    }

    public static List<Credit> of(EodReport r) {
        List<Credit> credits = new ArrayList<>();
        boolean splitByHand = !r.getAccessorySplit().isEmpty();
        for (EodLine l : r.getLines()) {
            List<Employee> people = new ArrayList<>();
            people.add(l.getRep());
            people.addAll(l.getSharedWith());
            boolean shared = people.size() > 1;
            List<BigDecimal> parts = splitByHand ? null : split(l.getAccessories(), people.size());
            for (int i = 0; i < people.size(); i++) {
                Employee p = people.get(i);
                boolean isRep = i == 0;
                if (!isRep && splitByHand) {
                    continue;
                }
                credits.add(new Credit(p.getId(), p.getFullName(), r.getId(), r.getStore().getId(),
                        r.getStore().getName(), r.getDate(), r.getStatus(), l.getId(), l.getType(), l.getCategory(),
                        l.getDescription(), isRep && l.getCategory() != null ? 1 : 0,
                        splitByHand ? ZERO : parts.get(i), isRep ? l.getSoldFor() : ZERO, shared && !splitByHand,
                        isRep, l.getTaxRate()));
            }
        }
        for (EodAccessoryShare s : r.getAccessorySplit()) {
            Employee p = s.getEmployee();
            credits.add(new Credit(p.getId(), p.getFullName(), r.getId(), r.getStore().getId(), r.getStore().getName(),
                    r.getDate(), r.getStatus(), null, EodLineType.ACCESSORY, null, "Share of the day's accessories", 0,
                    s.getAmount(), ZERO, true, false, r.getStore().getTaxRate()));
        }
        return credits;
    }

    /** {@code whole} in {@code n} even parts to 2 decimals; the first part takes the remainder. */
    public static List<BigDecimal> split(BigDecimal whole, int n) {
        if (n <= 1) {
            return List.of(whole);
        }
        BigDecimal part = whole.divide(BigDecimal.valueOf(n), 2, RoundingMode.DOWN);
        List<BigDecimal> parts = new ArrayList<>();
        parts.add(whole.subtract(part.multiply(BigDecimal.valueOf(n - 1))));
        for (int i = 1; i < n; i++) {
            parts.add(part);
        }
        return parts;
    }
}
