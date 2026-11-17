# Wook Observer

등록 서버의 자산 정보와 서비스 운영대장을 관리하고, **실행 프로세스 → JVM·라이브러리 → Docker 컨테이너** 점검 이력·명세·취약점 대조 결과를 연결하는 내부망 웹서비스입니다.

현재 버전은 **1.11.0**입니다. RHEL·CentOS 7.x의 구형 도구로 수집한 자산도 반입하고, 자산 목록·상세·업로드 미리보기·MD/XLSX에서 **OS 업그레이드 필요**를 표시합니다. 중앙 서비스는 Spring Boot 4.1.2 / Java 21.0.13 이상 / PostgreSQL 18로 구성하고 Docker Compose로 실행합니다. 대상 Linux 서버는 SSH 또는 현장 실행 수집 파일로 점검하며 **Java를 새로 설치할 필요가 없습니다.**

## Docker로 시작하기

Docker Engine, Docker Compose, `openssl`이 필요합니다. 최초 이미지 빌드는 인터넷이 가능한 환경에서 수행합니다.

```bash
./scripts/init-env.sh
# 생성된 .env의 OBSERVER_ADMIN_PASSWORD를 로컬에서 확인하세요.
docker compose up -d --build
docker compose ps
```

브라우저에서 **http://localhost:18080**에 접속하고 `admin` / `.env`의 초기 비밀번호로 로그인하세요. 초기 계정은 DB가 비어 있을 때만 생성됩니다. 이후 비밀번호는 사용자 관리에서 변경합니다. `.env`를 바꿔도 기존 계정의 비밀번호는 초기화되지 않습니다.

웹 계정 비밀번호는 **8자 이상, UTF-8 72바이트 이하**입니다. 최초 `.env` 관리자 비밀번호와 사용자 추가·비밀번호 변경에 동일하게 적용합니다. 계정 편집에서 비밀번호를 비워 두면 기존 값을 유지합니다.

- `DATABASE_PASSWORD`: PostgreSQL 접속 비밀번호.
- `OBSERVER_ENCRYPTION_KEY`: SSH 키·비밀번호 자격 증명의 AES-256-GCM 암호화 키. **DB 백업과 별도로 보관**하고 임의로 교체하지 마세요.
- `OBSERVER_BIND_ADDRESS`, `OBSERVER_PORT`: 기본 `127.0.0.1:18080`. 다른 PC에 제공하려면 내부 HTTPS 프록시를 구성하세요. HTTPS 사용 시 `COOKIE_SECURE=true`를 지정합니다.
- `OBSERVER_MIRROR_HOSTS`: 정기 업데이트를 허용할 내부 HTTPS 미러 호스트. 쉼표로 구분하며 기본값은 비어 있습니다.

앱은 비루트 계정·읽기 전용 파일시스템으로 실행하며 DB 포트와 호스트 Docker 소켓을 공개하지 않습니다. 데이터는 Compose의 `database` 볼륨에 보관합니다. **앱은 단일 인스턴스로 운영**하세요. 작업 큐와 재시작 복구는 단일 인스턴스를 기준으로 합니다.

## 점검 대상 등록과 실행

1. 관리자 → **점검 대상 → 대상 등록**에서 이름, SSH 호스트·포트·계정을 입력합니다.
2. 기본 **SSH 계정 비밀번호**를 입력하거나 **SSH 개인 키**를 선택합니다. 비밀번호 방식에는 키 파일이 필요 없습니다. 암호화된 개인 키는 키 암호도 입력하세요.
3. **연결 정보 저장**을 누르면 서버 지문을 자동 조회합니다. 관리자가 확인한 SHA-256 지문과 대조하고 **지문 확인 후 저장**을 누르세요. 공개 키 직접 입력은 **서버 확인 방식 변경**에서 선택할 수 있습니다.
4. 일반 계정으로 서버 전체 권한 점검이 필요하면 **root 비밀번호로 su 전환 / sudo 비밀번호 / 비밀번호 없는 sudo**를 선택합니다. root 접속 계정이면 접속 계정 권한을 그대로 사용하세요.
5. **점검 시작**을 누릅니다. 사용자 계정도 등록된 모든 대상의 점검과 결과 조회가 가능합니다.
6. **점검 이력 → 상세 보기**에서 수집 제한, 취약점 판정, XLSX·ZIP·JSON·요약 MD를 확인합니다.

