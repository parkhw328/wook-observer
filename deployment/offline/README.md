# Wook Observer 1.3.0 폐쇄망 설치 안내

이 묶음은 **Linux x86_64(amd64)** 서버의 신규 설치용입니다. Docker Engine과 `docker compose`가 설치된 서버에서 앱·PostgreSQL·취약점 데이터를 인터넷 없이 사용할 수 있습니다.

## 1. 반입 파일과 실행 조건

아래 두 파일을 같은 폴더로 복사하세요.

- `@@BUNDLE_NAME@@.tar.gz`: 이미지·설정·스크립트·데이터·문서·소스 묶음.
- `@@BUNDLE_NAME@@.tar.gz.sha256`: 묶음 전체의 SHA-256.

호스트에는 Docker Engine, Compose 플러그인, 셸, `tar`, `sha256sum`이 필요합니다. Compose는 `up --wait`, `--wait-timeout`, `--pull` 옵션을 지원해야 합니다. 기본 컨테이너 메모리 상한은 앱 2GiB + DB 1GiB이므로 서버 메모리 4GiB 이상과 여유 디스크 10GB 이상을 권장합니다. 데이터·보고서 증가에 맞춰 공간을 확보하세요.

Java·Maven·npm·Python·OpenSSL을 호스트에 추가 설치할 필요는 없습니다. Java 21.0.13+9, SSH 클라이언트, 보고서 생성 의존성은 앱 이미지에 포함되고 초기 암호 생성도 반입한 이미지에서 수행합니다.

## 2. 무결성 확인과 이미지 등록

```bash
sha256sum -c @@BUNDLE_NAME@@.tar.gz.sha256
tar -xzf @@BUNDLE_NAME@@.tar.gz
cd @@BUNDLE_NAME@@
./install.sh
```

`install.sh`는 내부 파일의 SHA-256을 확인하고 이미지 두 개를 `docker load`로 등록한 뒤 새 `.env`를 권한 600으로 생성합니다. 기존 `.env`가 있으면 유지합니다. 앱/DB를 아직 시작하지 않으므로 먼저 접속 주소·포트를 설정할 수 있습니다. 설치 시 레지스트리에 접속하거나 이미지를 빌드하지 않습니다.

## 3. 설정과 시작

`.env`를 편집해 값을 확인하세요. 파일을 셸의 `source` 명령으로 실행할 필요는 없습니다.

| 항목 | 기본값 / 용도 |
| --- | --- |
| `OBSERVER_ADMIN_USER` | `admin` |
| `OBSERVER_ADMIN_PASSWORD` | 설치 시 새로 생성한 초기 관리자 비밀번호 |
| `OBSERVER_ENCRYPTION_KEY` | 설치 시 생성한 SSH 키·비밀번호 암호화 키. DB와 함께 보존하고 별도 백업 |
| `DATABASE_PASSWORD` | 설치 시 새로 생성한 DB 비밀번호 |
| `OBSERVER_BIND_ADDRESS` | `127.0.0.1`. 서버의 브라우저에서만 접근하는 기본 설정 |
| `OBSERVER_PORT` | `18080` |
| `OBSERVER_MIRROR_HOSTS` | 비어 있음. 내부 HTTPS 미러를 허용할 때 호스트명을 쉼표로 구분 |
| `COOKIE_SECURE` | `false`. 내부 HTTPS 프록시를 사용하는 경우 `true` |

다른 PC에서 접속하려면 서버의 내부 IP 또는 `0.0.0.0`으로 바인딩 주소를 바꾸고 해당 내부 포트의 접근을 허용하세요. 운영 계정과 SSH 키를 입력하는 접속 경로에는 내부 HTTPS 프록시 구성을 권장합니다. 프록시와 인증서는 해당 조직의 기존 구성을 사용하세요.

```bash
./start.sh
./status.sh
```

기본 접속 주소는 **서비스 실행 서버의 http://127.0.0.1:18080**입니다. 다른 PC에서는 설정한 서버 내부 IP를 사용합니다. `admin`과 `.env`의 초기 비밀번호로 로그인하세요. 계정이 생성된 뒤에는 사용자 관리에서 비밀번호를 변경합니다. `.env`의 초기 비밀번호를 바꾸어도 기존 DB 계정 비밀번호가 변경되지는 않습니다.

웹 계정 비밀번호는 **8자 이상, UTF-8 72바이트 이하**입니다. 새 설치에서는 최초 `./start.sh` 실행 전에 `.env`의 `OBSERVER_ADMIN_PASSWORD`를 수정할 수 있습니다. 기존 설치는 로그인 후 **사용자 관리 → 계정 편집 → 새 비밀번호**에서 변경하세요. 빈칸은 기존 비밀번호 유지입니다.

