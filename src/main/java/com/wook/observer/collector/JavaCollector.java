package com.wook.observer.collector;

import com.wook.observer.config.ObserverProperties;
import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.Observation.JavaInfo;
import com.wook.observer.model.Observation.ProcessInfo;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

@Component
public class JavaCollector {
    private static final Set<String> ALLOWED_PROPERTIES = Set.of("java.version", "java.runtime.version", "java.vendor",
            "java.vm.name", "java.vm.version", "java.home", "java.class.path", "jdk.module.path", "os.name", "os.version", "os.arch");
    private final ObserverProperties properties;
    private final CommandRunner commands;
    private final LibraryScanner libraries;

    public JavaCollector(ObserverProperties properties, CommandRunner commands, LibraryScanner libraries) {
        this.properties = properties;
        this.commands = commands;
        this.libraries = libraries;
    }

    public List<JavaInfo> collect(List<ProcessInfo> processes, List<Issue> issues) {
        List<ProcessInfo> javaProcesses = processes.stream()
                .filter(process -> Set.of("java", "java.exe").contains(process.name())).toList();
        if (javaProcesses.size() > properties.getMaxJavaProcesses()) {
            issues.add(new Issue("java", "host", "Java 프로세스 수 제한으로 일부 생략: " + javaProcesses.size()));
        }
        List<JavaInfo> result = new ArrayList<>();
        for (ProcessInfo process : javaProcesses.stream().limit(properties.getMaxJavaProcesses()).toList()) {
            String pid = Long.toString(process.pid());
            Map<String, String> values = new TreeMap<>();
            String status = "부분 수집";
            try {
                if (process.pid() == ProcessHandle.current().pid()) {
                    ALLOWED_PROPERTIES.forEach(key -> {
                        String value = System.getProperty(key);
                        if (value != null) { values.put(key, value); }
                    });
                    status = "수집기 JVM의 선별 속성 조회 완료";
                } else if (properties.isJvmAttachEnabled()) {
                    String jcmd = Path.of(System.getProperty("java.home"), "bin", "jcmd").toString();
                    var diagnostic = commands.run(List.of(jcmd, pid, "VM.system_properties"));
                    if (diagnostic.success()) {
                        values.putAll(filterProperties(diagnostic.output()));
                        status = "jcmd 선별 속성 조회 완료";
                    } else {
                        issues.add(new Issue("java", pid, diagnostic.failure()));
                    }
                } else {
                    issues.add(new Issue("java", pid, "설정에 의해 JVM attach 비활성화; 실행 인자의 클래스패스만 조사"));
                }
                if (!values.containsKey("java.version")) {
                    issues.add(new Issue("java", pid, "대상 JVM 버전 미확인"));
                }
                Set<String> paths = new LinkedHashSet<>();
                for (String key : List.of("java.class.path", "jdk.module.path")) {
                    addPaths(paths, values.get(key));
                }
                List<String> arguments = Arrays.asList(ProcessCollector.readProc(process.pid(), "cmdline").split("\\x00"));
                paths.addAll(classPathsFromArguments(arguments));
                if (paths.isEmpty()) {
                    issues.add(new Issue("library", pid, "클래스패스/실행 JAR 식별 불가"));
                }
                result.add(new JavaInfo(process.pid(), status, Map.copyOf(values), libraries.scan(process.pid(), List.copyOf(paths), issues)));
            } catch (Exception exception) {
                issues.add(new Issue("java", pid, "Java 메타데이터 조회 중 대상 종료 또는 해석 실패"));
                result.add(new JavaInfo(process.pid(), "수집 실패", Map.copyOf(values), List.of()));
            }
        }
        return List.copyOf(result);
    }

    public static Map<String, String> filterProperties(String output) throws Exception {
        Properties parsed = new Properties();
        parsed.load(new StringReader(output));
        Map<String, String> result = new TreeMap<>();
        for (String key : ALLOWED_PROPERTIES) {
            if (parsed.containsKey(key)) { result.put(key, parsed.getProperty(key)); }
        }
        return result;
    }

    public static List<String> classPathsFromArguments(List<String> args) {
        Set<String> paths = new LinkedHashSet<>();
        Set<String> pathOptions = Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path");
        Set<String> valueOptions = Set.of("--add-opens", "--add-exports", "--add-reads", "--add-modules", "--limit-modules", "--patch-module");
        for (int index = 1; index < args.size(); index++) {
            String arg = args.get(index);
            if (arg.equals("-jar") && index + 1 < args.size()) {
                paths.add(args.get(index + 1));
                break;
            }
            if (pathOptions.contains(arg) && index + 1 < args.size()) {
                addPaths(paths, args.get(++index));
            } else if (arg.startsWith("--class-path=") || arg.startsWith("--module-path=")) {
                addPaths(paths, arg.substring(arg.indexOf('=') + 1));
            } else if (valueOptions.contains(arg)) {
                index++;
            } else if (!arg.startsWith("-") || arg.equals("-m") || arg.equals("--module")) {
                break;
            }
        }
        return List.copyOf(paths);
    }

    private static void addPaths(Set<String> paths, String value) {
        if (value != null) {
            Arrays.stream(value.split(":", -1)).map(path -> path.isBlank() ? "." : path).forEach(paths::add);
        }
    }
}
