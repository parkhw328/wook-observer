package com.wook.observer;

public final class JavaVersionPolicy {
    private JavaVersionPolicy() {
    }

    public static void requireSupported(Runtime.Version version) {
        if (version.feature() != 21 || version.compareTo(Runtime.Version.parse("21.0.13")) < 0
                || version.pre().isPresent()) {
            throw new IllegalStateException("JDK 21.0.13 or newer within Java 21 is required; found " + version);
        }
    }
}
