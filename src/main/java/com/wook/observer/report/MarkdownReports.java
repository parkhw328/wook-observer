package com.wook.observer.report;

import com.wook.observer.model.Observation;
import com.wook.observer.model.Assessment;
import com.wook.observer.model.ServiceCategory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class MarkdownReports {
    public Map<String, String> documents(Observation report) {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("summary.md", summary(report));
        documents.put("host/os.md", operatingSystem(report));
        documents.put("host/rpm-packages.md", rpmPackages(report));
        for (ServiceCategory category : ServiceCategory.values()) {
            String base = "services/" + category.directory() + "/";
            documents.put(base + "processes.md", processes(report, category));
            documents.put(base + "open-source.md", libraries(report, category));
            documents.put(base + "containers.md", containers(report, category));
        }
        return documents;
    }

    private String summary(Observation report) {
        StringBuilder out = new StringBuilder("# Wook Observer 서버 분석 보고서\n\n");
        out.append("- 버전: ").append(cell(report.version())).append("\n")
                .append("- 수집 시작 시각: ").append(report.collectedAt()).append("\n")
                .append("- 호스트: ").append(cell(report.host().name())).append("\n")
                .append("- OS: ").append(cell(report.host().os())).append(" ").append(cell(report.host().osVersion())).append("\n")
                .append("- 아키텍처: ").append(cell(report.host().architecture())).append("\n")
                .append("- CPU: ").append(report.host().processors()).append(" / 물리 메모리(bytes): ").append(report.host().physicalMemoryBytes()).append("\n")
                .append("- 관측 범위: ").append(cell(report.host().scope())).append("\n")
                .append("- Docker 엔진: ").append(cell(report.host().dockerVersion())).append("\n")
                .append("- 프로세스: ").append(report.processes().size()).append(" / Java: ").append(report.javaProcesses().size())
                .append(" / 컨테이너: ").append(report.containers().size()).append("\n\n")
                .append("## 호스트 명세\n\n[OS·커널 정보](host/os.md) · [설치 RPM 패키지](host/rpm-packages.md)\n\n")
                .append("RPM: ").append(report.systemInventory().rpm().status().label()).append(" / 수집 항목 ")
                .append(report.systemInventory().rpm().packages().size()).append("건\n\n")
                .append("## 서비스 직군별 명세\n\n| 직군 | 프로세스 | 오픈소스/라이브러리 | 컨테이너 |\n| --- | --- | --- | --- |\n");
        for (ServiceCategory category : ServiceCategory.values()) {
            String base = "services/" + category.directory() + "/";
            out.append("| ").append(category.label()).append(" | [명세](").append(base).append("processes.md)")
                    .append(" | [명세](").append(base).append("open-source.md) | [명세](").append(base).append("containers.md) |\n");
        }
        out.append("\n## 해석 범위\n\n")
                .append("- 실행 파일·이미지 이름에 의한 서비스 분류는 휴리스틱이며 설정으로 보정할 수 있습니다.\n")
                .append("- 라이브러리는 클래스패스 또는 실행 패키지에서 발견한 항목입니다. 실제 클래스 로딩, 오픈소스 여부, 라이선스는 별도 확인이 필요합니다.\n")
                .append("- RPM 외 패키지 상세, 동적 클래스 로더, systemd, 호스트 리스닝 포트, Kubernetes 계층은 미지원입니다. SSH와 로컬 수집의 차이는 수집 오류를 확인하세요.\n")
                .append("- 시작 시각 이후 순차적으로 수집한 스냅샷으로 프로세스 종료 등 시점 차이가 있을 수 있습니다.\n\n")
                .append("## 수집 제한 및 오류\n\n");
        if (report.issues().isEmpty()) {
            out.append("구현된 수집 범위에서 보고된 오류 없음. 위 해석 범위와 미지원 항목은 계속 적용됩니다.\n");
        } else {
            out.append("| 계층 | 대상 | 내용 |\n| --- | --- | --- |\n");
            report.issues().forEach(issue -> row(out, issue.layer(), issue.target(), issue.message()));
        }
        if (report.assessment() != null) {
            out.append("\n## 취약점 판정\n\n")
                    .append("카탈로그 버전: ").append(report.assessment().catalogRevision()).append(" / 반영 시각: ").append(cell(report.assessment().catalogUpdatedAt()))
                    .append("\n\n").append(cell(report.assessment().scope())).append("\n\n")
                    .append("| 종류 | ID | 대상 | 취약점발견 | 심각도 | 근거 |\n| --- | --- | --- | --- | --- | --- |\n");
            report.assessment().findings().forEach(f -> row(out, f.kind(), f.id(), f.asset(), f.status(), f.severity(), f.evidence()));
        }
        out.append("\n전체 구조화 결과: [report.json](report.json)\n")
                .append("\n통합 Excel 보고서: [report.xlsx](report.xlsx)\n");
        return out.toString();
    }

    private String processes(Observation report, ServiceCategory category) {
        StringBuilder out = heading(category, "프로세스 명세", report);
        out.append("근거: Java Process API, Linux `/proc/<pid>/status`, Docker `top`. CPU는 시작 후 누적 시간입니다.\n\n")
                .append("| PID | PPID | 프로세스 | 소유자 | 시작 시각 | CPU(ms) | RSS(bytes) | 스레드 | 컨테이너 ID | 실행 파일 | 분류 근거 | 취약점발견 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        var processes = report.processes().stream().filter(process -> process.category() == category).toList();
        processes.forEach(process -> row(out, process.pid(), process.parentPid(), process.name(), process.user(),
                process.startedAt(), process.cpuMillis(), process.residentBytes(), process.threads(), process.containerId(),
                process.executable(), process.classificationEvidence(), report.assessment() == null ? "미실시" : report.assessment().processStatus(process.pid())));
        if (processes.isEmpty()) { out.append("\n해당 직군에서 수집된 프로세스 없음. 수집 오류는 요약 문서를 확인하세요.\n"); }
        Set<Long> pids = processes.stream().map(Observation.ProcessInfo::pid).collect(Collectors.toSet());
        report.javaProcesses().stream().filter(java -> pids.contains(java.pid())).forEach(java -> {
            out.append("\n## JVM PID ").append(java.pid()).append("\n\n상태: ").append(cell(java.status()))
                    .append("\n\n| 속성 | 값 |\n| --- | --- |\n");
            java.properties().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> row(out, entry.getKey(), entry.getValue()));
        });
        return out.toString();
    }

    private String libraries(Observation report, ServiceCategory category) {
        StringBuilder out = heading(category, "오픈소스 명세", report);
        out.append("Java 라이브러리 후보 목록입니다. 오픈소스 여부·라이선스와 실제 로딩 여부는 확정하지 않습니다. 호스트 RPM은 별도 [설치 패키지 명세](../../host/rpm-packages.md)를 확인하세요.\n\n")
                .append("| PID | 이름 | 버전 | 경로 | 버전 근거 | 라이선스 | 로딩 상태 | 취약점발견 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        Set<Long> pids = report.processes().stream().filter(process -> process.category() == category)
                .map(Observation.ProcessInfo::pid).collect(Collectors.toSet());
        var javaProcesses = report.javaProcesses().stream().filter(java -> pids.contains(java.pid())).toList();
        javaProcesses.forEach(java -> java.libraries().forEach(library -> row(out, java.pid(), library.name(),
                library.version(), library.path(), library.evidence(), library.license(), library.loadingStatus(),
                report.assessment() == null ? "미실시" : report.assessment().status(Assessment.libraryAsset(java.pid(), library)))));
        if (javaProcesses.stream().allMatch(java -> java.libraries().isEmpty())) {
            out.append("\n수집된 라이브러리 없음. 라이브러리가 없다는 의미가 아니며 미지원·권한·수집 오류를 확인하세요.\n");
        }
        return out.toString();
    }

    private String operatingSystem(Observation report) {
        var out = new StringBuilder("# OS·커널 명세\n\n");
        out.append("수집 시작 시각: ").append(report.collectedAt()).append("\n\n관측 범위: ").append(cell(report.host().scope()))
                .append("\n\n배포판 정보와 실행 커널은 구분합니다. 컨테이너에서는 배포판 파일이 컨테이너 기준이며 커널은 호스트와 공유될 수 있습니다. 파일 끝 개행은 생략하고 항목당 16 KiB까지 보존합니다.\n\n")
                .append("| 항목 | 근거 | 상태 | 값 |\n| --- | --- | --- | --- |\n");
        report.systemInventory().os().forEach(d -> row(out, d.key(), d.source(), d.status().label(), d.value()));
        if (report.systemInventory().os().isEmpty()) { out.append("\nOS 상세 미수집. 이전 버전의 스냅샷은 다시 점검해야 합니다.\n"); }
        return out.toString();
    }

    private String rpmPackages(Observation report) {
        var rpm = report.systemInventory().rpm();
        var out = new StringBuilder("# 설치 RPM 패키지 명세\n\n");
        out.append("수집 시작 시각: ").append(report.collectedAt()).append("\n\n관측 범위: ").append(cell(report.host().scope()))
                .append("\n\n근거: `rpm -qa --qf 'RPM\\t%{NAME}\\t%{EPOCH}\\t%{VERSION}\\t%{RELEASE}\\t%{ARCH}\\n'`\n\n")
                .append("상태: ").append(rpm.status().label()).append(" / 수집 항목: ").append(rpm.packages().size()).append("건\n\n")
                .append(cell(rpm.message())).append("\n\n20초·20,000건·4 MiB 제한. Epoch 미지정은 0입니다. 여러 아키텍처·설치 버전은 각각 보존합니다. 설치 여부는 서비스 사용·실제 로딩을 의미하지 않습니다. 컨테이너별 RPM DB와 RPM CVE 대조는 미지원입니다.\n\n")
                .append("| 이름 | Epoch | 버전 | Release | 아키텍처 | NEVRA | 취약점발견 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        rpm.packages().forEach(p -> row(out, p.name(), p.epoch(), p.version(), p.release(), p.architecture(), p.nevra(), "점검 불가 (RPM 대조 미지원)"));
        return out.toString();
    }

    private String containers(Observation report, ServiceCategory category) {
        StringBuilder out = heading(category, "컨테이너 명세", report);
        out.append("근거: 로컬 Docker 엔진 `inspect`, `top`. 이미지 태그와 실제 이미지 ID를 구분합니다.\n");
        var containers = report.containers().stream().filter(container -> container.category() == category).toList();
        for (var container : containers) {
            out.append("\n## ").append(cell(container.name())).append("\n\n| 항목 | 값 |\n| --- | --- |\n");
            row(out, "ID", container.id());
            row(out, "이미지", container.image());
            row(out, "이미지 ID", container.imageId());
            row(out, "상태", container.state());
            row(out, "호스트 init PID", container.initPid());
            row(out, "호스트 프로세스 PID", container.processIds());
            row(out, "포트", String.join("; ", container.ports()));
            row(out, "네트워크", String.join("; ", container.networks()));
            row(out, "마운트", String.join("; ", container.mounts()));
            row(out, "분류 근거", container.classificationEvidence());
            row(out, "취약점발견", report.assessment() == null ? "미실시" : report.assessment().status("container:" + container.id()));
        }
        if (containers.isEmpty()) { out.append("\n해당 직군에서 수집된 컨테이너 없음. Docker 접근 오류 여부는 요약 문서를 확인하세요.\n"); }
        return out.toString();
    }

    private StringBuilder heading(ServiceCategory category, String type, Observation report) {
        return new StringBuilder("# ").append(category.label()).append(" — ").append(type)
                .append("\n\n수집 시작 시각: ").append(report.collectedAt())
                .append("\n\n[전체 요약 및 수집 제한](../../summary.md)\n\n");
    }

    private static void row(StringBuilder out, Object... values) {
        out.append("|");
        for (Object value : values) { out.append(" ").append(cell(value)).append(" |"); }
        out.append("\n");
    }

    static String cell(Object value) {
        if (value == null || value.toString().isEmpty()) { return "미확인/해당 없음"; }
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\\", "&#92;").replace("|", "&#124;").replace("`", "&#96;")
                .replace("[", "&#91;").replace("]", "&#93;").replace("*", "&#42;").replace("_", "&#95;")
                .replace("\r", " ").replace("\n", " ");
    }
}
