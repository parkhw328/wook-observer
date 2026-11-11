# Wook Observer

서버의 **실행 프로세스 → JVM·라이브러리 → Docker 컨테이너**를 조사하고 서비스 직군별 명세와 취약점 대조 결과를 제공하는 내부망 웹서비스입니다.

현재 버전은 **1.0.0**입니다. 중앙 서비스는 Spring Boot 4.1.2 / Java 21.0.13 이상 / PostgreSQL 18로 구성하고 Docker Compose로 실행합니다. 대상 Linux 서버는 SSH로 연결하므로 **Java를 새로 설치할 필요가 없습니다.**

## Docker로 시작하기

Docker Engine, Docker Compose, `openssl`이 필요합니다. 최초 이미지 빌드는 인터넷이 가능한 환경에서 수행합니다.

```bash
./scripts/init-env.sh
# 생성된 .env의 OBSERVER_ADMIN_PASSWORD를 로컬에서 확인하세요.
docker compose up -d --build
docker compose ps
```

브라우저에서 **http://localhost:18080**에 접속하고 `admin` / `.env`의 초기 비밀번호로 로그인하세요. 초기 계정은 DB가 비어 있을 때만 생성됩니다. 이후 비밀번호는 사용자 관리에서 변경합니다. `.env`를 바꿔도 기존 계정의 비밀번호는 초기화되지 않습니다.

- `DATABASE_PASSWORD`: PostgreSQL 접속 비밀번호.
- `OBSERVER_ENCRYPTION_KEY`: SSH 개인 키의 AES-256-GCM 암호화 키. **DB 백업과 별도로 보관**하고 임의로 교체하지 마세요.
- `OBSERVER_BIND_ADDRESS`, `OBSERVER_PORT`: 기본 `127.0.0.1:18080`. 다른 PC에 제공하려면 내부 HTTPS 프록시를 구성하세요. HTTPS 사용 시 `COOKIE_SECURE=true`를 지정합니다.
- `OBSERVER_MIRROR_HOSTS`: 정기 업데이트를 허용할 내부 HTTPS 미러 호스트. 쉼표로 구분하며 기본값은 비어 있습니다.

앱은 비루트 계정·읽기 전용 파일시스템으로 실행하며 DB 포트와 호스트 Docker 소켓을 공개하지 않습니다. 데이터는 Compose의 `database` 볼륨에 보관합니다. **앱은 단일 인스턴스로 운영**하세요. 작업 큐와 재시작 복구는 단일 인스턴스를 기준으로 합니다.

## 점검 대상 등록과 실행

1. 관리자 → **점검 대상 → 대상 등록**에서 이름, SSH 호스트·포트·계정을 입력합니다.
2. 암호 없는 전용 SSH 개인 키와, 서버 관리자가 확인한 **호스트 공개 키**의 `유형 base64` 두 필드를 입력합니다. 예: `/etc/ssh/ssh_host_ed25519_key.pub`. 키 자동 신뢰와 비밀번호 SSH 로그인은 지원하지 않습니다.
3. **점검 시작**을 누릅니다. 사용자 계정도 등록된 모든 대상의 점검과 결과 조회가 가능합니다.
4. **점검 이력 → 상세 보기**에서 수집 제한, 취약점 판정, XLSX·ZIP·JSON·요약 MD를 확인합니다.

호스트 주소는 중앙 앱 컨테이너에서 접속할 수 있어야 합니다. 대상에 `127.0.0.1`을 입력하면 앱 컨테이너 자신을 가리키므로, 같은 PC를 점검할 때도 해당 PC의 접근 가능한 SSH 주소를 입력하세요.

