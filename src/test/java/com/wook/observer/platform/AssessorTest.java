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
        assertThat(result.assessment().findings()).anyMatch(f->"CCE-80901-2".equals(f.id()) && "점검 불가".equals(f.status()));
    }
    @Test
    void withdrawnAdvisoryIsNotReportedAsFound() {
        var sample=ReportFixtures.sample();
        var record=json.readTree("{\"id\":\"TEST-withdrawn\",\"withdrawn\":\"2026-01-01T00:00:00Z\",\"affected\":[]}");
        var result=new Assessor(null).assess(sample,new Catalog.Snapshot(2,"2026-11-11T00:00:00Z",List.of(record)));
        assertThat(result.assessment().findings()).noneMatch(f->"발견".equals(f.status()));
    }
}
