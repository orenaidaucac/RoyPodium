package com.github.orenaidaucac.royPodium.discord;

import com.github.orenaidaucac.royPodium.podium.ConditionSpec;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;

/**
 * Display-only formatter for raw PlaceholderAPI values (never affects conditions or reward commands).
 */
@Getter
@Builder
public final class ValueFormatSpec {

    public static final ValueFormatSpec DEFAULT = ValueFormatSpec.builder().build();

    @Builder.Default private final boolean grouping = true;
    /** -1 preserves natural decimals; >= 0 formats with fixed decimal places. */
    @Builder.Default private final int decimals = -1;
    @Builder.Default private final boolean compact = false;
    @Builder.Default private final String prefix = "";
    @Builder.Default private final String suffix = "";
    @Builder.Default private final boolean keepNonNumeric = true;
    @Builder.Default private final String fallback = "N/A";

    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final BigDecimal MILLION = new BigDecimal("1000000");
    private static final BigDecimal BILLION = new BigDecimal("1000000000");
    private static final BigDecimal TRILLION = new BigDecimal("1000000000000");

    public String format(String rawValue) {
        String safePrefix = prefix != null ? prefix : "";
        String safeSuffix = suffix != null ? suffix : "";
        String safeFallback = (fallback != null && !fallback.isBlank()) ? fallback : "N/A";

        if (rawValue == null || rawValue.isBlank()) {
            return safeFallback;
        }

        Optional<BigDecimal> parsed = ConditionSpec.parseNumber(rawValue);
        if (parsed.isEmpty()) {
            return keepNonNumeric ? (safePrefix + rawValue.trim() + safeSuffix) : safeFallback;
        }

        BigDecimal num = parsed.get();
        String body = compact ? formatCompact(num) : formatStandard(num);
        return safePrefix + body + safeSuffix;
    }

    private String formatCompact(BigDecimal num) {
        BigDecimal abs = num.abs();
        String unit = "";
        BigDecimal divisor = BigDecimal.ONE;

        if (abs.compareTo(TRILLION) >= 0) {
            divisor = TRILLION;
            unit = "T";
        } else if (abs.compareTo(BILLION) >= 0) {
            divisor = BILLION;
            unit = "B";
        } else if (abs.compareTo(MILLION) >= 0) {
            divisor = MILLION;
            unit = "M";
        } else if (abs.compareTo(THOUSAND) >= 0) {
            divisor = THOUSAND;
            unit = "K";
        }

        if (divisor.equals(BigDecimal.ONE)) {
            return formatStandard(num);
        }
        int scale = decimals >= 0 ? Math.min(decimals, 6) : 1;
        BigDecimal scaled = num.divide(divisor, scale, RoundingMode.HALF_UP);
        if (decimals < 0) {
            scaled = scaled.stripTrailingZeros();
        }
        return scaled.toPlainString() + unit;
    }

    private String formatStandard(BigDecimal num) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) {
            pattern.append('.');
            pattern.append("0".repeat(Math.min(decimals, 10)));
        } else if (decimals < 0) {
            BigDecimal stripped = num.stripTrailingZeros();
            int frac = Math.max(0, stripped.scale());
            if (frac > 0) {
                pattern.append('.');
                pattern.append("#".repeat(Math.min(frac, 10)));
            }
        }
        DecimalFormat df = new DecimalFormat(pattern.toString(), symbols);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(num);
    }
}