| 수집 영역 | 대상 서버 조건 / 한계 |
| --- | --- |
| OS·프로세스 | Linux, SSH, POSIX 셸, `ps` 및 기본 명령. Java 불필요. 접근 가능한 PID 범위만 조사 |
| Docker | Docker CLI, `timeout`, 로컬 소켓 조회 권한. 컨테이너 정보와 `docker top` PID 연결 |
| JVM 속성 | 실행 중인 Java 서비스, 사용할 수 있는 `jcmd`, `timeout`, attach 권한 |
| Java 라이브러리 | Python 3와 `/proc`·JAR 읽기 권한. 실행 JAR, 클래스패스, 열린 JAR, Spring Boot/WAR 중첩 JAR의 메타데이터 |
| 설정 점검 | 수집된 `sysctl`, `sshd -T` 전역 값, Docker 특권·읽기 전용 루트·사용자 설정 |

Python 3나 `jcmd`가 없어도 기본 점검은 수행하고 상세 미수집 사유를 남깁니다. SSH 수집은 고정된 조회 스크립트를 표준 입력으로 전달하며 대상에 파일이나 Java를 설치하지 않습니다. `sudo`, 임의 명령, 컨테이너 변경, 힙 덤프·강제 GC는 실행하지 않습니다. `jcmd`는 선별 속성 조회를 위한 attach만 사용합니다.

동시 점검은 2개, 대기열은 16개, SSH 작업은 최대 180초입니다. 프로세스 4,096개, 컨테이너 256개, JVM 64개, JVM별 라이브러리 512개 등 제한을 적용합니다. 컨테이너 안의 SSH 서버에서는 호스트 PID와 컨테이너 PID를 임의 연결하지 않습니다.

## 취약점 데이터와 관리자

- **사용자 관리**: 사용자 추가, ADMIN/USER 권한 변경, 비밀번호 재설정, 비활성화. 변경된 계정의 기존 세션은 다음 요청에서 만료됩니다.
- **취약점 데이터**: DB에 저장한 CVE/OSV, CCE, 내부 설정 규칙 검색.
- **데이터 업데이트**: OSV JSON/ZIP 또는 자체 묶음 파일 업로드, 반입 폴더/내부 미러 원본 설정, 즉시 갱신, 6필드 cron 예약, 결과 이력.
- 기본 목록은 **CVE 2건·RHEL CCE 2건·Docker 정책 1건**입니다. 전체 취약점 DB가 아니며 운영 범위의 최신 데이터를 별도로 반입해야 합니다.
- 정확한 Maven 좌표와 버전을 OSV 영향 범위와 비교합니다. CCE는 식별자 목록만으로 판정하지 않고 **적용 OS·실제 설정·기대값**이 있는 규칙만 점검합니다. 내부 Docker 규칙은 CCE와 구분합니다.
- 판정은 `발견`, `의심`, `미발견(점검 범위 내)`, `점검 불가`로 구분합니다. 버전 일치는 악용 가능성을 확정하지 않습니다. 기준이나 메타데이터가 없으면 정상으로 처리하지 않습니다.

반입 형식·출처·갱신 정책은 [docs/catalog.md](docs/catalog.md)를 확인하세요. 새 데이터를 반입한 뒤 **새 점검을 시작**하면 적용됩니다. 과거 보고서는 당시 데이터 버전과 결과를 보존합니다.

인터넷이 가능한 준비 PC에서 `python3 scripts/prepare-maven-catalog.py`를 실행하면 공식 OSV Maven 전체 덤프를 `updates/maven-osv.zip`에 받고 크기·ZIP CRC·SHA-256을 확인합니다. 이 ZIP을 폐쇄망으로 반입해 관리자 화면에서 업로드하거나 업데이트 원본을 `maven-osv.zip`으로 저장한 뒤 갱신하세요. 다운로드 스크립트는 서비스 실행 중 자동 호출되지 않습니다.

## 보고서

동일 스냅샷을 DB에 저장하며 다시 다운로드해도 서버를 재점검하지 않습니다.

