package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.Observation.Library;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

@Component
public class LibraryScanner {
    private static final int METADATA_LIMIT = 64 * 1024;
    private static final int NESTED_JAR_LIMIT = 16 * 1024 * 1024;
    private static final Pattern FILENAME_VERSION = Pattern.compile("^(.+?)-(\\d[^/]*)\\.jar$");
    private final ObserverProperties properties;

    public LibraryScanner(ObserverProperties properties) {
        this.properties = properties;
    }

    public List<Library> scan(long pid, List<String> classPaths, List<Issue> issues) {
        List<Library> libraries = new ArrayList<>();
        Set<Object> visited = new HashSet<>();
        for (String classPath : classPaths) {
            if (libraries.size() >= properties.getMaxJarsPerProcess()) { break; }
            try {
                boolean wildcard = classPath.endsWith("/*");
                String value = wildcard ? classPath.substring(0, classPath.length() - 2) : classPath;
                Path path = processPath(pid, value);
                if (wildcard && Files.isDirectory(path)) {
                    try (var entries = Files.list(path)) {
                        for (Path jar : entries.filter(file -> file.toString().endsWith(".jar"))
                                .sorted().limit(properties.getMaxJarsPerProcess() + 1L).toList()) {
                            if (libraries.size() >= properties.getMaxJarsPerProcess()) {
                                issues.add(new Issue("library", Long.toString(pid), "라이브러리 수 제한으로 일부 생략"));
                                break;
                            }
                            scanOnce(jar, value + "/" + jar.getFileName(), visited, libraries, issues, pid);
                        }
                    }
                } else if (value.endsWith(".jar") || value.endsWith(".war")) {
                    scanOnce(path, value, visited, libraries, issues, pid);
                } else if (!Files.exists(path)) {
                    issues.add(new Issue("library", Long.toString(pid), "클래스패스 항목 접근 불가: " + value));
                }
            } catch (Exception exception) {
                issues.add(new Issue("library", Long.toString(pid), "클래스패스 조회 실패: " + classPath));
            }
        }
        if (libraries.size() >= properties.getMaxJarsPerProcess()) {
            issues.add(new Issue("library", Long.toString(pid), "라이브러리 수 상한 도달: 추가 항목이 생략될 수 있음"));
        }
        return List.copyOf(libraries);
    }

    static Path processPath(long pid, String value) {
        Path path = Path.of(value);
        Path proc = Path.of("/proc", Long.toString(pid));
        return path.isAbsolute() ? proc.resolve("root").resolve(path.normalize().toString().substring(1))
                : proc.resolve("cwd").resolve(path);
    }

    private void scanOnce(Path path, String display, Set<Object> visited, List<Library> libraries,
                          List<Issue> issues, long pid) throws IOException {
        // Canonicalizing /proc/<pid>/root would discard the target mount namespace.
        Object fileKey = Files.readAttributes(path, BasicFileAttributes.class).fileKey();
        if (visited.add(fileKey == null ? path.toAbsolutePath() : fileKey)) {
            inspect(path, display, libraries, issues, Long.toString(pid));
        }
    }

