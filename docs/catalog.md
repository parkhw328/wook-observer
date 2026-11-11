# 폐쇄망 취약점 카탈로그

## 입력 형식

관리자는 파일 업로드 또는 `updates/`에 반입한 파일을 통해 DB를 갱신합니다. HTTPS 미러는 운영 설정 `OBSERVER_MIRROR_HOSTS`에 등록된 호스트의 443 포트만 허용하며 리다이렉트·URL 자격 증명·쿼리 문자열을 허용하지 않습니다. TLS 인증서 검증은 유지됩니다. 내부 CA는 이미지의 신뢰 저장소에 설치해 배포하세요.

지원 입력은 다음과 같습니다.

1. [OSV 형식](https://ossf.github.io/osv-schema/) JSON 한 건 또는 배열.
2. OSV JSON 파일만 포함한 ZIP. 경로를 파일시스템에 풀지 않고 읽습니다.
3. `{ "format": "wook-catalog-1", "records": [...] }` 묶음. OSV와 아래 CCE/POLICY 규칙을 함께 넣을 수 있습니다.

최대 업로드는 64MiB, ZIP 압축 해제 합계는 256MiB, 개별 ZIP 항목은 4MiB, 레코드는 50,000개입니다. 큰 데이터셋은 정해진 상한에 맞춰 여러 묶음으로 나눠 반입하세요. NVD 원본 피드나 CVE List V5는 직접 지원하지 않으며 OSV 또는 지원 규칙 형식으로 변환해야 합니다.

```json
{
  "format": "wook-catalog-1",
  "records": [{
    "id": "WOOK-DOCKER-001",
    "kind": "POLICY",
    "title": "특권 컨테이너 실행 여부",
    "modified": "2026-11-11T00:00:00Z",
    "severity": "HIGH",
    "platform": "any",
    "check": "docker.privileged",
    "expected": "false",
    "reference": "https://docs.docker.com/engine/containers/run/#runtime-privilege-and-linux-capabilities"
  }]
}
```

`kind`를 생략한 레코드는 OSV 권고로 취급합니다. `id`, UTC ISO-8601 `modified`, `affected[].package.name`·`ecosystem`이 필요합니다. 철회 시에는 OSV `withdrawn` 시각을 제공합니다. CCE/POLICY 규칙도 철회 시각으로 판정에서 제외할 수 있습니다.

## CVE 대조

[OSV 다운로드](https://google.github.io/osv.dev/data/)에서 Maven 등 생태계별 데이터를 준비할 수 있습니다. 배포본의 출처·라이선스·수정 시각을 보존하세요.

외부 준비 PC의 `python3 scripts/prepare-maven-catalog.py`는 공식 `Maven/all.zip`을 받아 `updates/maven-osv.zip`과 `.manifest.json` 출처 기록을 생성합니다. 컨테이너는 다운로드하지 않으며 관리자 갱신으로만 반영합니다. 이 파일은 Git에 넣지 않습니다. 용량 상한을 넘는 미래 덤프는 묶음을 나눠 반입해야 합니다.

- 현재 자동 비교는 **Maven**의 정확한 `groupId:artifactId`와 라이브러리 버전에 한정합니다.
- `ECOSYSTEM` 범위의 `introduced`, 배타적 `fixed`·`limit`, 포함되는 `last_affected`와 명시적 `versions`를 지원합니다. Maven `ComparableVersion`으로 비교합니다.
- `versions`에 없다는 이유만으로 미발견을 판정하지 않습니다. 해석할 수 없는 범위, 식별 정보 부족, 저장된 기준 부재는 점검 불가입니다.
- CVE alias가 있으면 보고서에 CVE ID를 표시합니다. CVE alias가 없는 OSV 권고도 원래 ID로 보존합니다.
- 존재하는 파일의 버전 대조이며 실제 클래스 로딩, 배포판 backport, 런타임 도달성, 설정에 따른 악용 여부를 증명하지 않습니다.

## CCE와 내부 정책

CCE는 설정 식별자이며 실행 코드가 아닙니다. 공식 근거와 적용 OS를 확인한 뒤 허용된 `check`와 `expected`를 연결하세요. 실제 CCE ID는 `kind: CCE`, 사용자 정의 기준은 `kind: POLICY`로 구분합니다.

| check | 수집 값 / 조건 |
| --- | --- |
| `ssh.permitrootlogin`, `ssh.passwordauthentication` | `sshd -T`의 전역 유효 설정. 권한 부족과 Match 블록에 대한 개별 연결 설정은 미판정 |
| `kernel.randomize_va_space`, `kernel.kptr_restrict` | `sysctl -n` 값 |
| `docker.privileged`, `docker.readOnlyRootfs` | Docker inspect의 불리언 문자열 `true` / `false` |
| `docker.user` | Docker Config.User 원문. 빈 문자열은 이미지 설정상 기본 사용자이며 실제 실행 UID 증명은 아님 |

`platform`은 `/etc/os-release`의 `ID:VERSION_ID의 대버전`으로 지정합니다(예: `rhel:9`). `any`는 POLICY만 허용합니다. 적용 플랫폼이 일치하지 않거나 값이 없으면 점검 불가로 기록합니다. 업로드한 셸·정규식·임의 명령을 실행하는 규칙은 지원하지 않습니다.

## 갱신 정책

전체 묶음을 검증한 후 하나의 DB 트랜잭션으로 ID별 추가·갱신합니다. 실패하면 기존 데이터가 유지됩니다. `modified`가 더 오래된 레코드는 기존 데이터를 덮어쓰지 않습니다. 묶음에서 빠진 ID는 삭제하지 않으므로 철회 정보도 반드시 반입하세요. 성공/실패, 원본, 레코드 수, SHA-256과 카탈로그 개정 번호를 기록합니다.

정기 업데이트는 기본 꺼짐입니다. 6필드 Spring cron과 시간대(기본 `Asia/Seoul`)를 지정하며 30초 간격으로 도래 여부를 확인합니다. 중단 중 지난 일정은 재시작 후 한 번 실행하고 다음 시각을 계산합니다. 실행 설정은 DB에 저장됩니다. 실패한 동일 실행은 즉시 반복하지 않으며 다음 주기 또는 관리자의 즉시 갱신으로 재시도합니다.

과거 점검의 JSON·XLSX·MD는 당시 결과와 개정 번호를 유지합니다. 최신 기준으로 다시 확인하려면 새 점검을 실행하세요. 초기 목록의 5건만으로 운영 전체의 취약점 유무를 판단할 수 없습니다.

## 기본 데이터 출처

`src/main/resources/catalog/bootstrap.json`은 최초 DB 생성 시 한 번 반영됩니다.

- CVE-2021-44228 / [GHSA-jfh8-c2jp-5v3q](https://api.osv.dev/v1/vulns/GHSA-jfh8-c2jp-5v3q), CVE-2021-45046 / [GHSA-7rjr-3q55-vv33](https://api.osv.dev/v1/vulns/GHSA-7rjr-3q55-vv33): GitHub Advisory Database를 OSV API로 2026-11-11 취득. 원본 내용·alias·수정 시각 유지. [CC-BY-4.0](https://github.com/github/advisory-database/blob/main/LICENSE.md).
- CCE-80901-2 (RHEL 8), CCE-90800-4 (RHEL 9): [ComplianceAsCode SSH root 로그인 규칙](https://github.com/ComplianceAsCode/content/blob/master/linux_os/guide/services/ssh/ssh_server/sshd_disable_root_login/rule.yml)의 ID·적용 OS·기대값을 참조해 간소화한 조회 규칙. 전체 SCAP 규칙이나 인증된 준수 점검을 대체하지 않음. [BSD-3-Clause](https://github.com/ComplianceAsCode/content/blob/master/LICENSE).
- WOOK-DOCKER-001: 이 프로젝트의 내부 정책. 공식 CCE 번호가 아님.