호스트 주소는 중앙 앱 컨테이너에서 접속할 수 있어야 합니다. 대상에 `127.0.0.1`을 입력하면 앱 컨테이너 자신을 가리키므로, 같은 PC를 점검할 때도 해당 PC의 접근 가능한 SSH 주소를 입력하세요.

| 수집 영역 | 대상 서버 조건 / 한계 |
| --- | --- |
| OS·프로세스 | Linux, SSH, POSIX 셸, `ps` 및 기본 명령. Java 불필요. 접근 가능한 PID 범위만 조사 |
| OS·커널 상세 | `uname -a/-r/-v/-m`, `/etc/os-release`, `/etc/redhat-release`, `/proc/version`. `base64`로 전송하며 항목당 16 KiB 제한 |
| 설치 RPM | `rpm`, `timeout`, RPM DB 읽기 권한. 이름·Epoch·버전·Release·아키텍처·소스 RPM·모듈·공급자, 20초·20,000건·4 MiB 제한 |
| 열린 포트 | `ss`, `timeout`. TCP 리스닝·UDP 바인딩, IPv4/IPv6 주소·포트·상태·확인 가능한 PID/프로세스. 프로토콜별 5초·합계 4,096개·512 KiB 제한 |
| Docker | Docker CLI, `timeout`, 로컬 소켓 조회 권한. 컨테이너 정보와 `docker top` PID 연결 |
| JVM 속성 | 실행 중인 Java 서비스, 사용할 수 있는 `jcmd`, `timeout`, attach 권한 |
| Java 라이브러리 | Python 3와 `/proc`·JAR 읽기 권한. 실행 JAR, 클래스패스, 열린 JAR, Spring Boot/WAR 중첩 JAR의 메타데이터 |
| 설정 점검 | 수집된 `sysctl`, `sshd -T` 전역 값, Docker 특권·읽기 전용 루트·사용자 설정 |

Python 3나 `jcmd`가 없어도 기본 점검은 수행하고 상세 미수집 사유를 남깁니다. SSH 수집은 고정된 조회 스크립트를 실행하며 대상에 파일이나 Java를 설치하지 않습니다. 선택한 su/sudo 전환으로 고정 수집 스크립트를 실행할 수 있으며, 임의 명령·대상 권한 정책 변경·컨테이너 변경·힙 덤프·강제 GC는 수행하지 않습니다. `jcmd`는 선별 속성 조회를 위한 attach만 사용합니다.

인증 옵션·지문 확인·root 전환 조건은 [SSH 접속과 점검 권한](docs/ssh-access.md)을 확인하세요. 비밀번호는 암호화해 보관하며 전환 실패를 일반 권한 점검 성공으로 표시하지 않습니다. 보고서에 실제 실행 계정·UID를 기록합니다.

웹은 **주황색·밝은 배경**, 관측 렌즈를 형상화한 Observer 로고와 통일된 SVG 메뉴 아이콘을 사용합니다. 글꼴은 1.0.0의 일반 고딕체 계열로 복원하고, 본문·입력 18px와 표·설명 16px는 유지합니다. 글꼴 대체 파일과 아이콘을 앱에서 제공하며 외부 CDN을 사용하지 않습니다. [화면·연결 등록 안내](docs/interface.md)를 참고하세요.

동시 점검은 2개, 대기열은 16개, SSH 작업은 최대 180초입니다. 프로세스 4,096개, 컨테이너 256개, JVM 64개, JVM별 라이브러리 512개 등 제한을 적용합니다. 컨테이너 안의 SSH 서버에서는 호스트 PID와 컨테이너 PID를 임의 연결하지 않습니다.

## 자산 관리와 서비스 운영대장

**대시보드 → 자산 관리**에서 등록 자산과 서비스 후보, 운영 절차 미입력, 자산별 최근 완료 점검의 발견·의심을 확인합니다. 서버·주소·자산번호·담당자 검색과 환경·보완 상태 필터를 제공합니다.

