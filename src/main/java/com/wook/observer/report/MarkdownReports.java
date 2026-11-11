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
        var operator = OperatorReport.from(report);
        documents.put("summary.md", summary(report, operator));
        documents.put("actions.md", actions(operator));
        documents.put("coverage.md", coverage(report, operator));
        documents.put("host/os.md", operatingSystem(report));
        documents.put("host/rpm-packages.md", rpmPackages(report));
        for (ServiceCategory category : ServiceCategory.values()) {
            String base = "services/" + category.directory() + "/";
            documents.put(base + "processes.md", processes(report, category));
            documents.put(base + "open-source.md", libraries(report, category, operator));
            documents.put(base + "containers.md", containers(report, category));
        }
        return documents;
    }

    private String summary(Observation report, OperatorReport operator) {
        var out = new StringBuilder("# Wook Observer 운영 요약\n\n");
        out.append("- 대상: ").append(cell(report.host().name())).append(" / ").append(cell(report.host().scope())).append("\n")
                .append("- 수집: ").append(report.collectedAt()).append(" / 앱 ").append(cell(report.version())).append("\n")
                .append("- 프로세스 ").append(report.processes().size()).append(" · JVM ").append(report.javaProcesses().size())
                .append(" · 컨테이너 ").append(report.containers().size()).append(" · 고유 라이브러리(직군·버전별) ").append(operator.libraries().size()).append("\n\n")
                .append("## 조치 현황\n\n")
                .append("발견 ").append(operator.actions().stream().filter(a -> "발견".equals(a.status())).count())
                .append("건 · 의심 ").append(operator.actions().stream().filter(a -> "의심".equals(a.status())).count())
                .append("건 (ID·직군·패키지·버전별 중복 제거).\n\n")
                .append("[발견·의심 조치 목록과 영향 위치](actions.md) · [점검 범위·미판정 요약](coverage.md)\n\n");
        if (report.assessment() != null) {
            out.append("카탈로그 개정 ").append(report.assessment().catalogRevision()).append(" / 반영 ")
                    .append(cell(report.assessment().catalogUpdatedAt())).append(". 비대상·미판정은 위 조치 수에 포함하지 않습니다. 미판정은 안전을 뜻하지 않습니다.\n\n");
        } else { out.append("취약점 대조 미실시. 중앙 웹서비스에서 점검해야 저장된 기준으로 대조합니다.\n\n"); }
        out.append("## 명세\n\n[OS·커널](host/os.md) · [설치 RPM](host/rpm-packages.md) (수집 ")
                .append(report.systemInventory().rpm().packages().size()).append("건, ").append(report.systemInventory().rpm().status().label()).append(")\n\n")
                .append("| 직군 | 프로세스 | 오픈소스 | 컨테이너 |\n| --- | --- | --- | --- |\n");
        for (ServiceCategory category : ServiceCategory.values()) {
            long processes = report.processes().stream().filter(p -> p.category() == category).count();
            long libraries = operator.libraries().stream().filter(l -> l.category() == category).count();
            long containers = report.containers().stream().filter(c -> c.category() == category).count();
            if (processes + libraries + containers == 0) { continue; }
            String base = "services/" + category.directory() + "/";
            out.append("| ").append(category.label()).append(" | [").append(processes).append("](").append(base).append("processes.md)")
                    .append(" | [").append(libraries).append("](").append(base).append("open-source.md)")
                    .append(" | [").append(containers).append("](").append(base).append("containers.md) |\n");
        }
        out.append("\n수집 제한·오류 ").append(operator.issues().size()).append("종류: [점검 범위](coverage.md).\n\n")
                .append("[통합 XLSX](report.xlsx) · [전체 JSON 근거](report.json)\n");
        return out.toString();
    }

    private String actions(OperatorReport operator) {
        var out = new StringBuilder("# 발견·의심 조치 목록\n\n같은 ID·직군·패키지·버전의 영향 위치를 묶었습니다. 버전 일치는 실제 악용 가능성 확정이 아닙니다.\n\n");
        if (operator.actions().isEmpty()) { out.append("발견·의심 항목 없음. [점검 범위와 미판정](coverage.md)을 함께 확인하세요.\n"); }
        for (var action : operator.actions()) {
            out.append("## ").append(cell(action.id())).append(" · ").append(cell(action.serviceGroup())).append("\n\n");
            out.append("| 항목 | 내용 |\n| --- | --- |\n");
            row(out, "취약점발견 / 심각도", action.status() + " / " + action.severity());
            row(out, "패키지·설치 버전", action.packageName() + " " + action.version());
            row(out, "제목", action.title());
            row(out, "영향 위치", String.join("; ", action.locations().stream().map(OperatorReport.Location::label).toList()));
            row(out, "근거", String.join("; ", action.evidence()));
            row(out, "조치", String.join("; ", action.recommendations()));
            row(out, "권고 ID·별칭", String.join(", ", action.aliases()));
            row(out, "참조", String.join("; ", action.references()));
            out.append("\n");
        }
        return out.toString();
    }

    private String coverage(Observation report, OperatorReport operator) {
        var out = new StringBuilder("# 점검 범위\n\n");
        out.append(report.assessment() == null ? "취약점 대조 미실시." : cell(report.assessment().scope())).append("\n\n")
                .append("CVE는 라이브러리 위치, CCE/POLICY는 대상별 규칙 수입니다. 일부 미지원 권고가 있는 위치는 여러 상태에 포함될 수 있습니다.\n\n")
                .append("| 종류 | 판정 | 이유 | 항목 수 |\n| --- | --- | --- | --- |\n");
        operator.coverage().forEach(c -> row(out, c.kind(), c.status(), c.reason(), c.count()));
        out.append("\n## 수집 제한 및 오류\n\n| 계층 | 대상 | 내용 |\n| --- | --- | --- |\n");
        operator.issues().forEach(i -> row(out, i.layer(), String.join(", ", i.targets()), i.message()));
        out.append("\n## 공통 해석 기준\n\n")
                .append("- 라이브러리 존재는 실제 로딩·라이선스·오픈소스 여부 확인이 아닙니다. 파일명 후보는 좌표 확인이 필요합니다.\n")
                .append("- RPM CVE, 컨테이너별 RPM DB, 동적 클래스 로더, systemd·호스트 리스닝 포트·Kubernetes는 미지원입니다.\n")
                .append("- 순차 수집 스냅샷이며 이름 기반 서비스 분류는 설정으로 보정할 수 있습니다. 도구·권한 부족은 위 수집 제한을 확인하세요.\n")
                .append("- 이전 보고서는 당시 판정과 개정을 보존합니다. 새로운 수집·판정은 재점검부터 적용합니다.\n");
        return out.toString();
    }

    private String processes(Observation report, ServiceCategory category) {
        StringBuilder out = heading(category, "프로세스 명세", report);
        out.append("근거: Java Process API, Linux `/proc/<pid>/status`, Docker `top`. CPU는 시작 후 누적 시간입니다.\n\n")
                .append("| PID | PPID | 프로세스 | 소유자 | 시작 시각 | CPU(ms) | RSS(bytes) | 스레드 | 컨테이너 ID | 실행 파일 | 분류 근거 | 취약점발견 |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        var statuses = report.assessment() == null ? Map.<String, String>of() : report.assessment().statuses();
        var processes = report.processes().stream().filter(process -> process.category() == category).toList();
        processes.forEach(process -> row(out, process.pid(), process.parentPid(), process.name(), process.user(),
                process.startedAt(), process.cpuMillis(), process.residentBytes(), process.threads(), process.containerId(),
                process.executable(), process.classificationEvidence(), report.assessment() == null ? "미실시" : statuses.getOrDefault("process:" + process.pid(), "미판정")));
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

    private String libraries(Observation report, ServiceCategory category, OperatorReport operator) {
        StringBuilder out = heading(category, "오픈소스 명세", report);
        out.append("같은 패키지·버전을 묶고 영향 위치를 보존합니다. 실제 로딩·라이선스는 미확인입니다. 미판정은 안전을 뜻하지 않습니다.\n\n")
                .append("| 이름 | 버전 | 취약점발견 | 관련 ID | 식별 근거 | PID·컨테이너·경로 |\n")
                .append("| --- | --- | --- | --- | --- | --- |\n");
        operator.libraries().stream().filter(l -> l.category() == category).forEach(l -> row(out, l.name(), l.version(), l.status(),
                String.join(", ", l.advisories()), String.join("; ", l.evidence()),
                String.join("; ", l.locations().stream().map(OperatorReport.Location::label).toList())));
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
        rpm.packages().forEach(p -> row(out, p.name(), p.epoch(), p.version(), p.release(), p.architecture(), p.nevra(), "—"));
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
