package com.wook.observer.report;

import com.wook.observer.platform.ServiceDiscovery;
import com.wook.observer.platform.OsLifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A single portable document of saved asset profiles and complete service runbooks. */
public final class AssetMarkdown {
    private AssetMarkdown() { }
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("UNSPECIFIED", "미지정"), Map.entry("PRODUCTION", "운영"), Map.entry("STAGING", "검증"),
            Map.entry("DEVELOPMENT", "개발"), Map.entry("TEST", "테스트"), Map.entry("ACTIVE", "운영"),
            Map.entry("MAINTENANCE", "유지보수"), Map.entry("RETIRED", "폐기·사용 종료"), Map.entry("OTHER", "미분류"),
            Map.entry("MIDDLEWARE", "미들웨어"), Map.entry("APPLICATION", "애플리케이션"), Map.entry("COMMON", "공통·보안·백업"),
            Map.entry("WEB", "웹 서버"), Map.entry("DATABASE", "데이터베이스"), Map.entry("CONTAINER", "컨테이너"), Map.entry("CACHE", "캐시"), Map.entry("MESSAGING", "메시징"), Map.entry("INFRASTRUCTURE", "인프라"),
            Map.entry("OBSERVED", "최근 수집 확인"), Map.entry("NOT_OBSERVED", "최근 수집 미확인"), Map.entry("MANUAL", "수동 등록"),
            Map.entry("COMPLETED", "완료"), Map.entry("FAILED", "실패"), Map.entry("RUNNING", "진행 중"), Map.entry("QUEUED", "대기"));

    public static String write(List<Map<String, Object>> assets, Function<Map<String, Object>, List<Map<String, Object>>> services) {
        return write(assets, services, asset -> com.wook.observer.model.NetworkObservation.from(null));
    }
    public static String write(List<Map<String, Object>> assets, Function<Map<String, Object>, List<Map<String, Object>>> services,
                               Function<Map<String, Object>, com.wook.observer.model.NetworkObservation> network) {
        var out = new StringBuilder("# 자산·서비스 운영 보고서\n\n");
        out.append("작성 시각 (UTC): ").append(Instant.now()).append("\n\n")
                .append("등록 자산 ").append(assets.size()).append("대. 저장된 자산 정보와 전체 서비스(시스템 서비스·숨긴 서비스 포함)를 제공합니다. 화면 검색·제외 필터는 적용하지 않습니다.\n\n")
                .append("수기 운영 절차는 기록이며 실행되지 않습니다. 자동 관측은 서버별 수집 시각 기준이며 미관측은 중지·삭제를 의미하지 않습니다.\n\n")
                .append("OS 지원 상태는 저장된 OS 근거와 문서 생성 시점의 내장 정책 기준입니다. ELS는 운영자가 등록한 계약과 종료일을 반영하며, 자동 계약 조회를 하지 않습니다. CVE 발견 건수와 별도로 관리합니다.\n\n")
                .append("## 자산 목록\n\n| 서버명 | 주소 | 자산번호 | 서버 위치 | 담당 | 최근 수집 (UTC) | OS 지원 상태 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var asset : assets) {
            var support = OsLifecycle.forAsset((ServiceDiscovery.Summary) asset.get("inventory"), asset);
            row(out, value(asset, "name"), value(asset, "host"), value(asset, "asset_code"), value(asset, "location"), value(asset, "owner"),
                    value(asset, "observed_at"), support.upgradeRequired() ? "OS 업그레이드 필요 · " + support.label() : support.label());
        }
        if (assets.isEmpty()) out.append("\n등록한 자산이 없습니다.\n");
        int index = 0;
        for (var asset : assets) {
            out.append("\n## ").append(++index).append(". ").append(cell(value(asset, "name"))).append("\n\n");
            fields(out);
            field(out, "자산 ID", asset, "id"); field(out, "서버 주소", asset, "host"); field(out, "자산번호", asset, "asset_code");
            row(out, "환경", label(asset, "environment", "미지정")); row(out, "운영 상태", label(asset, "lifecycle", "운영"));
            field(out, "담당자 / 담당파트", asset, "owner"); field(out, "서버 위치", asset, "location");
            field(out, "서버 용도", asset, "purpose"); field(out, "설명", asset, "description"); field(out, "자산 메모", asset, "notes");
            row(out, "수집 방식", "UPLOAD".equals(asset.get("collection_mode")) ? "파일 업로드" : "SSH");
            field(out, "수기 정보 수정자", asset, "updated_by"); field(out, "수기 정보 수정 시각 (UTC)", asset, "updated_at");
            out.append("\n### 최근 관측\n\n"); fields(out);
            var summary = (ServiceDiscovery.Summary) asset.get("inventory");
            field(out, "수집 기준 시각 (UTC)", asset, "observed_at");
            row(out, "최근 시도 상태", label(asset, "latest_status", "점검 전")); field(out, "최근 시도 메시지", asset, "latest_message");
            row(out, "관측 호스트", summary == null ? "미수집" : summary.host().name());
            row(out, "OS", summary == null ? "미수집" : summary.distribution());
            var support = OsLifecycle.forAsset(summary, asset);
            row(out, "OS 지원 상태", support.label());
            row(out, "ELS 계약 등록", Boolean.TRUE.equals(asset.get("els_contract")) ? "운영자 확인" : "미등록");
            field(out, "등록 계약 종료일", asset, "els_valid_until"); field(out, "ELS 확인자", asset, "els_confirmed_by"); field(out, "ELS 확인 시각", asset, "els_confirmed_at");
            row(out, "OS 전환 조치", support.action()); row(out, "OS 지원 범위", support.note());
            if (!support.endedOn().isEmpty()) row(out, "공식 종료일", support.endedOn());
            if (!support.extendedUntil().isEmpty()) row(out, "공식 ELS 제공 종료일 · 조건부", support.extendedUntil());
            if (!support.sourceUrl().isEmpty()) row(out, "공식 지원 정책 출처", support.sourceUrl());
            row(out, "지원 정책 확인 / 판정 기준", support.policyVerifiedOn() + " / " + support.evaluatedOn());
            row(out, "CPU / 메모리", summary == null ? "미수집" : summary.host().processors() + " CPU / " + summary.host().physicalMemoryBytes() + " bytes");
            row(out, "아키텍처", summary == null ? "미수집" : summary.host().architecture());
            row(out, "수집 범위", summary == null ? "미수집" : summary.host().scope());
            row(out, "포트 수집 상태", summary == null ? "미수집" : summary.socketStatus() + " / " + summary.socketMessage());
            row(out, "수집 제한 건수", summary == null ? "미수집" : summary.issueCount());
            row(out, "최근 완료 점검의 발견·의심", asset.get("finding_count") == null ? "미점검" : asset.get("finding_count"));
            field(out, "관측 점검 ID", asset, "job_id"); field(out, "업로드 파일 SHA-256", asset, "import_sha256");
            out.append(NetworkReports.markdown(network.apply(asset)));
            var rows = services.apply(asset);
            out.append("\n### 서비스 운영대장 · ").append(rows.size()).append("개\n\n");
            if (rows.isEmpty()) out.append("등록하거나 수집한 서비스가 없습니다.\n");
            int serviceIndex = 0;
            for (var service : rows) {
                var observed = (ServiceDiscovery.Candidate) service.get("observed");
                String name = value(service, "name");
                if (name.isEmpty() && observed != null) name = observed.name();
                out.append("\n#### ").append(++serviceIndex).append(". ").append(cell(name)).append("\n\n"); fields(out);
                row(out, "구분", label(service, "service_type", "미분류"));
                row(out, "관리 상태", Boolean.TRUE.equals(service.get("archived")) ? "숨김" : "표시");
                row(out, "관측 상태", label(service, "observation_status", "미확인"));
                field(out, "SW", service, "software"); field(out, "SW 버전", service, "software_version");
                field(out, "담당파트 / 담당자", service, "owner"); field(out, "실행계정 (수기)", service, "run_user");
                field(out, "APP / 설치위치", service, "install_path"); field(out, "DATA 경로", service, "data_path"); field(out, "LOG 경로", service, "log_path");
                field(out, "운영 포트 / 보완", service, "bindings"); field(out, "연계 대상 / 통신 방향", service, "dependencies"); field(out, "메모", service, "notes");
                field(out, "최근 관측 시각 (UTC)", service, "last_seen_at"); field(out, "수정자", service, "updated_by"); field(out, "수정 시각 (UTC)", service, "updated_at");
                if (observed != null) {
                    row(out, "관측 서비스명", observed.name()); row(out, "관측 종류 / 직군", observed.kind() + " / " + observed.category());
                    row(out, "관측 실행계정", observed.runUser()); row(out, "자동 바인딩 포트", String.join("\n", observed.bindings()));
                    row(out, "PID 전체", String.join(" ", observed.pids().stream().map(String::valueOf).toList()));
                    row(out, "관측 실행 파일", observed.executable()); row(out, "관측 이미지", observed.image()); row(out, "컨테이너 ID", observed.containerId());
                }
                field(out, "서비스 ID", service, "id");
                command(out, "시작 방법", value(service, "start_command"));
                command(out, "종료 방법", value(service, "stop_command"));
                command(out, "상태 확인 방법", value(service, "status_command"));
            }
        }
        return out.toString();
    }

    private static void command(StringBuilder out, String title, String value) {
        out.append("\n##### ").append(title).append("\n\n");
        if (value.isEmpty()) { out.append("미입력\n"); return; }
        String fence = "```";
        while (value.contains(fence)) fence += "`";
        out.append(fence).append("text\n").append(value).append("\n").append(fence).append("\n");
    }
    private static void fields(StringBuilder out) { out.append("| 항목 | 내용 |\n| --- | --- |\n"); }
    private static void field(StringBuilder out, String label, Map<String, Object> values, String key) { row(out, label, value(values, key)); }
    private static String value(Map<String, Object> row, String key) { return java.util.Objects.toString(row.get(key), ""); }
    private static String label(Map<String, Object> row, String key, String fallback) { return LABELS.getOrDefault(value(row, key), fallback); }
    private static void row(StringBuilder out, Object... cells) {
        out.append("| ");
        for (Object value : cells) out.append(cell(value)).append(" | ");
        out.append('\n');
    }
    private static String cell(Object value) {
        String text = java.util.Objects.toString(value, "");
        if (text.isEmpty()) return "미입력";
        // Escape all Markdown syntax in data; only generator-owned markup is interpreted.
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("#", "&#35;").replace("\\", "&#92;").replace("|", "&#124;").replace("`", "&#96;").replace("*", "&#42;")
                .replace("_", "&#95;").replace("[", "&#91;").replace("]", "&#93;")
                .replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>");
    }
}
