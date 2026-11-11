package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.Observation.Library;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryScannerTest {
    @TempDir Path directory;

    @Test
    @EnabledOnOs(OS.LINUX)
    void readsThroughProcessRootAndDeduplicatesAliasesByFileIdentity() throws Exception {
        Path jar = directory.resolve("service-1.2.3.jar");
        Files.write(jar, archive(Map.of()));
        Path alias = directory.resolve("alias.jar");
        Files.createSymbolicLink(alias, jar);
        var issues = new ArrayList<Issue>();
        var result = new LibraryScanner(new ObserverProperties()).scan(ProcessHandle.current().pid(),
                List.of(jar.toString(), alias.toString()), issues);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().version()).isEqualTo("1.2.3");
        assertThat(issues).isEmpty();
    }

    @Test
    void readsMavenCoordinatesAndNestedBootJarManifest() throws Exception {
        byte[] nested = archive(Map.of("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Title: library\r\nImplementation-Version: 4.2.3\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        Path jar = directory.resolve("app.jar");
        Files.write(jar, archive(Map.of(
                "META-INF/maven/com.example/app/pom.properties", "groupId=com.example\nartifactId=app\nversion=1.2.3\n".getBytes(StandardCharsets.UTF_8),
                "BOOT-INF/lib/library-0.0.1.jar", nested)));
        var libraries = new ArrayList<Library>();
        var issues = new ArrayList<Issue>();
        new LibraryScanner(new ObserverProperties()).inspect(jar, "/app/app.jar", libraries, issues, "123");
        assertThat(libraries).anySatisfy(library -> {
            assertThat(library.name()).isEqualTo("com.example:app");
            assertThat(library.version()).isEqualTo("1.2.3");
        }).anySatisfy(library -> {
            assertThat(library.name()).isEqualTo("library");
            assertThat(library.version()).isEqualTo("4.2.3");
            assertThat(library.evidence()).isEqualTo("META-INF/MANIFEST.MF");
        });
        assertThat(issues).isEmpty();
    }

    @Test
    void shadedJarRetainsAllMavenArtifactsWithoutMisidentifyingTheArchive() throws Exception {
        Path jar = directory.resolve("shaded.jar");
        Files.write(jar, archive(Map.of(
                "META-INF/maven/group/a/pom.properties", "artifactId=a\nversion=1.0\n".getBytes(StandardCharsets.UTF_8),
                "META-INF/maven/group/b/pom.properties", "artifactId=b\nversion=2.0\n".getBytes(StandardCharsets.UTF_8))));
        var libraries = new ArrayList<Library>();
        new LibraryScanner(new ObserverProperties()).inspect(jar, "shaded.jar", libraries, new ArrayList<>(), "123");
        assertThat(libraries).extracting(Library::name).containsExactlyInAnyOrder("shaded.jar", "a", "b");
    }

    @Test
    void labelsFilenameVersionsAsInferences() {
        var library = LibraryScanner.fromFilename("app.jar!/BOOT-INF/lib/spring-core-7.0.10.jar");
        assertThat(library.version()).isEqualTo("7.0.10");
        assertThat(library.evidence()).contains("추정");
        assertThat(library.loadingStatus()).contains("미확인");
    }

    @Test
    void capsExpandedNestedMetadataAndFallsBackToAnExplicitFilenameInference() throws Exception {
        byte[] manifest = ("Manifest-Version: 1.0\r\nLong-Value: " + "x".repeat(70 * 1024) + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        Path jar = directory.resolve("app.jar");
        Files.write(jar, archive(Map.of("BOOT-INF/lib/library-1.2.3.jar", archive(Map.of("META-INF/MANIFEST.MF", manifest)))));
        var libraries = new ArrayList<Library>();
        var issues = new ArrayList<Issue>();
        new LibraryScanner(new ObserverProperties()).inspect(jar, "app.jar", libraries, issues, "123");
        assertThat(libraries).anySatisfy(library -> {
            assertThat(library.name()).isEqualTo("library");
            assertThat(library.evidence()).isEqualTo("파일명 추정");
        });
        assertThat(issues).anySatisfy(issue -> assertThat(issue.message()).contains("메타데이터 읽기 제한"));
    }

    @Test
    void respectsLibraryCountAndReportsUnreadableArchives() throws Exception {
        var properties = new ObserverProperties();
        properties.setMaxJarsPerProcess(1);
        Path jar = directory.resolve("app.jar");
        Files.write(jar, archive(Map.of("BOOT-INF/lib/dependency-1.0.jar", archive(Map.of()))));
        var libraries = new ArrayList<Library>();
        var issues = new ArrayList<Issue>();
        var scanner = new LibraryScanner(properties);
        scanner.inspect(jar, "app.jar", libraries, issues, "123");
        scanner.inspect(directory.resolve("missing.jar"), "missing.jar", libraries, issues, "123");
        assertThat(libraries).hasSize(1);
        assertThat(issues).hasSize(2);
    }

    private static byte[] archive(Map<String, byte[]> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