자산을 열면 담당·서버 위치·용도·환경·운영 상태를 저장하고, 서비스 그리드에서 **SW/버전·실행계정·APP/DATA/LOG·포트·시작/종료/상태 확인 방법·연계 대상·비고**를 편집할 수 있습니다. 기본 **운영 절차** 보기와 **전체 항목** 보기를 전환할 수 있습니다. 점검에서 확인한 프로세스·컨테이너·소켓으로 후보를 채우고 수동 서비스도 추가합니다.

자산 화면은 전체 가로 폭과 13px 표 글자를 사용합니다. 왼쪽 메뉴는 16px, 폭은 240px(중간 화면 220px)로 줄여 본문 공간을 늘렸습니다. 포트의 자동 관측·미입력 안내와 수정 이력은 상세 팝업에서 확인하고 편집 버튼은 표 맨 오른쪽에 고정합니다. 포트는 프로토콜/번호별 최대 4개 요약, PID는 별도 열에서 최대 3개만 표시하며 전체 주소·Docker 매핑·PID는 마우스오버·키보드·터치로 확인합니다. 목록의 **전체 자산 XLSX**, 서버 상세의 **자산 XLSX**에서 저장된 전체 항목(보관 포함)을 내려받을 수 있습니다. 검색/필터와 저장 전 입력은 내보내기에 적용하지 않습니다.

**시스템 서비스 항목 제외** 버튼을 켜면 커널·systemd·SSH·시간 동기화 등 식별 가능한 OS 서비스 행을 숨기고, 다시 누르면 표시합니다. 기본은 전체 표시이며 수기 정보와 XLSX 전체 항목은 보존합니다. PID는 쉼표 없이 개별 숫자 배지로 보여줍니다. 왼쪽 하단은 **내부망 점검 플랫폼 (1.11.0)** 한 줄로 정리하고 본문 하단의 브랜드 푸터를 제거했습니다.

수기 정보는 DB에 저장하며 재점검으로 덮어쓰지 않습니다. 미관측 항목은 보존하고 보관함으로 옮기거나 복원할 수 있습니다. ADMIN은 편집, USER는 조회·점검하며 동시 수정은 충돌로 안내합니다. **명령은 기록용이며 실행하지 않습니다.** 과거 점검 보고서는 당시 스냅샷을 유지합니다.

사용자가 제공한 Excel의 세 시트, 병합 셀·열 밀림 분석, 컬럼 대응과 후속 반입 설계는 [자산 관리 안내](docs/asset-management.md)에 있습니다. SSH 없는 자산은 파일 업로드 방식으로 등록할 수 있습니다. Excel 자동 반입·Windows 수집·공통 템플릿은 후속 범위입니다.

1.10.0부터 서비스 편집을 최대 1,440px의 넓은 창으로 제공합니다. 기본 정보·경로/포트·실행 절차·연계/메모를 나누고 저장 버튼을 하단에 고정합니다. 자산의 위치 항목은 **서버 위치**로 표시합니다.

**전체 자산 MD 보고서 / 자산 MD 보고서**에서 저장된 자산과 서비스 운영대장을 한 문서로 보고 **MD 다운로드**로 같은 내용을 저장합니다. 시스템·보관 서비스, 전체 포트/PID와 여러 줄의 실행 절차를 포함합니다.

## 서버에서 직접 실행하고 결과 업로드

1. **자산 관리 → 수집 결과 업로드**에서 **수집 SH 다운로드**를 누릅니다.
2. 점검할 Linux 서버로 옮겨 `sh observer-collect.sh`를 실행합니다.
3. 생성된 `.observer` 파일을 가져와 **파일 내용 확인 → 확인한 결과 반영**으로 업로드합니다. 새 자산 등록과 기존 자산 반영을 지원합니다.

SSH 계정 등록 없이 사용할 수 있으며 현재 계정으로 최대 180초 동안 읽기 중심으로 수집합니다. 파일 크기는 16 MiB까지이며, 도구/권한 부족은 수집 제한으로 남깁니다. 중복 파일·손상 파일을 검증하고 과거 수집 결과는 이력에만 추가합니다. 수기 정보와 최신 관측값은 보존합니다. 자세한 실행 조건·원본 보관·반영 규칙은 [직접 실행 수집 안내](docs/offline-collection.md)를 확인하세요.

