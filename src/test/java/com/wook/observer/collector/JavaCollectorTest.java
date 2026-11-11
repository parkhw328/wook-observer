package com.wook.observer.collector;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaCollectorTest {
    @Test
    void retainsOnlyApprovedJvmProperties() throws Exception {
        var values = JavaCollector.filterProperties("""
                123:
                java.version=21.0.13
                java.class.path=/app/service.jar
                database.password=secret
                api.token=secret-token
                sun.java.command=Main --password=secret
                """);
        assertThat(values).containsEntry("java.version", "21.0.13").containsEntry("java.class.path", "/app/service.jar");
        assertThat(values).doesNotContainKeys("database.password", "api.token", "sun.java.command");
    }

    @Test
    void extractsClasspathBeforeApplicationArgumentsOnly() {
        assertThat(JavaCollector.classPathsFromArguments(List.of("java", "-Xmx1g", "--add-opens",
                "java.base/java.lang=ALL-UNNAMED", "-cp", "/a.jar:/lib/*", "Main", "-jar", "/secret.jar")))
                .containsExactly("/a.jar", "/lib/*");
    }

    @Test
    void handlesExecutableJarAndDoesNotExposeApplicationSecrets() {
        assertThat(JavaCollector.classPathsFromArguments(List.of("java", "-Dpassword=secret", "-jar", "app.jar", "--token=secret")))
                .containsExactly("app.jar");
    }

    @Test
    void handlesModuleAndEqualsStyleClasspathOptions() {
        assertThat(JavaCollector.classPathsFromArguments(List.of("java", "--module-path=/modules/a.jar:/modules/b.jar", "-m", "app/Main")))
                .containsExactly("/modules/a.jar", "/modules/b.jar");
    }
}
