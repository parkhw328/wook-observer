package com.wook.observer.collector;

import com.wook.observer.model.Observation.Issue;
import com.wook.observer.model.SystemInventory;
import com.wook.observer.model.SystemInventory.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses bounded, untrusted records without executing release-file or package contents. */
public final class SystemInventoryParser {
    public static final int MAX_PACKAGES = 20_000;
    private static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        SOURCES.put("uname_all", "uname -a");
        SOURCES.put("kernel_release", "uname -r");
        SOURCES.put("kernel_version", "uname -v");
        SOURCES.put("architecture", "uname -m");
        SOURCES.put("os_release", "cat /etc/os-release");
        SOURCES.put("redhat_release", "cat /etc/redhat-release");
        SOURCES.put("proc_version", "cat /proc/version");
    }
    private final Map<String, OsDetail> os = new LinkedHashMap<>();
    private final List<RpmPackage> packages = new ArrayList<>();
    private Status rpmStatus = Status.NOT_COLLECTED;
    private boolean rpmSeen, invalidRpm, limited;
    private String rpmMessage = "";

    public void accept(String line) {
        String[] fields = line.split("\t", -1);
        if (fields[0].equals("OSINFO") && fields.length >= 2 && SOURCES.containsKey(fields[1])) {
            String key = fields[1];
            try {
                if (fields.length != 4 || fields[3].length() > 22_000) { throw new IllegalArgumentException(); }
                Status status = Status.valueOf(fields[2]);
                byte[] bytes = Base64.getDecoder().decode(fields[3]);
                if (bytes.length > 16_384) { status = Status.TRUNCATED; }
                os.put(key, new OsDetail(key, SOURCES.get(key), status,
                        new String(bytes, 0, Math.min(bytes.length, 16_384), StandardCharsets.UTF_8)));
            } catch (IllegalArgumentException exception) {
                os.put(key, new OsDetail(key, SOURCES.get(key), Status.FAILED, ""));
            }
        } else if (fields[0].equals("RPM")) {
            rpmSeen = true;
            if (packages.size() >= MAX_PACKAGES) { limited = true; return; }
            if (fields.length != 6 || !valid(fields[1]) || !valid(fields[3]) || !valid(fields[4]) || !valid(fields[5])
                    || !fields[2].matches("[0-9]{1,20}|\\(none\\)")) { invalidRpm = true; return; }
            packages.add(new RpmPackage(fields[1], fields[2].equals("(none)") ? "0" : fields[2], fields[3], fields[4], fields[5]));
        } else if (fields[0].equals("RPM_STATUS")) {
            rpmSeen = true;
            try {
                rpmStatus = fields.length == 2 && fields[1].equals("NO_TIMEOUT") ? Status.UNAVAILABLE : Status.valueOf(fields[1]);
                if (fields[1].equals("NO_TIMEOUT")) { rpmMessage = "timeout 도구가 없어 RPM 조회를 실행하지 않았습니다."; }
            } catch (RuntimeException exception) { invalidRpm = true; }
        }
    }

    private boolean valid(String value) {
        return !value.isBlank() && value.length() <= 512 && value.codePoints().noneMatch(Character::isISOControl);
    }

    public SystemInventory build(List<Issue> issues) {
        List<OsDetail> details = SOURCES.entrySet().stream().map(entry -> os.getOrDefault(entry.getKey(),
                new OsDetail(entry.getKey(), entry.getValue(), Status.NOT_COLLECTED, ""))).toList();
        if (limited || invalidRpm || (rpmSeen && rpmStatus == Status.NOT_COLLECTED)) { rpmStatus = Status.TRUNCATED; }
        if (rpmMessage.isEmpty()) {
            rpmMessage = switch (rpmStatus) {
                case COLLECTED -> "설치 RPM DB 조회 완료. 설치 여부이며 서비스 사용·CVE 판정은 별도입니다.";
                case UNAVAILABLE -> "rpm 도구가 없어 설치 패키지를 조회하지 않았습니다.";
                case FAILED -> "RPM DB 조회 실패: 읽기 권한·DB 상태·도구 호환성을 확인하세요.";
                case TIMEOUT -> "RPM 조회 20초 제한 초과. 수집된 항목만 보존합니다.";
                case TRUNCATED -> "20,000건·4 MiB 제한 또는 불완전한 응답. 전체 목록으로 해석하지 마세요.";
                case NOT_COLLECTED -> "이 스냅샷에서는 RPM을 수집하지 않았습니다.";
            };
        }
        details.stream().filter(d -> d.status() != Status.COLLECTED
                && !(d.key().equals("redhat_release") && d.status() == Status.UNAVAILABLE))
                .forEach(d -> issues.add(new Issue("os", d.source(), d.status().label())));
        if (rpmStatus != Status.COLLECTED) { issues.add(new Issue("rpm", "rpm -qa", rpmMessage)); }
        var sorted = packages.stream().sorted(Comparator.comparing(RpmPackage::name).thenComparing(RpmPackage::architecture)
                .thenComparing(RpmPackage::version).thenComparing(RpmPackage::release).thenComparing(RpmPackage::epoch)).toList();
        return new SystemInventory(details, new RpmInventory(rpmStatus, rpmMessage, sorted));
    }
}