## 취약점 데이터와 관리자

- **사용자 관리**: 사용자 추가, ADMIN/USER 권한 변경, 비밀번호 재설정, 비활성화. 변경된 계정의 기존 세션은 다음 요청에서 만료됩니다.
- **취약점 데이터**: DB에 저장한 CVE/OSV, CCE, 내부 설정 규칙 검색.
- **데이터 업데이트**: OSV JSON/ZIP 또는 자체 묶음 파일 업로드, 반입 폴더/내부 미러 원본 설정, 즉시 갱신, 6필드 cron 예약, 결과 이력.
- 기본 목록은 **CVE 2건·RHEL CCE 2건·Docker 정책 1건**입니다. 전체 취약점 DB가 아니며 운영 범위의 최신 데이터를 별도로 반입해야 합니다.
- 정확한 Maven 좌표와 버전을 OSV 영향 범위와 비교합니다. **RHEL·Rocky·AlmaLinux 8/9/10의 설치 RPM**은 해당 배포판의 RHSA·RLSA/RXSA·ALSA 보안 권고에 RPM EVR로 대조합니다. 소스 RPM 대응만 가능한 항목은 의심이며, 모듈·공급자·RHEL 제품 스트림이 미확인인 경우 미판정입니다. CCE는 식별자 목록만으로 판정하지 않고 **적용 OS·실제 설정·기대값**이 있는 규칙만 점검합니다. 내부 Docker 규칙은 CCE와 구분합니다.
- 조치 목록에는 `발견`·`의심`만 표시합니다. CCE 플랫폼 불일치는 비대상, 데이터·설정·식별 정보 부족은 미판정으로 따로 집계합니다. 버전 일치는 악용 가능성을 확정하지 않으며 미판정은 안전 판정이 아닙니다.
- Maven 메타데이터가 없는 JAR도 정확한 파일명 artifactId·버전으로 내부 카탈로그에서 유일한 후보를 찾으면 `의심`으로 표시합니다. 여러 groupId 후보나 버전 불일치는 미판정입니다.

반입 형식·출처·갱신 정책은 [docs/catalog.md](docs/catalog.md)를 확인하세요. 새 데이터를 반입한 뒤 **새 점검을 시작**하면 적용됩니다. 과거 보고서는 당시 데이터 버전과 결과를 보존합니다.

인터넷이 가능한 준비 PC에서 `python3 scripts/prepare-maven-catalog.py`를 실행하면 공식 OSV Maven 전체 덤프를 `updates/maven-osv.zip`에 받고 크기·ZIP CRC·SHA-256을 확인합니다. 이 ZIP을 폐쇄망으로 반입해 관리자 화면에서 업로드하거나 업데이트 원본을 `maven-osv.zip`으로 저장한 뒤 갱신하세요. 다운로드 스크립트는 서비스 실행 중 자동 호출되지 않습니다.

2026-11-12 확장 반입본은 **Maven 7,257건 + RPM 보안 권고 22,633건**입니다. RHEL 12,646건·Rocky 4,769건·AlmaLinux 5,218건과 기존 CCE 2건·정책 1건을 합쳐 기본 DB의 최종 목록은 **29,893건**입니다. 권고 수이며 고유 CVE 수와는 다릅니다.

**RPM 대조·포트 수집은 앱 1.5.0 이상과 새 점검이 필요합니다.** 기존 설치는 아래 폐쇄망 업그레이드 절차로 앱을 교체하고, 데이터 전달 ZIP을 풀어 `import/maven-osv.zip`과 `import/rpm-*.zip` 5개를 각각 업로드하세요. 같은 내용의 Maven JSON은 대안이며 ZIP과 중복 반영할 필요가 없습니다. 바깥 전달 ZIP·manifest는 업로드 대상이 아닙니다.

외부 준비 PC(Python 3.11 이상)에서 OS/RPM 자료를 다시 준비하려면 아래를 실행합니다. 원본을 수정하지 않고 8/9/10 관련 보안 권고와 철회를 선별하고, Red Hat 데이터는 업로드·메모리 부담을 줄이도록 분할합니다.

