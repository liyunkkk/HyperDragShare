package com.leaf.hyperdragshare.codex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, offline key-information extractor for the BigBang segmentation surface.
 *
 * <p>Given an arbitrary chunk of text it pulls out the high-frequency "I just want to
 * copy this one string" items: phone numbers, emails, URLs, verification codes,
 * courier tracking numbers, money amounts and date/time expressions. Everything is
 * local regex work &mdash; no network, no native, no side effects.
 */
final class InfoExtractor {

    /** A single named category of matches (e.g. "手机号") with its de-duplicated values. */
    static final class Category {
        final String label;
        final List<String> values;

        Category(String label, List<String> values) {
            this.label = label;
            this.values = values;
        }
    }

    // Chinese mainland mobile numbers: 1 + [3-9] + 9 digits, not glued to more digits.
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern URL =
            Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s\\u4e00-\\u9fa5]+");
    // 4-8 digit standalone codes, typically verification codes.
    private static final Pattern CODE =
            Pattern.compile("(?<!\\d)\\d{4,8}(?!\\d)");
    // Courier tracking numbers: 10-18 chars, letters+digits, at least one digit.
    private static final Pattern TRACKING =
            Pattern.compile("(?<![A-Za-z0-9])(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{10,18}(?![A-Za-z0-9])");
    // Money: ￥/$/¥ prefixes or a number followed by 元/块/万; keeps decimals.
    private static final Pattern MONEY =
            Pattern.compile("(?:[￥$¥]\\s?\\d+(?:[.,]\\d+)?)|(?:\\d+(?:[.,]\\d+)?\\s?(?:元|块|万))");
    // Dates & times: 2024-01-02 / 2024年1月2日 / 12:30 / 下午3点 etc.
    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}[-/年]\\d{1,2}[-/月]\\d{1,2}日?"
                    + "|\\d{1,2}[:：]\\d{2}(?:[:：]\\d{2})?"
                    + "|(?:上午|下午|中午|凌晨|晚上)?\\d{1,2}[点时](?:\\d{1,2}分?)?");

    private InfoExtractor() {}

    /**
     * Runs all extractors over {@code text} and returns only the non-empty categories,
     * in a stable, useful display order. Never returns {@code null}.
     */
    static List<Category> extract(String text) {
        List<Category> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        // Phone numbers first so their digits are not mistaken for bare codes.
        List<String> phones = matchAll(PHONE, text);
        addIfAny(result, "手机号", phones);
        addIfAny(result, "邮箱", matchAll(EMAIL, text));
        addIfAny(result, "网址", matchAll(URL, text));
        addIfAny(result, "金额", matchAll(MONEY, text));
        addIfAny(result, "日期时间", matchAll(DATE_TIME, text));

        // Verification codes: exclude anything already captured as a phone number
        // (avoids a phone's trailing digits showing up as a "code").
        List<String> codes = matchAll(CODE, text);
        Set<String> phoneSet = new LinkedHashSet<>(phones);
        codes.removeIf(phoneSet::contains);
        addIfAny(result, "验证码/数字", codes);

        // Tracking numbers must contain a letter OR be longer than a code to avoid
        // duplicating pure verification codes already listed above.
        List<String> tracking = matchAll(TRACKING, text);
        tracking.removeIf(v -> v.length() <= 8 && v.chars().allMatch(Character::isDigit));
        addIfAny(result, "快递单号", tracking);

        return result;
    }

    private static List<String> matchAll(Pattern pattern, String text) {
        // LinkedHashSet: de-duplicate while preserving first-seen order.
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group().trim();
            if (!value.isEmpty()) {
                seen.add(value);
            }
        }
        return new ArrayList<>(seen);
    }

    private static void addIfAny(List<Category> out, String label, List<String> values) {
        if (values != null && !values.isEmpty()) {
            out.add(new Category(label, values));
        }
    }
}