- `summary.md`, `report.json`, `report.xlsx`와 `services/<직군>/{processes,open-source,containers}.md`.
- 직군: 애플리케이션·웹·데이터베이스·캐시·메시징·인프라·기타. 분류는 이름 기반 휴리스틱과 설정 보정입니다.
- 웹 점검 XLSX는 **31개 시트**: 기존 명세·원문 30개와 취약점 상세. 각 직군 명세에 `취약점발견` 컬럼, 요약에 카탈로그 버전·반영 시각·판정 범위를 포함합니다.
- ZIP은 XLSX와 MD·JSON을 포함한 **24개 파일**입니다. 긴 원문은 분할해 원문 시트에 보존합니다. 수집 문자열은 수식으로 실행하지 않습니다.

## 개발 및 기존 일회성 JAR

**JDK 21 계열 21.0.13 이상**이 필요합니다. Maven Wrapper를 사용합니다.

```bash
./mvnw verify
# DB 없이 로컬 서버를 한 번 점검하고 종료
java -jar target/wook-observer-1.0.0.jar --observer.report-dir=./reports/check-001
```

일회성 CLI는 기존 로컬 수집기를 유지하며 취약점 DB 대조를 수행하지 않습니다(`미실시`). CLI XLSX는 기존 30개 시트입니다. 출력 디렉터리는 새 경로여야 합니다.

기존 로컬 HTTP API가 꼭 필요한 경우 `--spring.profiles.active=collector`로 실행합니다. 이 모드는 인증 없이 **loopback 전용**으로 운영하며 `POST /api/v1/observations`, `/api/v1/reports`, `/api/v1/reports.xlsx`를 제공합니다. 중앙 웹서비스에서는 이 API를 제공하지 않습니다.

소스 구조: `collector/` 로컬 수집, `platform/` 계정·DB·SSH·작업·취약점, `model/` 스냅샷, `report/` 보고서, `src/main/resources/static/` 웹 화면, `db/migration/` Flyway 스키마. 테스트는 `src/test/java/`의 JUnit 기반입니다.

## 폐쇄망 배포와 백업

외부 빌드 환경에서 이미지를 준비해 반입합니다. 반입 서버에서는 Maven·npm·외부 API 호출 없이 기동합니다.

```bash
# 외부 빌드 환경
docker compose build
docker pull postgres:18.6-bookworm
docker save wook-observer:1.0.0 postgres:18.6-bookworm -o wook-observer-images.tar
# 내부 서버: compose.yaml, scripts/init-env.sh, updates/와 이미지 파일 반입
docker load -i wook-observer-images.tar
./scripts/init-env.sh
docker compose up -d --no-build --pull never
```

`docker compose exec -T db pg_dump -U observer -d observer -Fc > observer.dump`로 DB를 백업하고 `.env`의 암호화 키를 별도로 보관하세요. 복구는 중지된 앱과 비어 있는 대상 DB에 `pg_restore`로 진행하고 기동 전 계정·암호화 키를 함께 확인하세요. `docker compose down`은 볼륨을 보존하지만 `down -v`는 DB를 삭제합니다.

## 현재 범위와 프로젝트 지침

Windows, Kubernetes/containerd, 비 Java OS 패키지 CVE, 이미지 레이어 전체 스캔, 실제 동적 클래스 로딩, systemd/리스닝 포트/호출 관계는 후속 범위입니다. 원격 JVM의 세부 수집은 권한·네임스페이스·도구에 의존합니다. 오류 0건이 전체 계층의 완전한 분석을 의미하지 않습니다.

규칙·요구사항·검증 이력은 [rules/rules.md](rules/rules.md), [rules/requirements.md](rules/requirements.md), [rules/versions.md](rules/versions.md)에서 관리합니다. 루트 `resources/`는 공유용이며 커밋하지 않습니다. 기존 `AGENTS.md`는 보존합니다. 실제 비밀번호·SSH 키·반입 데이터·보고서도 Git에서 제외합니다.
