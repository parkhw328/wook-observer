package com.wook.observer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavaVersionPolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"21.0.13", "21.0.13+9", "21.0.14", "21.0.13.1"})
    void acceptsSupportedJava21Updates(String version) {
        assertThatCode(() -> JavaVersionPolicy.requireSupported(Runtime.Version.parse(version))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"21", "21.0.12.1", "21.0.13-ea", "17.0.16", "25.0.1"})
    void rejectsOldOrUnvalidatedJavaVersions(String version) {
        assertThatThrownBy(() -> JavaVersionPolicy.requireSupported(Runtime.Version.parse(version)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("21.0.13");
    }
}