```bash
python3 scripts/prepare-rpm-catalog.py
# 검증된 캐시를 재사용할 때
python3 scripts/prepare-rpm-catalog.py --use-cache
# Maven/RPM·JSON 대안·출처·라이선스를 데이터 전용 ZIP으로 포장
python3 scripts/package-catalog.py
```


## 보고서

동일 스냅샷을 DB에 저장하며 다시 다운로드해도 서버를 재점검하지 않습니다.

- 첫 화면과 `summary.md`는 대상·수집 시각·중복 제거한 조치 수·명세 링크를 제공합니다. `actions.md`는 발견·의심의 ID, 패키지·설치 버전, 영향 위치, 근거·조치를 담습니다.
- `coverage.md`는 비대상·미판정·미발견을 이유별로 요약합니다. 같은 CVE/패키지/버전/직군의 반복 결과와 라이브러리를 묶고 PID·컨테이너·경로로 추적합니다.
- `report.xlsx`는 요약 → 취약점 상세 → 점검 범위 순서입니다. 웹 35개 시트 중 빈 명세·MD/JSON 원문은 기본 숨김이며 Excel의 시트 탭 우클릭 → 숨기기 취소로 열 수 있습니다. 원문은 분할 보존하며 수식으로 실행하지 않습니다.
- `report.json`, `host/{os,rpm-packages,listening-ports}.md`, `services/<직군>/{processes,open-source,containers}.md`를 유지합니다. 직군은 애플리케이션·웹·DB·캐시·메시징·인프라·기타입니다. ZIP은 총 29개 파일입니다.
- RPM 명세에는 설치 EVR과 판정, 조치에는 공식 권고·관련 CVE·수정 EVR을 표시합니다. 열린 포트 명세는 현재 네트워크 네임스페이스의 소켓 목록입니다. 방화벽 통과·외부 도달 가능성을 의미하지 않으며 PID는 권한에 따라 미확인일 수 있습니다. 도구 없음·실패·시간/수집량 제한을 구분합니다.

판정 예시, 집계 기준과 이전 보고서 취급은 [운영자 보고서 안내](docs/operator-report.md)를 확인하세요.

## 개발 및 기존 일회성 JAR

**JDK 21 계열 21.0.13 이상**이 필요합니다. Maven Wrapper를 사용합니다.

```bash
./mvnw verify
# SSH의 선택적 Python JAR 수집기 회귀 검사 (개발 PC)
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s src/test/python
# 자산 포트 요약·PID 표시 회귀 검사 (개발 PC의 Node.js 18 이상)
node --test src/test/js/*.test.cjs
# DB 없이 로컬 서버를 한 번 점검하고 종료
java -jar target/wook-observer-1.11.0.jar --observer.report-dir=./reports/check-001
```

일회성 CLI는 기존 로컬 수집기를 유지하며 취약점 DB 대조를 수행하지 않습니다(`미실시`). CLI XLSX는 34개 시트입니다. 출력 디렉터리는 새 경로여야 합니다.

기존 로컬 HTTP API가 꼭 필요한 경우 `--spring.profiles.active=collector`로 실행합니다. 이 모드는 인증 없이 **loopback 전용**으로 운영하며 `POST /api/v1/observations`, `/api/v1/reports`, `/api/v1/reports.xlsx`를 제공합니다. 중앙 웹서비스에서는 이 API를 제공하지 않습니다.

소스 구조: `collector/` 로컬 수집, `platform/` 계정·DB·SSH·현장 파일 반입·작업·취약점·자산/서비스 관리, `model/` 스냅샷, `report/` 점검 보고서·자산 XLSX/Markdown, `src/main/resources/static/` 웹 화면(`assets.js` 자산 그리드/편집, `asset-report.js` MD, `offline.js` 반입), `src/main/resources/db/migration/` Flyway 스키마. 테스트는 `src/test/java/`의 JUnit 기반이며 자산 저장/보존·후보 식별·XLSX는 `AssetInventoryTest`, `ServiceDiscoveryTest`, `AssetWorkbookTest`에서 검증합니다. `src/test/js/*.test.cjs`는 Node 내장 테스트로 포트 요약·PID 표시·시스템 서비스 판별 및 Markdown 표시를 검사하며 별도 npm 의존성이 없습니다. `OfflineInventoryTest`, `OfflineImportTest`, `OfflineApiTest`는 파일 형식/해시/한도·중복/동시 반입·원자성·권한과 보존을 검증합니다.

