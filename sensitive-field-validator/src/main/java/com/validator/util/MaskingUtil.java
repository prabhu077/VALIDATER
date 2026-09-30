package com.validator.util;

/**
 * Turns a raw sample value into a masked preview for display, based on the
 * sensitive type DiscoveryEngine assigned to it. Display-only -- this does
 * not touch the underlying data.
 */
public final class MaskingUtil {

    private MaskingUtil() {
    }

    public static String mask(String type, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (type == null) {
            return value;
        }

        switch (type) {
            case "EMAIL": {
                int at = value.indexOf('@');
                if (at < 0) {
                    return "***"; // not an address: never echo the raw value back
                }
                if (at <= 1) {
                    return "***" + value.substring(Math.max(at, 0));
                }
                return value.charAt(0) + "***" + value.substring(at);
            }
            case "PHONE":
                return value.length() > 2
                        ? "*".repeat(value.length() - 2) + value.substring(value.length() - 2)
                        : "**";
            case "CARD": {
                String digitsOnly = value.replaceAll("\\s", "");
                return digitsOnly.length() > 4
                        ? "*".repeat(digitsOnly.length() - 4) + digitsOnly.substring(digitsOnly.length() - 4)
                        : "****";
            }
            case "PAN":
                return value.length() == 10
                        ? value.substring(0, 5) + "****" + value.substring(9)
                        : "****";
            case "AADHAAR": {
                String digitsOnly = value.replaceAll("\\s", "");
                return digitsOnly.length() >= 4
                        ? "XXXX XXXX " + digitsOnly.substring(digitsOnly.length() - 4)
                        : "XXXX XXXX XXXX";
            }
            case "PASSWORD":
                return "*".repeat(8);
            case "GST":
                return value.length() > 6
                        ? value.substring(0, 2) + "*".repeat(value.length() - 6) + value.substring(value.length() - 4)
                        : "****";
            default:
                return value;
        }
    }
}
