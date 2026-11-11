package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.ServiceCategory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerCollectorTest {
    @Test
    void parsesContainerIdentityNetworksPortsAndProcessLinks() {
        var properties = new ObserverProperties();
        var collector = new DockerCollector(mock(CommandRunner.class), properties, new ServiceClassifier(properties));
        var node = JsonMapper.builder().build().readTree("""
                {"Id":"abc123", "Name":"/database", "Image":"sha256:actual", "ConfigImage":"postgres:17",
                 "Status":"running", "Pid":321,
                 "Ports":{"5432/tcp":[{"HostIp":"127.0.0.1","HostPort":"15432"}],"9000/tcp":null},
                 "Networks":{"backend":{"IPAddress":"172.18.0.2","GlobalIPv6Address":""}},
                 "Mounts":[{"Type":"volume","Source":"/vol/data","Destination":"/data","RW":true}],
                 "Env":["PASSWORD=not-for-report"]}
                """);
        var container = collector.parseContainer(node, List.of(321L, 322L));
        assertThat(container.category()).isEqualTo(ServiceCategory.DATABASE);
        assertThat(container.processIds()).containsExactly(321L, 322L);
        assertThat(container.ports()).contains("127.0.0.1:15432 -> 5432/tcp");
        assertThat(container.imageId()).isEqualTo("sha256:actual");
        assertThat(JsonMapper.builder().build().writeValueAsString(container)).doesNotContain("PASSWORD", "not-for-report");
    }

    @Test
    void unavailableDockerProducesAnIssueInsteadOfPretendingNoContainersExist() {
        var commands = mock(CommandRunner.class);
        when(commands.run(anyList())).thenReturn(new CommandRunner.Result(false, "", "접근 불가"));
        var properties = new ObserverProperties();
        var issues = new ArrayList<Issue>();
        var result = new DockerCollector(commands, properties, new ServiceClassifier(properties)).collect(issues);
        assertThat(result.containers()).isEmpty();
        assertThat(issues).anySatisfy(issue -> assertThat(issue.message()).contains("접근 불가"));
    }

    @Test
    void parsesHostPidsWithoutCollectingCommandArguments() {
        assertThat(DockerCollector.parseProcessIds("PID COMMAND\n123 java\n124 postgres\n123 java\n"))
                .containsExactly(123L, 124L);
    }
}