## 폐쇄망 배포와 백업

1.11.0은 자산 화면의 포트/PID 요약, 넓은 표와 작은 글자, 자산·서비스 XLSX 다운로드와 Observer 로고를 제공합니다. 메뉴명은 상단에만 표시하고 본문의 중복 제목을 제거했습니다. 목록의 **전체 자산 XLSX**, 서버 상세의 **자산 XLSX**로 저장된 전체 항목(보관 포함)을 내려받습니다. 화면 검색/필터와 저장 전 입력은 내보내기에 적용되지 않습니다.

점검·SSH 수집·판정·자산 변경·카탈로그 갱신·다운로드의 결과와 소요 시간을 기본 INFO 로그로 기록합니다. 요청 ID를 비동기 점검까지 연결하고 비밀번호·키·명령 본문·예외 메시지는 제외합니다. `.env`의 `OBSERVER_LOG_LEVEL=DEBUG`로 일반 API 조회와 예외 코드 위치를 추가할 수 있습니다. 앱/DB 로그는 파일당 10 MiB·5개로 회전합니다. 조회 명령과 이벤트는 [운영 로그 안내](docs/operations-logging.md)를 참고하세요.

기존 설치에서는 같은 Compose 프로젝트와 DB 볼륨, 기존 `.env`를 유지해 업데이트합니다. 1.10.0부터 V5 마이그레이션으로 수집 방식·파일 해시와 업로드 동시 처리용 잠금 테이블을 추가합니다. 기존 대상은 SSH로 유지합니다. 1.5.0 이하에서는 V4 자산 관리 테이블도 추가하고 이전 점검 결과를 초기 자산 정보에 연결합니다. 1.10.0/1.10.1 → 1.11.0에는 추가 DB 마이그레이션이 없습니다. 기동 시 기존 최근 점검의 OS 근거로 자산 표시 정보를 보완하며, 수기 정보와 원본 점검 스냅샷은 유지합니다. 자세한 절차는 아래 설치 안내를 따르세요.

**Linux x86_64(amd64), Docker Engine·Compose 설치 완료** 환경용 반입 묶음을 제공합니다. 앱·PostgreSQL 이미지 전체, Maven·RPM 취약점 ZIP, CCE/정책 JSON, 설정·운영 스크립트, SHA-256과 소스 스냅샷을 포함합니다. 반입 서버에는 Java·Maven·Python·OpenSSL을 설치할 필요가 없습니다. 실행 절차는 묶음의 `README.md`를 따르며 원본은 [폐쇄망 설치 안내](deployment/offline/README.md)입니다.

```bash
# 반입 서버: 함께 제공한 두 파일의 이름을 사용
sha256sum -c wook-observer-1.11.0-linux-amd64-YYYYMMDD.tar.gz.sha256
tar -xzf wook-observer-1.11.0-linux-amd64-YYYYMMDD.tar.gz
cd wook-observer-1.11.0-linux-amd64-YYYYMMDD
./install.sh
# 새 .env에서 관리자 비밀번호와 접속 주소·포트 확인
./start.sh
```

`install.sh`는 파일 무결성 확인, `docker load`, 새 자격 증명 생성을 수행합니다. 오프라인 Compose에는 빌드 항목이 없으며 `pull_policy: never`와 `--pull never`로 실행합니다. 로그인 후 **데이터 업데이트**에서 `maven-osv.zip`과 `rpm-*.zip` 파일을 차례로 반영하세요. 파일별 원본 이름을 설정해 즉시 갱신하거나 파일 업로드를 사용할 수 있습니다. 기존 PC의 DB·계정·SSH 키·보고서는 반입 묶음에 넣지 않습니다.

새 묶음을 만드는 **준비 PC에서만** Python 3.11 이상과 Docker가 필요합니다. `v1.11.0`과 일치하는 `wook-observer:1.11.0`, `postgres:18.6-bookworm`의 amd64 이미지 및 `updates/maven-osv.zip`·`updates/rpm-*.zip`·각 manifest를 먼저 준비한 뒤 아래를 실행하세요. 이미지 내보내기에 `docker image save --platform`을 지원하는 Docker가 필요합니다.

