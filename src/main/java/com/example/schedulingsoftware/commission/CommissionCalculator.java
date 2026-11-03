package com.example.schedulingsoftware.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns one person's month (boxes by category, accessories, fines and rewards) into pay, following the plan. Pure
 * arithmetic, no database: easy to test and to explain.
 */
public final class CommissionCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private CommissionCalculator() {
    }

    /**
     * What a person did in the month (approved EODs only). Free lines and reactivations are mapped by the plan.
     * {@code accessoriesAfterTax} is the accessories with each store's sales tax taken off (worked out per sale).
     */
    public record Month(int newActivations, int upgrades, int bts, int homeInternet, int unlock, int metroLocked,
            int freeLines, int reactivations, BigDecimal accessoriesGross, BigDecimal accessoriesAfterTax,
            BigDecimal fines, BigDecimal rewards) {
    }

    /** The pay, line by line. Fines are negative. */
    public record Breakdown(
            int activations, BigDecimal activationRate, BigDecimal activationPay,
            int upgrades, BigDecimal upgradeRate, BigDecimal upgradePay,
            int bts, BigDecimal btsPay,
            int homeInternet, BigDecimal homeInternetPay,
            int unlock, BigDecimal unlockPay,
            int metroLocked, BigDecimal metroLockedPay,
            BigDecimal accessoriesGross, BigDecimal accessoriesNet, BigDecimal accessoryPercent, BigDecimal accessoryPay,
            BigDecimal boxPay, BigDecimal fines, BigDecimal rewards, BigDecimal total) {
    }

    public static Breakdown calculate(CommissionPlan plan, Month m) {
        int activations = m.newActivations() + mapped(plan.getFreeLinePaysAs(), PaysAs.ACTIVATION, m.freeLines())
                + mapped(plan.getReactivationPaysAs(), PaysAs.ACTIVATION, m.reactivations());
        int upgrades = m.upgrades() + mapped(plan.getFreeLinePaysAs(), PaysAs.UPGRADE, m.freeLines())
                + mapped(plan.getReactivationPaysAs(), PaysAs.UPGRADE, m.reactivations());

        BigDecimal activationRate = activations <= plan.getActivationTier1Max() ? plan.getActivationTier1Rate()
                : activations <= plan.getActivationTier2Max() ? plan.getActivationTier2Rate()
                        : plan.getActivationTier3Rate();
        BigDecimal upgradeRate = upgrades <= plan.getUpgradeTier1Max() ? plan.getUpgradeTier1Rate()
                : plan.getUpgradeTier2Rate();
        BigDecimal activationPay = times(activationRate, activations);
        BigDecimal upgradePay = times(upgradeRate, upgrades);
        BigDecimal btsPay = times(plan.getBtsRate(), m.bts());
        BigDecimal hintPay = times(plan.getHomeInternetRate(), m.homeInternet());
        BigDecimal unlockPay = times(plan.getUnlockRate(), m.unlock());
        BigDecimal mlockPay = times(plan.getMetroLockedRate(), m.metroLocked());

        BigDecimal net = net(plan, m.accessoriesAfterTax());
        BigDecimal accPercent;
        BigDecimal accPay;
        if (plan.getAccessoryMode() == AccessoryMode.BRACKETS) {
            BigDecimal t1 = net.min(plan.getAccessoryTier1Max());
            BigDecimal t2 = net.min(plan.getAccessoryTier3From()).subtract(plan.getAccessoryTier1Max()).max(BigDecimal.ZERO);
            BigDecimal t3 = net.subtract(plan.getAccessoryTier3From()).max(BigDecimal.ZERO);
            accPay = pct(t1, plan.getAccessoryTier1Percent()).add(pct(t2, plan.getAccessoryTier2Percent()))
                    .add(pct(t3, plan.getAccessoryTier3Percent())).setScale(2, RoundingMode.HALF_UP);
            accPercent = net.signum() == 0 ? BigDecimal.ZERO
                    : accPay.multiply(HUNDRED).divide(net, 2, RoundingMode.HALF_UP);
        } else {
            accPercent = net.compareTo(plan.getAccessoryTier3From()) >= 0 ? plan.getAccessoryTier3Percent()
                    : net.compareTo(plan.getAccessoryTier1Max()) > 0 ? plan.getAccessoryTier2Percent()
                            : plan.getAccessoryTier1Percent();
            accPay = pct(net, accPercent).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal boxPay = activationPay.add(upgradePay).add(btsPay).add(hintPay).add(unlockPay).add(mlockPay);
        BigDecimal fines = money(m.fines());
        BigDecimal rewards = money(m.rewards());
        BigDecimal total = boxPay.add(accPay).add(fines).add(rewards);
        return new Breakdown(activations, money(activationRate), activationPay, upgrades, money(upgradeRate), upgradePay,
                m.bts(), btsPay, m.homeInternet(), hintPay, m.unlock(), unlockPay, m.metroLocked(), mlockPay,
                money(m.accessoriesGross()), net, accPercent.stripTrailingZeros(), accPay, boxPay, fines, rewards, total);
    }

    /** Accessories after cost: (after tax) × (1 − cost %). */
    public static BigDecimal net(CommissionPlan plan, BigDecimal afterTax) {
        BigDecimal afterCost = money(afterTax).multiply(HUNDRED.subtract(plan.getCostPercent())).divide(HUNDRED, 6,
                RoundingMode.HALF_UP);
        return afterCost.setScale(2, RoundingMode.HALF_UP).max(ZERO);
    }

    /** An amount that includes sales tax at {@code ratePercent}, without it: amount ÷ (1 + rate). */
    public static BigDecimal withoutTax(BigDecimal amount, BigDecimal ratePercent) {
        BigDecimal rate = ratePercent == null ? BigDecimal.ZERO : ratePercent;
        return money(amount).multiply(HUNDRED).divide(HUNDRED.add(rate), 6, RoundingMode.HALF_UP);
    }

    private static int mapped(PaysAs paysAs, PaysAs target, int count) {
        return paysAs == target ? count : 0;
    }

    private static BigDecimal times(BigDecimal rate, int count) {
        return rate.multiply(BigDecimal.valueOf(count)).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }
}
