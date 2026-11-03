package com.example.schedulingsoftware.commission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.example.schedulingsoftware.commission.CommissionCalculator.Breakdown;
import com.example.schedulingsoftware.commission.CommissionCalculator.Month;

/** The Sync Sphere compensation sheet, line by line. */
class CommissionCalculatorTest {

    private final CommissionPlan plan = new CommissionPlan();

    private static Month m(int act, int upg, String acc) {
        return new Month(act, upg, 0, 0, 0, 0, 0, 0, new BigDecimal(acc), new BigDecimal(acc), BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private static void money(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " was " + actual);
    }

    @Test
    void activationsPayByTheMonthsCount() {
        money("25.00", CommissionCalculator.calculate(plan, m(25, 0, "0")).activationPay()); // 25 × $1
        money("78.00", CommissionCalculator.calculate(plan, m(26, 0, "0")).activationPay()); // 26 × $3
        money("210.00", CommissionCalculator.calculate(plan, m(70, 0, "0")).activationPay()); // 70 × $3
        money("284.00", CommissionCalculator.calculate(plan, m(71, 0, "0")).activationPay()); // 71 × $4
    }

    @Test
    void upgradesPayByTheMonthsCount() {
        money("100.00", CommissionCalculator.calculate(plan, m(0, 25, "0")).upgradePay()); // 25 × $4
        money("130.00", CommissionCalculator.calculate(plan, m(0, 26, "0")).upgradePay()); // 26 × $5
    }

    @Test
    void flatRates() {
        Breakdown b = CommissionCalculator.calculate(plan,
                new Month(0, 0, 3, 2, 1, 1, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        money("3.00", b.btsPay()); // BTS $1
        money("10.00", b.homeInternetPay()); // T-Mobile Home Internet $5
        money("10.00", b.unlockPay()); // unlock $10
        money("10.00", b.metroLockedPay()); // Metro used phone $10
        money("33.00", b.boxPay());
    }

    @Test
    void accessoriesWholeAmountAtTheRateReached() {
        money("60.00", CommissionCalculator.calculate(plan, m(0, 0, "3000")).accessoryPay()); // 2%
        money("400.00", CommissionCalculator.calculate(plan, m(0, 0, "4000")).accessoryPay()); // 10%
        money("1050.00", CommissionCalculator.calculate(plan, m(0, 0, "7000")).accessoryPay()); // 15%
    }

    @Test
    void accessoriesEachBracketAtItsRate() {
        plan.setAccessoryMode(AccessoryMode.BRACKETS);
        // 3,000 × 2% + 1,000 × 10%
        money("160.00", CommissionCalculator.calculate(plan, m(0, 0, "4000")).accessoryPay());
        // 3,000 × 2% + 4,000 × 10% + 1,000 × 15%
        money("610.00", CommissionCalculator.calculate(plan, m(0, 0, "8000")).accessoryPay());
    }

    @Test
    void accessoriesAreAfterTaxAndCost() {
        plan.setCostPercent(new BigDecimal("40"));
        // $1,077.50 sold at a 7.75% store: $1,000 before tax.
        BigDecimal afterTax = CommissionCalculator.withoutTax(new BigDecimal("1077.50"), new BigDecimal("7.75"));
        money("1000.00", afterTax.setScale(2, java.math.RoundingMode.HALF_UP));
        Breakdown b = CommissionCalculator.calculate(plan, new Month(0, 0, 0, 0, 0, 0, 0, 0,
                new BigDecimal("1077.50"), afterTax, BigDecimal.ZERO, BigDecimal.ZERO));
        money("600.00", b.accessoriesNet()); // − 40% cost
        money("12.00", b.accessoryPay()); // 2%
    }

    @Test
    void freeLineAndReactPayOnlyIfTheAdminSaysSo() {
        Month month = new Month(20, 0, 0, 0, 0, 0, 10, 5, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
        assertEquals(20, CommissionCalculator.calculate(plan, month).activations());
        plan.setFreeLinePaysAs(PaysAs.ACTIVATION);
        plan.setReactivationPaysAs(PaysAs.UPGRADE);
        Breakdown b = CommissionCalculator.calculate(plan, month);
        assertEquals(30, b.activations()); // 30 activations → $3 tier
        money("90.00", b.activationPay());
        assertEquals(5, b.upgrades());
    }

    @Test
    void finesComeOffAndRewardsAddOn() {
        Breakdown b = CommissionCalculator.calculate(plan, new Month(10, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal("-75"), new BigDecimal("50")));
        money("-15.00", b.total()); // 10 × $1 − 75 + 50
    }
}
