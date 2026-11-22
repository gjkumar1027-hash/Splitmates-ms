package com.splitmates.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money helpers. Amounts are always integer paise internally (BR-01). */
public final class Money {
    private Money() {
    }

    public static long toPaise(BigDecimal rupees) {
        if (rupees == null) throw new IllegalArgumentException("Amount is required");
        if (rupees.scale() > 2 && rupees.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount can have at most 2 decimals");
        }
        return rupees.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
    }

    /** Indian number format, e.g. ₹1,00,000.00 (NFR-14). */
    public static String format(long paise) {
        long abs = Math.abs(paise);
        String rupees = String.valueOf(abs / 100);
        StringBuilder grouped = new StringBuilder();
        int len = rupees.length();
        if (len <= 3) {
            grouped.append(rupees);
        } else {
            String head = rupees.substring(0, len - 3);
            // Lakh/crore grouping: pairs of digits before the last three.
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) grouped.append(',');
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(rupees.substring(len - 3));
        }
        return (paise < 0 ? "-" : "") + "₹" + grouped + "." + String.format("%02d", abs % 100);
    }

    public static String plain(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }
}