```bash
python3 scripts/package-offline.py --output resources/offline/wook-observer-1.11.0-linux-amd64-YYYYMMDD
# 생성 디렉터리의 별도 복사본에서 설치·기동·데이터 반영 검증
# 원본 디렉터리의 VERIFICATION.md에 결과 기록 (.env 생성 금지)
python3 scripts/package-offline.py --output resources/offline/wook-observer-1.11.0-linux-amd64-YYYYMMDD --archive-only
```

최종 `.tar.gz`와 `.tar.gz.sha256`은 `resources/offline/`에 생성되며 Git에 추가하지 않습니다. 동일 버전의 앱 이미지를 다시 포장할 때는 앱 태그를 유지하고 `PACKAGE-MANIFEST.json`에 묶음 개정·생성 시각·이미지/데이터 해시를 기록합니다. 소스 스냅샷만으로 폐쇄망에서 재빌드하려면 별도의 Maven 의존성 반입이 필요합니다.

`docker compose exec -T db pg_dump -U observer -d observer -Fc > observer.dump`로 DB를 백업하고 `.env`의 암호화 키를 별도로 보관하세요. 복구는 중지된 앱과 비어 있는 대상 DB에 `pg_restore`로 진행하고 기동 전 계정·암호화 키를 함께 확인하세요. `docker compose down`은 볼륨을 보존하지만 `down -v`는 DB를 삭제합니다.

## 현재 범위와 프로젝트 지침

중앙 서비스의 언어는 자원 사용량과 운영 조건에 따라 변경할 수 있습니다. 현재는 기존 Spring Boot 구현을 유지하며, 측정 결과와 Python 등으로 전환할 기준은 [런타임 선택 기록](docs/runtime-decision.md)에 정리했습니다.

Windows, Kubernetes/containerd, 지원 배포판 이외 OS 패키지 CVE, 컨테이너별 패키지 DB·이미지 레이어 전체 스캔, 실제 동적 클래스 로딩, systemd/호출 관계는 후속 범위입니다. 원격 JVM의 세부 수집은 권한·네임스페이스·도구에 의존합니다. 오류 0건이 전체 계층의 완전한 분석을 의미하지 않습니다.

규칙·요구사항·검증 이력은 [rules/rules.md](rules/rules.md), [rules/requirements.md](rules/requirements.md), [rules/versions.md](rules/versions.md)에서 관리합니다. 루트 `resources/`는 공유용이며 커밋하지 않습니다. 기존 `AGENTS.md`는 보존합니다. 실제 비밀번호·SSH 키·반입 데이터·보고서도 Git에서 제외합니다.

## 구형 서버 자산 식별과 OS 지원 상태

RHEL·CentOS 7.x의 procps-ng 3.3.10, RPM 4.11, 헤더가 있는 `ss` 출력, `hostname` 없는 최소 환경을 처리합니다. Python 3·Docker·jcmd가 없어도 기본 자산은 반입할 수 있습니다. 최신 `observer-collect.sh`로 다시 수집하세요. 기존 파일은 해시와 완료 여부가 유효하고 `uname -a`에 호스트명 근거가 있으면 누락된 호스트명을 보완합니다. RPM 0건만으로 정상 수집으로 표시하지 않습니다.

CentOS Linux 7은 지원 종료, RHEL 7은 정규 유지보수 종료로 구분합니다. RHEL 7 ELS 계약 여부는 미확인으로 표시하며, 자산 대시보드의 **OS 업그레이드 필요** 버튼으로 모아볼 수 있습니다. OS 수명주기는 취약점 건수와 분리하며 다른 배포판을 지원 중으로 추정하지 않습니다. [OS 지원 정책과 근거](docs/os-lifecycle.md), [직접 수집과 반입 안내](docs/offline-collection.md)를 참고하세요.

서비스 등록/편집의 **서비스 구분**은 미들웨어·애플리케이션·공통 항목 외에 웹 서버·데이터베이스·컨테이너·캐시·메시징·인프라를 지원합니다. 저장한 분류는 재점검 후에도 유지하고 MD/XLSX에 같은 이름으로 표시합니다.
