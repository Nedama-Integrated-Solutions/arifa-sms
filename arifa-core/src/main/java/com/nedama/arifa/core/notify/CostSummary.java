package com.nedama.arifa.core.notify;

import com.nedama.arifa.core.sms.RecipientResult;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds up what the provider says an SMS cost, so every send can be logged with its price.
 *
 * <p>Africa's Talking reports a cost per recipient as text such as {@code KES 0.8000}. Only recipients that were
 * accepted are counted, because a refused message is not billed. A cost in an unexpected currency (for example
 * {@code USD 0.2200} where {@code KES 0.80} was expected) is the sign of a message billed as international traffic,
 * and the caller can raise it.</p>
 */
public final class CostSummary {

    private static final Pattern COST = Pattern.compile("^\\s*([A-Za-z]{3})\\s+(\\d+(?:\\.\\d+)?)\\s*$");

    private final Map<String, BigDecimal> totals;
    private final int unreported;

    private CostSummary(Map<String, BigDecimal> totals, int unreported) {
        this.totals = totals;
        this.unreported = unreported;
    }

    /** Totals the cost of the accepted recipients in these results. */
    public static CostSummary of(Collection<RecipientResult> results) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        int unreported = 0;
        for (RecipientResult result : results) {
            if (!result.accepted()) {
                continue;
            }
            Matcher matcher = result.cost() == null ? null : COST.matcher(result.cost());
            if (matcher == null || !matcher.matches()) {
                unreported++;
                continue;
            }
            String currency = matcher.group(1).toUpperCase(Locale.ROOT);
            totals.merge(currency, new BigDecimal(matcher.group(2)), BigDecimal::add);
        }
        return new CostSummary(totals, unreported);
    }

    /** True when no accepted recipient reported a cost. */
    public boolean isEmpty() {
        return totals.isEmpty();
    }

    /** How many accepted recipients came back without a readable cost. */
    public int unreported() {
        return unreported;
    }

    /** The currencies found, in order of first appearance. */
    public Set<String> currencies() {
        return totals.keySet();
    }

    /** The total in one currency, or zero. */
    public BigDecimal total(String currency) {
        return totals.getOrDefault(currency == null ? "" : currency.toUpperCase(Locale.ROOT), BigDecimal.ZERO);
    }

    /**
     * True when every reported cost is in this currency. Also true when nothing was reported, because there is
     * nothing to object to.
     */
    public boolean onlyCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return true;
        }
        String wanted = currency.trim().toUpperCase(Locale.ROOT);
        return totals.keySet().stream().allMatch(wanted::equals);
    }

    /** For the log: {@code KES 0.80}, or {@code KES 0.80 + USD 0.22}, or {@code cost not reported}. */
    public String describe() {
        if (totals.isEmpty()) {
            return "cost not reported";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, BigDecimal> entry : totals.entrySet()) {
            if (out.length() > 0) {
                out.append(" + ");
            }
            out.append(entry.getKey()).append(' ').append(format(entry.getValue()));
        }
        return out.toString();
    }

    private static String format(BigDecimal amount) {
        BigDecimal trimmed = amount.stripTrailingZeros();
        if (trimmed.scale() < 2) {
            trimmed = trimmed.setScale(2);
        }
        return trimmed.toPlainString();
    }
}
