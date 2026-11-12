# 폐쇄망 취약점 카탈로그

## 입력 형식

관리자는 파일 업로드 또는 `updates/`에 반입한 파일을 통해 DB를 갱신합니다. HTTPS 미러는 운영 설정 `OBSERVER_MIRROR_HOSTS`에 등록된 호스트의 443 포트만 허용하며 리다이렉트·URL 자격 증명·쿼리 문자열을 허용하지 않습니다. TLS 인증서 검증은 유지됩니다. 내부 CA는 이미지의 신뢰 저장소에 설치해 배포하세요.

지원 입력은 다음과 같습니다.

1. [OSV 형식](https://ossf.github.io/osv-schema/) JSON 한 건 또는 배열.
2. OSV JSON 파일만 포함한 ZIP. 경로를 파일시스템에 풀지 않고 읽습니다.
3. `{ "format": "wook-catalog-1", "records": [...] }` 묶음. OSV와 아래 CCE/POLICY 규칙을 함께 넣을 수 있습니다.

최대 업로드는 64MiB, ZIP 압축 해제 합계는 256MiB, 개별 ZIP 항목은 4MiB, 레코드는 50,000개입니다. 큰 데이터셋은 정해진 상한에 맞춰 여러 묶음으로 나눠 반입하세요. NVD 원본 피드나 CVE List V5는 직접 지원하지 않으며 OSV 또는 지원 규칙 형식으로 변환해야 합니다.

이미 설치한 서버에 데이터를 추가할 때는 앱 재설치 없이 **데이터 업데이트 → 취약점 목록 반입 → JSON / ZIP 파일 → 검증 후 반영**을 사용합니다. 성공 이력과 전체 건수를 확인하고 **새 점검**을 실행하세요. 기존 보고서는 당시 결과를 유지합니다.

데이터 전용 전달 묶음에는 업로드 파일을 `import/` 아래에 두고, 출처·라이선스·SHA-256·한국어 반입 안내를 함께 제공합니다. 안내와 라이선스가 포함된 **전달용 바깥 ZIP은 먼저 압축을 풀고**, 내부 `import/maven-osv.zip`을 그대로 업로드하세요. 같은 내용의 `maven-osv.json`이 있으면 ZIP과 JSON 중 하나만 반영합니다. manifest·검증 결과 JSON은 카탈로그가 아니므로 업로드하지 않습니다.

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

2026-11-12 데이터 전용 반입본은 공식 Maven 덤프 **7,257건**이며 원본 ZIP의 갱신 시각은 2026-11-10 19:48:24 UTC입니다. 시작용 5건이 있는 DB에 반입하면 기존 CVE 두 건은 ID로 갱신되어 **CVE/OSV 7,257건·CCE 2건·POLICY 1건, 총 7,260건**이 됩니다. 권고 7,257건에는 철회 122건도 포함되며 고유 CVE 별칭 수는 7,078개입니다. 전체 OS/RPM 취약점 DB나 CCE 확장 데이터가 아닙니다. 파일은 Git 제외 `resources/catalog/wook-observer-data-20261112/`에 보관하며 ZIP 원본과 같은 레코드의 JSON 대안을 제공합니다.

- 자동 비교는 **Maven** 패키지와 버전 범위입니다. `pom.properties`의 정확한 `groupId:artifactId`를 우선하며 Manifest가 먼저 있어도 탐색을 계속합니다. 묶음 JAR의 여러 Maven 항목과 중첩 JAR를 보존합니다.
- 좌표가 없는 JAR는 파일명의 정확한 artifactId와 수집 버전이 일치하고 내부 카탈로그의 groupId 후보가 하나일 때만 후보 대조합니다. 영향 범위에 들면 **의심**이며 좌표 확인이 필요합니다. 여러 후보·버전 모순은 미판정입니다.
- `ECOSYSTEM` 범위의 `introduced`, 배타적 `fixed`·`limit`, 포함되는 `last_affected`와 명시적 `versions`를 지원합니다. Maven `ComparableVersion`으로 비교합니다.
- `versions`에 없다는 이유만으로 미발견을 판정하지 않습니다. 해석할 수 없는 범위, 식별 정보 부족, 저장된 기준 부재는 미판정입니다.
- CVE alias가 있으면 대표 CVE ID를 표시하며 원래 권고 ID와 모든 별칭을 보존합니다. CVE alias가 없는 OSV 권고도 원래 ID로 보존합니다. 동일 ID·직군·패키지·버전은 한 조치로 묶습니다.
- 설치 버전이 들어 있는 영향 구간의 `fixed`를 수정 경계로 안내합니다. 다른 분기의 수정 버전을 일괄 권고하지 않으며 여러 CVE의 영향과 호환성을 함께 검토해야 합니다. `limit`는 수정 버전으로 안내하지 않습니다.
- 존재하는 파일의 버전 대조이며 실제 클래스 로딩, 배포판 backport, 런타임 도달성, 설정에 따른 악용 여부를 증명하지 않습니다.

## CCE와 내부 정책

CCE는 설정 식별자이며 실행 코드가 아닙니다. 공식 근거와 적용 OS를 확인한 뒤 허용된 `check`와 `expected`를 연결하세요. 실제 CCE ID는 `kind: CCE`, 사용자 정의 기준은 `kind: POLICY`로 구분합니다.

| check | 수집 값 / 조건 |
| --- | --- |
| `ssh.permitrootlogin`, `ssh.passwordauthentication` | `sshd -T`의 전역 유효 설정. 권한 부족과 Match 블록에 대한 개별 연결 설정은 미판정 |
| `kernel.randomize_va_space`, `kernel.kptr_restrict` | `sysctl -n` 값 |
| `docker.privileged`, `docker.readOnlyRootfs` | Docker inspect의 불리언 문자열 `true` / `false` |
| `docker.user` | Docker Config.User 원문. 빈 문자열은 이미지 설정상 기본 사용자이며 실제 실행 UID 증명은 아님 |

`platform`은 `/etc/os-release`의 `ID:VERSION_ID의 대버전`으로 지정합니다(예: `rhel:9`). `any`는 POLICY만 허용합니다. 알려진 플랫폼이 일치하지 않으면 비대상, 플랫폼이나 설정 값이 없으면 미판정입니다. 둘 다 취약점 조치 목록과 건수에서 제외하고 점검 범위에 집계합니다. 플랫폼이 맞고 실제 값이 기대값에 어긋날 때만 발견입니다. 업로드한 셸·정규식·임의 명령을 실행하는 규칙은 지원하지 않습니다.

## 갱신 정책

전체 묶음을 검증한 후 하나의 DB 트랜잭션으로 ID별 추가·갱신합니다. 실패하면 기존 데이터가 유지됩니다. `modified`가 더 오래된 레코드는 기존 데이터를 덮어쓰지 않습니다. 묶음에서 빠진 ID는 삭제하지 않으므로 철회 정보도 반드시 반입하세요. 성공/실패, 원본, 레코드 수, SHA-256과 카탈로그 개정 번호를 기록합니다.

정기 업데이트는 기본 꺼짐입니다. 6필드 Spring cron과 시간대(기본 `Asia/Seoul`)를 지정하며 30초 간격으로 도래 여부를 확인합니다. 중단 중 지난 일정은 재시작 후 한 번 실행하고 다음 시각을 계산합니다. 실행 설정은 DB에 저장됩니다. 실패한 동일 실행은 즉시 반복하지 않으며 다음 주기 또는 관리자의 즉시 갱신으로 재시도합니다.

과거 점검의 JSON·XLSX·MD는 당시 결과와 개정 번호를 유지합니다. 최신 기준으로 다시 확인하려면 새 점검을 실행하세요. 초기 목록의 5건만으로 운영 전체의 취약점 유무를 판단할 수 없습니다.

## 기본 데이터 출처

`src/main/resources/catalog/bootstrap.json`은 최초 DB 생성 시 한 번 반영됩니다.

- CVE-2021-44228 / [GHSA-jfh8-c2jp-5v3q](https://api.osv.dev/v1/vulns/GHSA-jfh8-c2jp-5v3q), CVE-2021-45046 / [GHSA-7rjr-3q55-vv33](https://api.osv.dev/v1/vulns/GHSA-7rjr-3q55-vv33): GitHub Advisory Database를 OSV API로 2026-11-11 취득. 원본 내용·alias·수정 시각 유지. [CC-BY-4.0](https://github.com/github/advisory-database/blob/main/LICENSE.md).
- CCE-80901-2 (RHEL 8), CCE-90800-4 (RHEL 9): [ComplianceAsCode SSH root 로그인 규칙](https://github.com/ComplianceAsCode/content/blob/master/linux_os/guide/services/ssh/ssh_server/sshd_disable_root_login/rule.yml)의 ID·적용 OS·기대값을 참조해 간소화한 조회 규칙. 전체 SCAP 규칙이나 인증된 준수 점검을 대체하지 않음. [BSD-3-Clause](https://github.com/ComplianceAsCode/content/blob/master/LICENSE).
- WOOK-DOCKER-001: 이 프로젝트의 내부 정책. 공식 CCE 번호가 아님.

## RPM 보안 권고 — 1.5.0 이상

공식 [OSV 생태계 덤프](https://google.github.io/osv.dev/data/)의 Red Hat·Rocky Linux·AlmaLinux 자료에서 8/9/10 보안 권고를 선별합니다. 준비 PC에서 `python3 scripts/prepare-rpm-catalog.py`를 실행하세요. 원본 캐시는 기본 `resources/catalog-cache/`이고 `--cache-directory`로 변경할 수 있습니다. `--use-cache`도 원본 해시·크기를 검증합니다. 실행 중인 Observer는 외부에서 다운로드하지 않습니다.

2026-11-12 반입본은 RHEL RHSA **12,646건**, Rocky RLSA/RXSA **4,769건**, AlmaLinux ALSA **5,218건**입니다. 철회 10건을 포함합니다. 비보안 RHBA/RHEA/ALBA/ALEA는 제외합니다. Maven 7,257건·CCE 2건·정책 1건과 합쳐 **29,893건**이며 고유 CVE 수가 아닙니다. 선택한 권고의 전체 JSON은 바꾸지 않아 다른 제품의 affected 정보도 일부 보존되지만 판정에서는 대상 배포판·스트림을 제한합니다.

`updates/rpm-redhat-01.zip`~`03.zip`, `rpm-rocky-01.zip`, `rpm-almalinux-01.zip`을 각각 반영합니다. 분할은 해제 크기 48 MiB·20,000건 이내이며 향후 파일 수는 달라질 수 있으므로 `rpm-catalog.manifest.json`의 `files`를 따르세요. ZIP 안에는 직접 읽을 수 있는 권고별 JSON이 들어 있습니다. manifest는 출처·취득/원본 수정 시각·SHA-256·분할별 건수를 담으며 업로드 대상은 아닙니다. 업로드는 기존 ID를 갱신하고 다른 배포판 자료를 유지합니다.

설치 RPM의 이름·Epoch·Version·Release·아키텍처·공급자와 OS를 확인한 뒤 해당 배포판의 보안 권고에만 대조합니다. [RPM EVR 순서](https://rpm-software-management.github.io/rpm/man/rpm-version.7)로 Epoch, 숫자 구간, Release, `~`/`^`를 비교하며 `introduced`, 배타적 `fixed`/`limit`, 포함되는 `last_affected`, 명시적 영향 버전을 지원합니다. 수정 버전은 일치한 구간의 `fixed`만 표시하므로 배포판 backport를 upstream 버전만으로 오판하지 않습니다.

- RHEL은 활성 저장소 ID와 [공식 repository-to-CPE](https://security.access.redhat.com/data/metrics/repository-to-cpe.json)를 연결합니다. 저장소 URL·인증정보는 수집하지 않습니다. EUS/AUS/E4S/TUS는 `/etc/dnf/vars/releasever` 또는 `/etc/yum/vars/releasever`의 명시적 고정 값도 필요합니다. 이름을 바꾼 내부 저장소·부족한 매핑·모순된 설정은 미판정입니다.
- 모듈 RPM은 OSV의 스트림 대응 정보가 부족하여 미판정입니다. 외부 공급자의 RPM도 배포판 공식 패키지로 단정하지 않습니다. 설치 목록이 일부이면 전체 미수집 범위를 별도로 남기고 확인된 패키지만 비교합니다.
- 소스 RPM 이름만 대응하는 바이너리는 버전·Release가 정확히 연결될 때 의심으로 표시합니다. 소스 Epoch와 바이너리별 영향을 따로 확인해야 하므로 이를 확정 발견이나 안전 판정으로 올리지 않습니다.
- 결과의 `kind: RPM`은 보안 권고 단위이며 데이터 저장 종류는 기존 `CVE`를 유지합니다. `upstream`·`related`의 CVE를 `aliases`로 바꾸지 않고 `relatedIds`에 분리합니다. 한 권고에 든 모든 CVE가 그 바이너리에 개별 적용된다는 의미는 아닙니다.
- 컨테이너별 RPM DB·외부 도달성·실제 악용 가능성은 이 비교로 확정하지 않습니다. CLI는 데이터베이스 없이 수집만 수행합니다.

### 출처와 재배포 고지

Red Hat 보안 데이터와 저장소/CPE 매핑은 [Red Hat Security Data](https://access.redhat.com/security/data)의 **CC BY 4.0**을 따릅니다. 저작자 Red Hat, Inc.를 표시하며 [라이선스 원문](licenses/cc-by-4.0.txt)을 동봉합니다. `src/main/resources/catalog/redhat-repositories.json`은 2026-11-12 공식 매핑에서 지원 제품의 저장소/CPE만 추린 파생물이며 변환·취득 시각·원본 SHA-256을 파일 안에 기록했습니다.

Rocky 자료는 원본 `database_specific.license`가 있으면 그대로 보존하며 CC BY 4.0 고지를 함께 제공합니다. Rocky Linux Project Authors의 [BSD 3-Clause 고지](licenses/rocky-bsd-3-clause.txt)와 [공식 라이선스 안내](https://rockylinux.org/legal/licensing)도 동봉합니다. AlmaLinux OS Foundation의 OSV 데이터베이스는 [MIT 라이선스](licenses/almalinux-mit.txt)를 따릅니다. 데이터 전달 묶음에는 Maven·CCE의 기존 라이선스도 함께 유지합니다. OS/RPM 바이너리 설치 파일은 이 취약점 자료에 포함되지 않습니다.