기본 Compose 프로젝트명은 `wook-observer-offline`입니다. 이 프로젝트의 DB는 Docker named volume에 저장됩니다. 이미 실행한 프로젝트명을 변경하면 다른 볼륨을 사용할 수 있으므로 같은 설치에서는 유지하세요.

### 기존 1.0.0 / 1.1.0 / 1.2.x에서 업데이트

1. 기존 설치 디렉터리에서 DB와 `.env`를 아래 백업 절차로 보관합니다. 진행 중인 점검이 끝난 뒤 업데이트하세요.
2. 새 묶음을 별도 디렉터리에 풀고 **기존 `.env`를 새 디렉터리에 복사**합니다. 기존 포트·DB 비밀번호·암호화 키를 그대로 사용해야 합니다.
3. 기존 Compose 프로젝트명이 `wook-observer-offline`과 다르면 `export COMPOSE_PROJECT_NAME=기존프로젝트명`으로 맞춥니다. 기존 폴더에서 `docker compose ls`로 확인할 수 있습니다.
4. 새 디렉터리의 `./install.sh`, `./start.sh`를 실행합니다. 기존 `.env`는 덮어쓰지 않고 같은 프로젝트의 DB 볼륨을 사용합니다. 1.0.0에서 오면 V3 DB 마이그레이션으로 기존 대상을 SSH 키·접속 계정 권한으로 보존하며, 1.1.0/1.2.x에서 오면 스키마 변경 없이 유지합니다.
5. 기존 관리자 비밀번호로 로그인해 대상·이력·카탈로그를 확인하고 새 점검으로 OS·RPM 정보를 수집합니다. 기존 사용자 비밀번호는 초기화되지 않습니다.

DB 볼륨을 삭제하거나 암호화 키를 새로 생성하지 마세요. 이전 앱으로 되돌려야 할 경우 단순 이미지 교체 대신 업데이트 전 DB 백업과 같은 `.env`를 함께 복구합니다.

## 4. 동봉한 취약점 데이터 반영

이미지의 최초 DB에는 CVE 2건, RHEL CCE 2건, 내부 Docker 규칙 1건의 시작용 데이터가 생성됩니다. 동봉한 **OSV Maven 권고 @@MAVEN_RECORDS@@건**을 다음 순서로 반영하세요.

1. 관리자 **데이터 업데이트 → 정기 업데이트 설정**에서 원본을 `maven-osv.zip`으로 입력합니다.
2. **설정 저장 → 저장된 원본 지금 갱신**을 누릅니다. 정기 실행은 필요한 경우에만 켜세요.
3. **취약점 데이터**에서 CVE/OSV @@MAVEN_RECORDS@@건, CCE 2건, 내부 정책 1건을 확인합니다.

`updates/maven-osv.zip`과 출처·해시 기록 `updates/maven-osv.manifest.json`이 포함되어 있습니다. 데이터 원본의 수정 시각은 `@@MAVEN_MODIFIED@@`입니다. `updates/catalog.json`은 이미지에 포함된 시작용 CVE/CCE/POLICY 목록입니다.

파일 업로드로 ZIP을 반영해도 됩니다. 이후에는 새 파일을 별도로 반입해 교체하거나 내부 HTTPS 미러를 구성하세요. 예약 실행은 내부 파일/미러를 주기적으로 읽으며, 인터넷에서 최신 데이터를 자동으로 가져오지 않습니다. 수동 갱신 시 실패하면 기존 카탈로그를 유지합니다.

자동 CVE 대조는 Maven 식별자·버전 기준이며 CCE는 등록된 OS·설정 규칙 범위입니다. 판정 범위·형식·데이터 라이선스는 [docs/catalog.md](docs/catalog.md)를 확인하세요.

## 5. 대상 등록과 점검

관리자 **점검 대상**에서 Linux SSH 호스트·계정과 비밀번호를 입력합니다. **연결 정보 저장**을 누르면 서버 지문을 자동 조회하며, 확인 체크와 **지문 확인 후 저장**으로 등록합니다. 공개 키를 직접 입력할 필요가 없으며 SSH 개인 키 인증이나 공개 키 수동 입력도 선택할 수 있습니다. 일반 계정으로 root 점검이 필요하면 root 비밀번호 su 또는 sudo 방식을 선택하세요. 대상 서버에 Java를 새로 설치할 필요가 없습니다. JVM·JAR 상세 수집은 대상의 도구와 권한에 따라 제한을 기록합니다.

앱 컨테이너가 대상 서버의 SSH 포트로 접속할 수 있어야 합니다. `127.0.0.1`을 대상 호스트로 입력하면 앱 컨테이너 자신을 가리킵니다. 웹서비스를 설치한 PC를 점검할 때도 실제 SSH 주소를 사용하세요.

