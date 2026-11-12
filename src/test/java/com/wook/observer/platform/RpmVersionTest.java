package com.wook.observer.platform;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class RpmVersionTest {
    @Test
    void agreesWithNativeRpmOnFourHundredRecordedComparisons() throws Exception {
        try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/catalog/rpm-ordering.tsv"), java.nio.charset.StandardCharsets.UTF_8))) {
            var rows = input.lines().filter(line -> !line.startsWith("#")).toList();
            assertThat(rows).hasSize(400);
            for (String row : rows) {
                String[] values = row.split("\t");
                assertThat(RpmVersion.parse(values[0]).compareTo(RpmVersion.parse(values[1])))
                        .as("%s versus %s", values[0], values[1]).isEqualTo(Integer.parseInt(values[2]));
            }
        }
    }

    @Test
    void ordersEpochVersionReleaseAndRpmSegments() {
        String[][] pairs = {
                {"0:3.0.1-43.el9_0", "1:3.0.1-43.el9_0"},
                {"1:3.0.1-42.el9_0", "1:3.0.1-43.el9_0"},
                {"1:3.0.1-43.el9_0.1", "1:3.0.1-43.el9_0.10"},
                {"1.0~rc1-1", "1.0-1"}, {"1.0-1", "1.0^git20260101-1"},
                {"1.0^git20260101-1", "1.0.1-1"}, {"1.0~beta1-1", "1.0~rc1-1"},
                {"1a-1", "1.0-1"}, {"1-1", "1a-1"}, {"1.9-1", "1.10-1"},
                {"99999999999999999999:1-1", "100000000000000000000:1-1"},
                {"2:0.9-99", "2:1.0-1"}, {"1-1.el8", "1-1.el8_10"},
                {"1.0^git1~pre-1", "1.0^git1-1"}
        };
        for (String[] pair : pairs) {
            assertThat(RpmVersion.parse(pair[0]).compareTo(RpmVersion.parse(pair[1]))).as("%s < %s", (Object[]) pair).isNegative();
            assertThat(RpmVersion.parse(pair[1]).compareTo(RpmVersion.parse(pair[0]))).isPositive();
        }
        for (String[] pair : new String[][]{{"1.01-1", "0:1.1-1"}, {"1+.+0-01", "1.0-1"}, {"001:01-001", "1:1-1"}}) {
            assertThat(RpmVersion.parse(pair[0]).compareTo(RpmVersion.parse(pair[1]))).isZero();
        }
    }

    @Test
    void rejectsVersionsThatCannotBeComparedWithoutGuessing() {
        for (String value : new String[]{"", ":1-1", "-1:1-1", "1:-1", "1-", "1-1-1", "1:1:1", "1 0-1", "1/0-1", "1-한글", ".-1"}) {
            assertThatThrownBy(() -> RpmVersion.parse(value)).as(value).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
