package com.wook.observer.platform;

import com.wook.observer.ReportFixtures;
import com.wook.observer.model.Observation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssessorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test
    void matchesMavenFixedBoundariesAndMultipleBranches() {
        var affected=json.readTree("""
                {"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"2.0-beta9"},{"fixed":"2.3.1"},{"introduced":"2.4"},{"fixed":"2.12.2"},{"introduced":"2.13"},{"fixed":"2.15"}]}]}
                """);
        for(String version:List.of("2.0-beta9","2.3","2.12.1","2.14.1")) assertThat(Assessor.matches(version,affected)).isEqualTo(Assessor.Match.YES);
        for(String version:List.of("2.0-beta8","2.3.1","2.12.2","2.15.0","3.0")) assertThat(Assessor.matches(version,affected)).isEqualTo(Assessor.Match.NO);
    }
    @Test
    void unknownRangesOrUnlistedVersionsDoNotImplySafety() {
        assertThat(Assessor.matches("2",json.readTree("{\"versions\":[\"1\"]}"))).isEqualTo(Assessor.Match.UNKNOWN);
        assertThat(Assessor.matches("2",json.readTree("{\"ranges\":[{\"type\":\"GIT\",\"events\":[{\"introduced\":\"0\"}]}]}"))).isEqualTo(Assessor.Match.UNKNOWN);
        assertThat(Assessor.matches("1",json.readTree("{\"versions\":[\"1\"]}"))).isEqualTo(Assessor.Match.YES);
        assertThat(Assessor.matches("2",json.readTree("{\"ranges\":[{\"type\":\"ECOSYSTEM\",\"events\":[{\"introduced\":\"1\"},{\"last_affected\":\"2\"}]}]}"))).isEqualTo(Assessor.Match.YES);
    }
    @Test
    void identifiesRealSeedCveAndScopesCceToMatchingOperatingSystem() throws Exception {
        var seed=json.readTree(getClass().getResourceAsStream("/catalog/bootstrap.json"));
        var sample=ReportFixtures.sample();
        var library=new Observation.Library("org.apache.logging.log4j:log4j-core","2.14.1","/app/log4j-core.jar","Maven pom.properties","unknown","presence only");
        var input=new Observation(sample.version(),sample.collectedAt(),sample.host(),sample.processes(),
                List.of(new Observation.JavaInfo(sample.processes().getFirst().pid(),"ok",Map.of(),List.of(library))),sample.containers(),sample.issues(),
                Map.of("host:os.id","rhel","host:os.version","9.4","host:ssh.permitrootlogin","yes"),null);
        var result=new Assessor(null).assess(input,new Catalog.Snapshot(1,"2026-11-11T00:00:00Z",seed.path("records").valueStream().toList()));
        assertThat(result.assessment().findings()).anyMatch(f->"CVE-2021-44228".equals(f.id()) && "발견".equals(f.status()));
        assertThat(result.assessment().findings().stream().filter(f->"CVE-2021-44228".equals(f.id()))).hasSize(1);
        assertThat(result.assessment().findings()).anyMatch(f->"CCE-90800-4".equals(f.id()) && "발견".equals(f.status()));
        assertThat(result.assessment().findings()).noneMatch(f->"CCE-80901-2".equals(f.id()));
        assertThat(result.assessment().coverage()).anyMatch(c->"CCE-80901-2".equals(c.packageName()) && "비대상".equals(c.status()));
    }
    @Test
    void withdrawnAdvisoryIsNotReportedAsFound() {
        var sample=ReportFixtures.sample();
        var record=json.readTree("{\"id\":\"TEST-withdrawn\",\"withdrawn\":\"2026-01-01T00:00:00Z\",\"affected\":[]}");
        var result=new Assessor(null).assess(sample,new Catalog.Snapshot(2,"2026-11-11T00:00:00Z",List.of(record)));
        assertThat(result.assessment().findings()).noneMatch(f->"발견".equals(f.status()));
    }
    @Test
    void distinguishesConfirmedMavenFromFilenameCandidateAndSkipsAmbiguity() {
        var record = advisory("GHSA-fixture", "org.example:library");
        var exact = assess(new Observation.Library("org.example:library", "1.5", "/lib/library-1.5.jar", "META-INF/maven pom.properties", "", ""), List.of(record));
        assertThat(exact.assessment().findings()).singleElement().satisfies(f -> {
            assertThat(f.status()).isEqualTo("발견");
            assertThat(f.id()).isEqualTo("CVE-2026-99999");
            assertThat(f.aliases()).contains("GHSA-fixture");
            assertThat(f.recommendation()).contains("2.0");
        });
        var filename = new Observation.Library("library", "1.5", "/app.jar!/BOOT-INF/lib/library-1.5.jar", "파일명 추정", "", "");
        assertThat(assess(filename, List.of(record)).assessment().findings()).singleElement().satisfies(f -> assertThat(f.status()).isEqualTo("의심"));
        var ambiguous = assess(filename, List.of(record, advisory("GHSA-other", "other.group:library")));
        assertThat(ambiguous.assessment().findings()).isEmpty();
        assertThat(ambiguous.assessment().coverage()).singleElement().satisfies(c -> assertThat(c.reason()).isEqualTo("Maven 후보 다수"));
        var inconsistent = new Observation.Library("library", "1.4", "/library-1.5.jar", "META-INF/MANIFEST.MF", "", "");
        assertThat(assess(inconsistent, List.of(record)).assessment().coverage()).singleElement().satisfies(c -> assertThat(c.reason()).contains("불일치"));
        var fixed = new Observation.Library("org.example:library", "2.0", "/library.jar", "Maven pom.properties", "", "");
        var safe = assess(fixed, List.of(record));
        assertThat(safe.assessment().findings()).isEmpty();
        assertThat(safe.assessment().coverage()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("미발견"));
        assertThat(assess(fixed, List.of()).assessment().coverage()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo("미판정"));
    }

    @Test
    void configurationMismatchIsActionableOnlyOnKnownApplicablePlatform() {
        var base = ReportFixtures.sample();
        var rule = json.readTree("""
                {"kind":"CCE","id":"CCE-12345-6","platform":"rhel:9","check":"ssh.permitrootlogin","expected":"no","title":"root login"}
                """);
        for (String mode : List.of("match", "mismatch", "missing", "unknown", "other")) {
            var facts = new java.util.HashMap<String,String>();
            if (!mode.equals("unknown")) { facts.put("host:os.id", mode.equals("other") ? "ubuntu" : "rhel"); facts.put("host:os.version", "9.4"); }
            if (!mode.equals("missing")) { facts.put("host:ssh.permitrootlogin", mode.equals("match") ? "no" : "yes"); }
            var input = new Observation(base.version(),base.collectedAt(),base.host(),List.of(),List.of(),List.of(),List.of(),facts,null);
            var result = new Assessor(null).assess(input, new Catalog.Snapshot(1,"",List.of(rule))).assessment();
            assertThat(result.findings()).hasSize(mode.equals("mismatch") ? 1 : 0);
            String state = switch(mode) { case "match" -> "미발견"; case "mismatch" -> "발견"; case "other" -> "비대상"; default -> "미판정"; };
            assertThat(result.coverage()).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(state));
        }
    }

    @Test
    void rejectsMalformedRangesEvenWhenTheirInitialIntervalWouldMatch() {
        var malformed = json.readTree("""
                {"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"2"},{"fixed":"3"}]}]}
                """);
        assertThat(Assessor.matches("1",malformed)).isEqualTo(Assessor.Match.UNKNOWN);
    }

    private tools.jackson.databind.JsonNode advisory(String id, String name) {
        return json.readTree("""
                {"id":"%s","aliases":["CVE-2026-99999"],"affected":[{"package":{"ecosystem":"Maven","name":"%s"},"ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"1.0"},{"fixed":"2.0"}]}]}]}
                """.formatted(id,name));
    }

    private Observation assess(Observation.Library library, List<tools.jackson.databind.JsonNode> records) {
        var base = ReportFixtures.sample();
        var input = new Observation(base.version(),base.collectedAt(),base.host(),base.processes(),
                List.of(new Observation.JavaInfo(123,"",Map.of(),List.of(library))),List.of(),List.of(),Map.of(),null);
        return new Assessor(null).assess(input,new Catalog.Snapshot(1,"",records));
    }

}
