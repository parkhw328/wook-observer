package com.wook.observer.platform;

import com.wook.observer.collector.ServiceClassifier;
import com.wook.observer.config.ObserverProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class SshCollectorTest {
    private final SshCollector collector = new SshCollector(null,new ServiceClassifier(new ObserverProperties()));
    @Test
    void includesSystemInventoryInRemoteSnapshotWithPtyLineEndings() {
        var result = collector.parse("HOST\trhel\t5.14\tx86_64\t2\t1048576\r\nPROCESS\t2 1 root 1024 1 nginx\r\n"
                + "OSINFO\tkernel_release\tCOLLECTED\tNS4xNA==\r\nRPM\tbash\t0\t5.1\t1.el9\tx86_64\r\nRPM_STATUS\tCOLLECTED\r\nDONE\r\n");
        assertThat(result.systemInventory().rpm().packages()).hasSize(1);
        assertThat(result.systemInventory().os()).anyMatch(d -> d.value().equals("5.14"));
    }
    @Test
    void supportsNoJavaHostAndPreservesNamespaceBoundary() {
        var result=collector.parse("""
                HOST\tno-java\t6.1\tx86_64\t2\t1048576
                SCOPE\tcontainer
                PROCESS\t1 0 root 1024 1 nginx
                CONTAINER\t{"id":"abc","name":"/web","image":"nginx:stable","imageId":"sha256:abc","state":"running","pid":1,"ports":{},"networks":{},"mounts":[],"privileged":true,"user":"","readOnlyRootfs":false}
                CPIDS\tabc\t1,
                FACT\thost:os.id\tdebian
                DONE
                """);
        assertThat(result.javaProcesses()).isEmpty();
        assertThat(result.processes()).hasSize(1);
        assertThat(result.processes().getFirst().containerId()).isBlank();
        assertThat(result.facts()).containsEntry("container:abc:docker.privileged","true");
        assertThat(result.issues()).anyMatch(i->i.message().contains("namespace"));
    }
    @Test
    void filtersUnapprovedJvmPropertiesAndRejectsTruncatedOutput() {
        String text="HOST\tjava\t6.1\tx86_64\t2\t1048576\nPROCESS\t2 1 user 1024 1 java\nJAVA\t2\nJPROP\t2\tjava.version=21.0.13\nJPROP\t2\tdatabase.password=secret\nJPROP\t2\tjava.vmXname=secret\nFACT\thost:token\tsecret\nDONE\n";
        var result=collector.parse(text);
        assertThat(result.javaProcesses().getFirst().properties()).containsOnlyKeys("java.version");
        assertThat(result.facts()).isEmpty();
        assertThatThrownBy(()->collector.parse(text.replace("DONE\n",""))).isInstanceOf(ApiProblem.class);
    }
    @Test
    void recordsActualAccountScopeIncludingPtyLineEndings() {
        String snapshot = "HOST\thost\t6.1\tx86_64\t2\t1048576\r\nACCESS\t1000\toperator\r\nPROCESS\t2 1 operator 1024 1 java\r\nDONE\r\n";
        var result = collector.parse(snapshot);
        assertThat(result.facts()).containsEntry("host:effective_uid", "1000").containsEntry("host:effective_user", "operator");
        assertThat(result.issues()).anyMatch(issue -> issue.layer().equals("permission"));
        var root = collector.parse(snapshot.replace("ACCESS\t1000\toperator", "ACCESS\t0\troot"));
        assertThat(root.facts()).containsEntry("host:effective_uid", "0");
        assertThat(root.issues()).noneMatch(issue -> issue.layer().equals("permission"));
    }
}
