package com.wook.observer.platform;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** RPM EVR ordering, including epoch, numeric segments, tilde and caret.
 * Specification: https://rpm.org/docs/6.0.x/man/rpm-version.7
 */
public record RpmVersion(BigInteger epoch, String version, String release) implements Comparable<RpmVersion> {
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9._+~^]{1,512}");

    public static RpmVersion parse(String value) {
        if (value == null || value.length() > 1100) { throw new IllegalArgumentException("Invalid RPM EVR"); }
        String[] epochParts = value.split(":", -1);
        if (epochParts.length > 2 || (epochParts.length == 2 && !epochParts[0].matches("[0-9]{1,30}"))) {
            throw new IllegalArgumentException("Invalid RPM epoch");
        }
        BigInteger epoch = epochParts.length == 2 ? new BigInteger(epochParts[0]) : BigInteger.ZERO;
        String[] parts = epochParts[epochParts.length - 1].split("-", -1);
        if (parts.length > 2 || !component(parts[0]) || (parts.length == 2 && !component(parts[1]))) {
            throw new IllegalArgumentException("Invalid RPM version or release");
        }
        return new RpmVersion(epoch, parts[0], parts.length == 2 ? parts[1] : "");
    }

    private static boolean component(String value) {
        return COMPONENT.matcher(value).matches() && value.chars().anyMatch(c -> digit((char) c) || alpha((char) c));
    }

    @Override
    public int compareTo(RpmVersion other) {
        int result = epoch.compareTo(other.epoch);
        if (result == 0) { result = segments(version, other.version); }
        if (result == 0) { result = segments(release, other.release); }
        return Integer.signum(result);
    }

    static int segments(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() || b < right.length()) {
            while (a < left.length() && separator(left.charAt(a))) { a++; }
            while (b < right.length() && separator(right.charAt(b))) { b++; }
            char x = a < left.length() ? left.charAt(a) : '\0';
            char y = b < right.length() ? right.charAt(b) : '\0';
            if (x == '~' || y == '~') {
                if (x != '~') { return 1; }
                if (y != '~') { return -1; }
                a++; b++; continue;
            }
            if (x == '^' || y == '^') {
                if (x == '\0') { return -1; }
                if (y == '\0') { return 1; }
                if (x != '^') { return 1; }
                if (y != '^') { return -1; }
                a++; b++; continue;
            }
            if (x == '\0' || y == '\0') { return x == y ? 0 : x == '\0' ? -1 : 1; }
            boolean numeric = digit(x);
            if (numeric != digit(y)) { return numeric ? 1 : -1; }
            int endA = a, endB = b;
            while (endA < left.length() && (numeric ? digit(left.charAt(endA)) : alpha(left.charAt(endA)))) { endA++; }
            while (endB < right.length() && (numeric ? digit(right.charAt(endB)) : alpha(right.charAt(endB)))) { endB++; }
            int startA = a, startB = b;
            if (numeric) {
                while (startA < endA && left.charAt(startA) == '0') { startA++; }
                while (startB < endB && right.charAt(startB) == '0') { startB++; }
                int length = Integer.compare(endA - startA, endB - startB);
                if (length != 0) { return length; }
            }
            int result = left.substring(startA, endA).compareTo(right.substring(startB, endB));
            if (result != 0) { return Integer.signum(result); }
            a = endA; b = endB;
        }
        return 0;
    }

    private static boolean digit(char value) { return value >= '0' && value <= '9'; }
    private static boolean alpha(char value) { return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z'; }
    private static boolean separator(char value) { return !digit(value) && !alpha(value) && value != '~' && value != '^'; }
}