root 전환 실패는 점검 실패로 기록하고 일반 권한으로 대체하지 않습니다. 상세 조건은 [docs/ssh-access.md](docs/ssh-access.md)를 확인하세요. 한글·영문 폰트도 이미지에 포함되어 외부 연결이 필요 없습니다.

주황색 테마·Observer 로고·SVG 메뉴 아이콘·일반 고딕체를 적용했습니다. [화면 안내](docs/interface.md)에서 글꼴과 연결 등록 흐름을 확인할 수 있습니다.

점검 이력에서 OS·커널 정보와 RPM 설치 목록을 확인하고 XLSX·ZIP·JSON·요약 MD를 다운로드합니다. RPM 조회는 20초·20,000건·4 MiB로 제한하며 도구 없음·권한/DB 오류·일부 수집을 구분합니다. 이전 보고서는 새 정보를 채우기 위해 다시 점검해야 합니다. RPM CVE 대조와 컨테이너별 RPM DB는 미지원입니다. 상세 기능과 수집 한계는 [docs/application-readme.md](docs/application-readme.md)에 있습니다.

## 6. 운영과 백업

```bash
./status.sh
./logs.sh app        # 앱 로그; Ctrl+C로 로그 보기 종료
./logs.sh db         # DB 로그
./stop.sh           # 중지, DB 볼륨 유지
./start.sh          # 다시 시작
```

백업할 때는 DB와 `.env`의 암호화 키를 함께 보존하세요. 아래 명령은 현재 설치 디렉터리에서 실행합니다.

```bash
umask 077
mkdir -p backups
backup_stamp=$(date +%Y%m%d-%H%M%S)
docker compose exec -T db pg_dump -U observer -d observer -Fc > "backups/observer-$backup_stamp.dump"
cp .env "backups/environment-$backup_stamp.env"
```

백업은 SSH 자격 증명과 계정 정보를 포함하므로 운영자만 접근할 수 있는 별도 저장소로 옮기세요. 복구 시 앱을 중지하고 비어 있는 새 DB에 `pg_restore --exit-on-error --no-owner --no-acl`을 사용한 뒤 같은 암호화 키로 앱을 시작합니다. `docker compose down -v`는 DB 볼륨을 삭제하므로 일반 중지에는 제공한 `stop.sh`를 사용하세요.

## 7. 묶음 구성과 확인 사항

| 경로 | 내용 |
| --- | --- |
| `images/wook-observer-1.3.0.tar.gz` | 실행 JAR·Java·SSH 클라이언트·보고서 라이브러리가 포함된 앱 이미지 전체 레이어 |
| `images/postgres-18.6-bookworm.tar.gz` | PostgreSQL 이미지 전체 레이어 |
| `images/images.list` | 이미지 태그, 실행 설정·파일시스템 레이어의 SHA-256, 파일명 |
| `compose.yaml` | 빌드 항목 없이 `pull_policy: never`를 지정한 오프라인 실행 구성 |
| `install.sh`, `start.sh`, `stop.sh`, `status.sh`, `logs.sh`, `verify.sh` | 설치·운영·파일 확인 도구 |
| `updates/` | Maven ZIP·출처 기록·시작용 CVE/CCE/정책 JSON |
| `source/` | `v1.3.0` 애플리케이션 Git 소스 스냅샷. 소스 재빌드에는 별도의 Maven 의존성 반입 필요 |
| `PACKAGE-MANIFEST.json` | 앱 버전·원본 커밋·플랫폼·내보내기 전 이미지 ID·실행 내용 해시·데이터 출처 |
| `SHA256SUMS` | 변경하지 않은 반입 파일 전체의 SHA-256 |
| `VERIFICATION.md` | 묶음으로 수행한 오프라인 기동·데이터 반영 검증 결과 |

기존 PC의 `.env`, DB 볼륨/덤프, 사용자·SSH 접속 정보, 실제 점검 보고서는 포함하지 않습니다. ARM64와 Windows 네이티브용 이미지는 이 묶음의 대상이 아닙니다. 운영용 `updates/` 파일을 나중에 교체하면 원래 `SHA256SUMS`와 달라지는 것이 정상이며 새 파일의 출처와 해시를 별도로 관리하세요.

Docker 저장 방식에 따라 load 후 표시되는 이미지 ID가 달라질 수 있어, 스크립트는 OS·아키텍처·실행 설정·파일시스템 레이어의 SHA-256으로 반입 이미지 내용을 대조합니다.

오류가 나면 `./status.sh`, `./logs.sh app`, `./logs.sh db`를 확인하세요. 포트가 사용 중이면 `.env`의 포트를 변경하고 다시 시작합니다. 이미지 실행 내용 또는 파일 검증이 실패하면 원본 반입 파일과 SHA-256을 대조하세요.
