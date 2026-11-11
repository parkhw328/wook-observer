package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.ServiceCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceClassifierTest {
    @Test
    void classifiesDatabaseImageAndUsesExplicitOverrideFirst() {
        var properties = new ObserverProperties();
        var classifier = new ServiceClassifier(properties);
        assertThat(classifier.classify("container:db", "registry.local/team/postgres:17").category())
                .isEqualTo(ServiceCategory.DATABASE);
        properties.getServiceOverrides().put("pid:321", ServiceCategory.MESSAGING);
        assertThat(classifier.classify("pid:321", "java").category()).isEqualTo(ServiceCategory.MESSAGING);
        assertThat(classifier.classify("pid:321", "java").evidence()).contains("설정");
    }

    @Test
    void preservesUnknownServicesAsOther() {
        assertThat(new ServiceClassifier(new ObserverProperties()).classify("pid:1", "unknown-daemon").category())
                .isEqualTo(ServiceCategory.OTHER);
    }
}
