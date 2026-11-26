package com.github.orenaidaucac.royPodium.podium;

import lombok.Getter;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a value-placeholder condition such as {@code >= 5}, {@code < 100}, or {@code != 0}.
 */
@Getter
public final class ConditionSpec {

    public enum Operator {
        EQ("="),
        NE("!="),
        GT(">"),
        LT("<"),
        GTE(">="),
        LTE("<=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }
    }

    private static final Pattern CONDITION_PATTERN = Pattern.compile(
            "^\\s*(==|!=|>=|<=|=|>|<|gte|gt|lte|lt|eq|ne)\\s*(.+?)\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    private final String raw;
    private final Operator operator;
    private final String rawThreshold;
    private final BigDecimal numericThreshold;

    private ConditionSpec(String raw, Operator operator, String rawThreshold, BigDecimal numericThreshold) {
        this.raw = raw;
        this.operator = operator;
        this.rawThreshold = rawThreshold;
        this.numericThreshold = numericThreshold;
    }

    /**
     * Parses a condition expression. Returns empty if syntax or numeric requirement is invalid.
     */
    public static Optional<ConditionSpec> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher m = CONDITION_PATTERN.matcher(raw);
        if (!m.matches()) {
            return Optional.empty();
        }
        Operator op = parseOperator(m.group(1));
        if (op == null) {
            return Optional.empty();
        }
        String thresholdStr = m.group(2).trim();
        BigDecimal numeric = parseNumber(thresholdStr).orElse(null);

        // Ordering operators require a numeric threshold
        if (numeric == null && op != Operator.EQ && op != Operator.NE) {
            return Optional.empty();
        }
        return Optional.of(new ConditionSpec(raw.trim(), op, thresholdStr, numeric));
    }

    /**
     * Evaluates this condition against {@code rawValue} at snapshot time.
     */
    public EvaluationResult evaluate(String rawValue) {
        String val = rawValue == null ? "" : rawValue.trim();

        if (numericThreshold != null) {
            Optional<BigDecimal> parsedVal = parseNumber(val);
            if (parsedVal.isEmpty()) {
                return new EvaluationResult(false,
                        "Non-numeric value '" + val + "' for condition '" + raw + "'");
            }
            int cmp = parsedVal.get().compareTo(numericThreshold);
            boolean passed = switch (operator) {
                case EQ -> cmp == 0;
                case NE -> cmp != 0;
                case GT -> cmp > 0;
                case LT -> cmp < 0;
                case GTE -> cmp >= 0;
                case LTE -> cmp <= 0;
            };
            return passed
                    ? EvaluationResult.PASS
                    : new EvaluationResult(false, "Failed condition '" + raw + "' (value: " + val + ")");
        }

        // Non-numeric equality/inequality comparison
        boolean equals = val.equalsIgnoreCase(rawThreshold);
        boolean passed = operator == Operator.EQ ? equals : !equals;
        return passed
                ? EvaluationResult.PASS
                : new EvaluationResult(false, "Failed condition '" + raw + "' (value: " + val + ")");
    }

    private static Operator parseOperator(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "=", "==", "eq" -> Operator.EQ;
            case "!=", "ne" -> Operator.NE;
            case ">", "gt" -> Operator.GT;
            case "<", "lt" -> Operator.LT;
            case ">=", "gte" -> Operator.GTE;
            case "<=", "lte" -> Operator.LTE;
            default -> null;
        };
    }

    public static Optional<BigDecimal> parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        // Strip grouping commas and whitespace (e.g., "1,250.50" -> "1250.50")
        String cleaned = raw.trim().replace(",", "").replace(" ", "");
        try {
            return Optional.of(new BigDecimal(cleaned));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public record EvaluationResult(boolean passed, String failureReason) {
        public static final EvaluationResult PASS = new EvaluationResult(true, "");
    }
}
