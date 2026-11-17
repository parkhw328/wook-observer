package com.wook.observer.report;

import com.wook.observer.model.NetworkObservation;
import java.util.List;

final class NetworkReports {
    private NetworkReports() { }
    static final String NOTE = "수집 시점의 로컬 네임스페이스 관측이며 실시간·접속 방향·외부 접근 가능성을 의미하지 않습니다. 예약 포트는 커널 자동 할당 제외 설정, 자동 할당 범위는 실제 설정 기준이며 서비스 용도/자동 할당 여부를 단정하지 않습니다. 필터와 관계없이 전체 근거를 포함합니다.";
    static List<Object> values(NetworkObservation.Entry entry) {
        var s = entry.socket();
        return List.of(s.protocol(), s.state(), NetworkObservation.endpoint(s.localAddress(), s.localPort()),
                NetworkObservation.endpoint(s.peerAddress(), s.peerPort()), NetworkObservation.owners(entry), entry.classification(), s.source());
    }
    static String markdown(NetworkObservation network) {
        StringBuilder out = new StringBuilder("\n### 네트워크 관측\n\n");
        out.append(NOTE).append("\n\n");
        out.append(MarkdownReports.cell(network.bindings().message())).append("\n\n")
                .append(MarkdownReports.cell(network.connections().message())).append("\n\n");
        for (var setting : network.settings()) out.append("- ").append(MarkdownReports.cell(setting.source())).append(": ").append(setting.status().label())
                .append(" · ").append(MarkdownReports.cell(setting.value().isEmpty() ? (setting.status() == com.wook.observer.model.SystemInventory.Status.COLLECTED ? "설정 없음" : "미확인") : setting.value())).append("\n");
        out.append("\n| 프로토콜 | 상태 | 로컬 주소:포트 | 상대 주소:포트 | PID / 프로세스 | 분류 근거 | 도구 |\n| --- | --- | --- | --- | --- | --- | --- |\n");
        for (var entry : network.entries()) {
            out.append('|'); for (var value : values(entry)) out.append(' ').append(MarkdownReports.cell(value)).append(" |"); out.append('\n');
        }
        if (network.entries().isEmpty()) out.append("\n관측 항목 없음. 위 수집 상태를 확인하세요.\n");
        return out.toString();
    }
}
