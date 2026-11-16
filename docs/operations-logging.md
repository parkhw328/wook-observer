# 운영 로그

1.8.0부터 기본 `INFO` 수준에서 운영 작업의 시작·결과·처리 시간과 실패 단계를 기록한다. 로그는 앱의 표준 출력으로 내보내며 Docker Compose가 보관한다. 별도 로그 서버나 외부 연결은 필요하지 않다.

## 조회와 추적

소스 설치는 `compose.yaml`이 있는 폴더에서 실행한다.

```sh
docker compose logs --since 30m --tail 200 --follow app
docker compose logs --since 1h app | rg 'event=scan.failed|event=ssh.exit.failed|event=ssh.timeout'
docker compose logs --since 1h app | rg 'requestId=여기에-요청-ID'
docker compose logs --since 1h app | rg 'jobId=여기에-점검-ID'
```

폐쇄망 설치 묶음은 `./logs.sh app`, DB는 `./logs.sh db`로 최근 200줄과 이후 로그를 본다. `rg`가 없는 서버는 `grep -E` 또는 `grep -F`를 사용한다. 로그에는 시간·레벨·실행 스레드·로거와 다음 필드가 있다.

| 필드 | 의미 |
| --- | --- |
| `event` | 작업/상태를 나타내는 고정 이벤트 이름 |
| `requestId` | 서버가 생성한 HTTP 요청 ID. API 응답의 `X-Request-ID` 헤더와 동일 |
| `actor` | 인증된 사용자. 인증 전 요청은 `anonymous`, 시작/예약 작업은 빈 값 |
| `jobId`, `targetId` | 비동기 점검의 이력 ID와 대상 ID. 요청 ID·수행자와 함께 작업 스레드로 전달 |
| `durationMs`, `queueWaitMs` | 처리 시간·대기 시간(밀리초) |
| `phase`, `reason`, `errorType`, `origin` | 실패 단계, 분류 코드, 예외 종류, 코드 위치 |

`scan.queued` 이후 `scan.started` → `ssh.started` → `ssh.completed` → `ssh.inventory.parsed` → `scan.collected` → `scan.assessment.started` → `scan.assessed` → `scan.completed` 순서로 추적한다. 실패 시 `scan.failed`의 `phase`가 `COLLECTION`, `ASSESSMENT`, `PERSISTENCE` 중 어디에서 중단되었는지 표시한다. 원격 서버의 개별 명령마다 로그를 전송하지는 않으며 수집 결과의 건수·상태와 보고서의 수집 범위/오류를 함께 확인한다.

예상하지 못한 API 오류는 화면에 요청 ID를 포함한 안내를 표시한다. 해당 ID로 `api.failed`와 `http.completed`를 찾는다. 다른 작업에서 같은 작업 스레드를 재사용해도 이전 요청의 ID가 남지 않는다. 일반 조회는 기본 수준에서 기록하지 않으며, 실패한 요청은 항상 남긴다. 응답의 `durationMs`와 다운로드 이벤트는 서버 처리 기준으로, 사용자 PC의 파일 저장 완료를 의미하지 않는다.

## 기본 이벤트