    public void inspect(Path path, String display, List<Library> libraries, List<Issue> issues, String target) {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            Library main = fromFilename(display);
            ZipEntry manifest = archive.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) {
                try (var input = archive.getInputStream(manifest)) {
                    main = fromManifest(new Manifest(new ByteArrayInputStream(metadata(input))), display, main);
                }
            }
            var entries = archive.entries();
            List<ZipEntry> nested = new ArrayList<>();
            List<Library> mavenArtifacts = new ArrayList<>();
            boolean metadataLimitReported = false;
            int inspected = 0;
            while (entries.hasMoreElements()) {
                if (++inspected > 100000) {
                    issues.add(new Issue("library", target, "JAR 항목 수 제한: " + display));
                    break;
                }
                ZipEntry entry = entries.nextElement();
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    if (mavenArtifacts.size() < properties.getMaxJarsPerProcess()) {
                        try (var input = archive.getInputStream(entry)) {
                            Library metadata = fromPom(metadata(input), display + "!/" + entry.getName());
                            if (metadata != null) { mavenArtifacts.add(metadata); }
                        }
                    } else if (!metadataLimitReported) {
                        issues.add(new Issue("library", target, "묶음 JAR 메타데이터 수 제한: " + display));
                        metadataLimitReported = true;
                    }
                }
                if ((entry.getName().startsWith("BOOT-INF/lib/") || entry.getName().startsWith("WEB-INF/lib/"))
                        && entry.getName().endsWith(".jar")) {
                    if (nested.size() <= properties.getMaxJarsPerProcess()) { nested.add(entry); }
                }
            }
            if (mavenArtifacts.size() == 1) {
                Library artifact = mavenArtifacts.getFirst();
                main = library(artifact.name(), artifact.version(), display, artifact.evidence());
            }
            if (libraries.size() < properties.getMaxJarsPerProcess()) { libraries.add(main); }
            if (mavenArtifacts.size() > 1) {
                for (Library artifact : mavenArtifacts) {
                    if (libraries.size() >= properties.getMaxJarsPerProcess()) {
                        issues.add(new Issue("library", target, "묶음 JAR 메타데이터 수 제한: " + display));
                        break;
                    }
                    libraries.add(artifact);
                }
            }
            for (ZipEntry entry : nested) {
                if (libraries.size() >= properties.getMaxJarsPerProcess()) {
                    issues.add(new Issue("library", target, "중첩 라이브러리 수 제한: " + display));
                    break;
                }
                String location = display + "!/" + entry.getName();
                List<Library> found = List.of(fromFilename(location));
                try (var input = archive.getInputStream(entry)) {
                    if (entry.getSize() > NESTED_JAR_LIMIT) {
                        throw new IOException("Nested archive limit");
                    }
                    byte[] bytes = input.readNBytes(NESTED_JAR_LIMIT + 1);
                    if (bytes.length > NESTED_JAR_LIMIT) { throw new IOException("Nested archive limit"); }
                    found = inspectNested(bytes, location, found.getFirst());
                } catch (Exception exception) {
                    issues.add(new Issue("library", target, "중첩 JAR 메타데이터 읽기 제한/실패; 파일명 근거 사용: " + location));
                }
                int available = properties.getMaxJarsPerProcess() - libraries.size();
                libraries.addAll(found.subList(0, Math.min(available, found.size())));
                if (found.size() > available) { issues.add(new Issue("library", target, "중첩 Maven 메타데이터 수 제한: " + location)); }
            }
        } catch (Exception exception) {
            issues.add(new Issue("library", target, "JAR 읽기 또는 메타데이터 해석 실패: " + display));
        }
    }

    private List<Library> inspectNested(byte[] bytes, String display, Library fallback) throws IOException {
        try (ZipInputStream jar = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int remaining = 32 * 1024 * 1024;
            byte[] buffer = new byte[8192];
            List<Library> artifacts = new ArrayList<>();
            for (int count = 0; count < 10000; count++) {
                ZipEntry entry = jar.getNextEntry();
                if (entry == null) {
                    if (artifacts.isEmpty()) { return List.of(fallback); }
                    if (artifacts.size() == 1) {
                        Library artifact = artifacts.getFirst();
                        return List.of(library(artifact.name(), artifact.version(), display, artifact.evidence()));
                    }
                    List<Library> result = new ArrayList<>();
                    result.add(fallback);
                    result.addAll(artifacts);
                    return result;
                }
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    byte[] data = metadata(jar);
                    remaining -= data.length;
                    fallback = fromManifest(new Manifest(new ByteArrayInputStream(data)), display, fallback);
                }
                if (entry.getName().startsWith("META-INF/maven/") && entry.getName().endsWith("/pom.properties")) {
                    byte[] data = metadata(jar);
                    remaining -= data.length;
                    Library pom = fromPom(data, display + "!/" + entry.getName());
                    if (pom != null) { artifacts.add(pom); }
                    if (artifacts.size() > properties.getMaxJarsPerProcess()) { throw new IOException("Maven metadata limit"); }
                }
                if (remaining < 0) { throw new IOException("Expanded metadata scan limit"); }
                int read;
                while ((read = jar.read(buffer)) != -1) {
                    remaining -= read;
                    if (remaining < 0) { throw new IOException("Expanded metadata scan limit"); }
                }
            }
            throw new IOException("Archive entry limit");
        }
    }

    private static byte[] metadata(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(METADATA_LIMIT + 1);
        if (bytes.length > METADATA_LIMIT) { throw new IOException("Metadata limit"); }
        return bytes;
    }

    private static Library fromPom(byte[] bytes, String path) throws IOException {
        Properties pom = new Properties();
        pom.load(new ByteArrayInputStream(bytes));
        String artifact = pom.getProperty("artifactId");
        String version = pom.getProperty("version");
        if (artifact == null || version == null || artifact.isBlank() || version.isBlank()) { return null; }
        String group = pom.getProperty("groupId", "");
        return library(group.isBlank() ? artifact : group + ":" + artifact, version, path, "META-INF/maven pom.properties");
    }

    private static Library fromManifest(Manifest manifest, String path, Library fallback) {
        var attributes = manifest.getMainAttributes();
        String version = attributes.getValue("Implementation-Version");
        if (version == null) { version = attributes.getValue("Bundle-Version"); }
        if (version == null || version.isBlank()) { return fallback; }
        String name = attributes.getValue("Implementation-Title");
        if (name == null) { name = attributes.getValue("Bundle-SymbolicName"); }
        if (name == null) { name = fallback.name(); }
        return library(name, version, path, "META-INF/MANIFEST.MF");
    }

    public static Library fromFilename(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        var matcher = FILENAME_VERSION.matcher(filename);
        return matcher.matches() ? library(matcher.group(1), matcher.group(2), path, "파일명 추정")
                : library(filename, "미확인", path, "파일명; 버전 메타데이터 없음");
    }

    private static Library library(String name, String version, String path, String evidence) {
        return new Library(name, version, path, evidence, "미확인", "클래스패스/패키지에서 발견; 실제 로딩 여부 미확인");
    }
}
