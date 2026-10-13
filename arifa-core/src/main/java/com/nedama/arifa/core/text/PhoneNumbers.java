package com.nedama.arifa.core.text;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Converts phone numbers, as people actually type them into Ignition user profiles,
 * into the +E.164 format SMS providers expect.
 *
 * <p>Examples with the default country code 254 (Kenya):</p>
 * <ul>
 *   <li>{@code 0712 345 678} becomes {@code +254712345678}</li>
 *   <li>{@code 0110-123-456} becomes {@code +254110123456}</li>
 *   <li>{@code 254712345678} becomes {@code +254712345678}</li>
 *   <li>{@code 712345678} becomes {@code +254712345678}</li>
 *   <li>{@code 00254712345678} becomes {@code +254712345678}</li>
 *   <li>{@code +44 7911 123456} stays a UK number: {@code +447911123456}</li>
 * </ul>
 */
public final class PhoneNumbers {

    /** Kenya's country calling code. */
    public static final String KENYA = "254";

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern KENYA_MOBILE = Pattern.compile("^\\+254[17]\\d{8}$");
    private static final Pattern FORMATTING = Pattern.compile("[\\s\\-.()/]");
    private static final Pattern COUNTRY_CODE = Pattern.compile("^[1-9]\\d{0,2}$");

    private PhoneNumbers() {
    }

    /**
     * Converts a typed number to +E.164.
     *
     * @param raw                the number as stored in the user's contact info
     * @param defaultCountryCode digits only, for example "254"; used for local formats like 07XX
     * @return the number in +E.164 format
     * @throws IllegalArgumentException if the number cannot be converted; the message masks the number
     */
    public static String toE164(String raw, String defaultCountryCode) {
        if (raw == null) {
            throw new IllegalArgumentException("Phone number is missing");
        }
        if (defaultCountryCode == null || !COUNTRY_CODE.matcher(defaultCountryCode).matches()) {
            throw new IllegalArgumentException("Default country code must be 1 to 3 digits, for example 254");
        }
        String cleaned = FORMATTING.matcher(raw.trim()).replaceAll("");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Phone number is empty");
        }

        String candidate;
        if (cleaned.startsWith("+")) {
            candidate = cleaned;
        } else if (cleaned.startsWith("00")) {
            candidate = "+" + cleaned.substring(2);
        } else if (cleaned.startsWith("0")) {
            candidate = "+" + defaultCountryCode + cleaned.substring(1);
        } else if (cleaned.startsWith(defaultCountryCode) && cleaned.length() > defaultCountryCode.length() + 8) {
            candidate = "+" + cleaned;
        } else {
            candidate = "+" + defaultCountryCode + cleaned;
        }

        if (!E164.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Not a valid phone number: " + mask(cleaned));
        }
        if (candidate.startsWith("+" + KENYA) && !KENYA_MOBILE.matcher(candidate).matches()) {
            throw new IllegalArgumentException(
                    "Not a Kenyan mobile number (expected 07XX XXX XXX or 01XX XXX XXX): " + mask(candidate));
        }
        return candidate;
    }

    /** True if the text is a country calling code of 1 to 3 digits, for example "254". */
    public static boolean isValidCountryCode(String code) {
        return code != null && COUNTRY_CODE.matcher(code).matches();
    }

    /** Same as {@link #toE164}, but returns empty instead of throwing. */
    public static Optional<String> tryToE164(String raw, String defaultCountryCode) {
        try {
            return Optional.of(toE164(raw, defaultCountryCode));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Hides the middle of a number so it can be written to logs.
     * {@code +254712345678} becomes {@code +254712***678}.
     */
    public static String mask(String number) {
        if (number == null) {
            return "(none)";
        }
        int length = number.length();
        if (length <= 6) {
            return "***";
        }
        if (length >= 11) {
            return number.substring(0, 7) + "***" + number.substring(length - 3);
        }
        return number.substring(0, 2) + "***" + number.substring(length - 2);
    }
}