| 이벤트 | 기록 내용 |
| --- | --- |
| `application.ready`, `application.stopping` | 버전·Java·최대 힙·점검 작업자/대기열 크기, 종료 |
| `scan.recovery`, `scan.shutdown` | 재시작으로 실패 처리한 작업 수, 종료 시 실행/대기 수 |
| `scan.queued`, `scan.started`, `scan.completed`, `scan.failed` | 대상·작업 ID, 대기·처리 시간, 조치 수, 실패 단계 |
| `scan.duplicate`, `scan.queue_full` | 중복 점검·대기열 초과 |
| `ssh.started`, `ssh.completed` | 대상 주소·포트·인증/권한 방식, 수집 바이트·종료 코드·시간 |
| `ssh.privilege.confirmed`, `ssh.privilege.failed`, `ssh.timeout`, `ssh.exit.failed`, `ssh.output.failed` | root 확인, 전환 실패·시간/출력 제한·종료 원인 |
| `ssh.inventory.parsed`, `ssh.inventory.partial` | 프로세스/JVM/컨테이너/RPM/소켓 건수, RPM·소켓 상태, 수집 문제 수 |
| `scan.assessed` | 판정 시간·조치 수·사용한 카탈로그 개정 |
| `asset.backfill.completed` | 기존 스냅샷에서 초기 반영한 자산 수·시간 |
| `target.saved`, `asset.profile.saved`, `asset.service.saved` | 대상/서비스 ID·수정자·작업 종류·개정·보관 여부. 자산 변경 성공은 DB 커밋 후 기록 |
| `asset.exported`, `report.downloaded` | 범위/점검 ID·형식·바이트·생성 시간 |
| `catalog.import.started`, `catalog.import.completed`, `catalog.import.failed` | 반입 실행 ID·파일 크기·검증 레코드 수·SHA-256·시간·실패 |
| `catalog.schedule.configured`, `catalog.schedule.triggered`, `catalog.source.loading`, `catalog.source.loaded`, `catalog.source.failed` | 예약 설정·다음 실행·파일/미러 읽기 결과 |
| `auth.login.succeeded`, `auth.login.failed`, `auth.logout`, `auth.session.revoked` | 로그인/로그아웃·실패 분류·계정 변경에 따른 세션 만료 |
| `account.created`, `account.updated` | 계정·권한·활성 여부·비밀번호 변경 여부 |
| `api.rejected`, `api.invalid_input`, `api.failed`, `http.completed`, `http.unhandled` | 입력 거절·오류 종류, 메서드·경로 패턴·HTTP 상태·시간 |

SSH 실패 코드는 `HOST_KEY_REJECTED`, `AUTHENTICATION_FAILED`, `DNS_FAILED`, `CONNECTION_REFUSED`, `CONNECT_TIMEOUT`, `NETWORK_UNREACHABLE`, `KEY_FORMAT`으로 구분한다. 분류할 수 없는 원격 종료/root 실패는 `REMOTE_EXIT_OR_PRIVILEGE_FAILURE`로 남긴다. OpenSSH 오류 출력에서 최대 16 KiB만 메모리에 보관해 분류하고 원문은 로그에 기록하지 않는다. 일부 전환 오류는 원격 터미널 출력에만 있으므로 구체적인 코드가 없을 수 있다.

## 상세 수준과 보관 크기

기존 `.env`에 설정이 없어도 `INFO`가 기본이다. 일반 API 조회·카탈로그 검증 건수와 예외의 원인별 코드 위치가 필요하면 다음 값을 사용한다.

```dotenv
OBSERVER_LOG_LEVEL=DEBUG
```

변경한 뒤 앱 컨테이너를 다시 만든다. 소스 설치는 다음 명령을 사용하고, 폐쇄망 묶음은 `./start.sh`를 실행한다. 기존 DB와 `.env`의 비밀번호·암호화 키는 그대로 유지한다.

```sh
docker compose up -d --no-deps --force-recreate --pull never app
```

조사가 끝나면 `INFO`로 되돌리고 같은 방법으로 적용한다. `DEBUG`에서도 비밀번호·개인 키·암호화 키·세션/CSRF 토큰·요청 본문·쿼리 문자열·서비스 실행 명령/비고·수집 원문·예외 메시지는 앱 운영 로그에 넣지 않는다. 예외는 종류와 코드 위치로 기록한다. 전체 애플리케이션/라이브러리를 `TRACE`로 변경하는 설정은 제공하지 않는다.

앱과 DB의 Docker `json-file` 로그는 파일당 `10m`, 최대 `5`개로 회전한다(컨테이너당 약 50 MiB 기준). 기존 컨테이너에는 새 Compose 설정으로 재생성한 뒤 적용된다. 컨테이너 삭제 시 로그도 제거되므로 장기 보관이 필요하면 삭제 전에 `docker compose logs --no-color app > observer-app.log`로 저장한다. 등록 서버 주소·사용자 ID가 포함되므로 운영 로그 접근 권한은 관리자가 제한한다.
