# 앱 시작 시 포트 충돌 확인

`address already in use` 또는 `port is already allocated`는 요청한 주소·포트를 바인딩하지 못했다는 뜻이다. 포트를 43576처럼 높게 바꾸어도 비어 있다는 보장은 없다. `LISTEN`뿐 아니라 로컬 포트를 사용하는 `ESTABLISHED` 등의 TCP 연결이 Docker 게시 포트와 충돌하는 경우도 있다. 실제 서버의 원인은 점유 근거로 구분한다.

## 적용값과 점유 대상

1.12.1부터 `start.sh`는 선택한 Docker 컨텍스트·Compose 파일·프로젝트명·앱 게시 포트를 먼저 출력한다. 시작 실패 시 같은 진단을 자동 실행하고 원래 실패 코드를 반환한다. `docker compose up`을 직접 실행했거나 별도로 확인하려면 반입 디렉터리에서 다음을 실행한다.

```sh
./diagnose.sh
```

이 명령은 설정과 실행 상태를 조회한다. 비밀번호·암호화 키·전체 Compose 설정을 출력하지 않으며 컨테이너/프로세스를 중지하거나 파일·볼륨을 변경하지 않는다. 같은 포트를 게시한 실행 컨테이너의 이름·프로젝트, 실행 호스트의 TCP 상태/PID/프로세스를 최대 40줄 표시한다. `ss` 부재 시 `netstat`를 사용하고 도구·권한·시간 초과는 미확인으로 남긴다. 원격 Docker 컨텍스트에서는 로컬 `ss` 결과가 원격 Docker 서버의 점유 상태가 아니므로 해당 서버에서도 조회한다.

이전 묶음에서도 다음 명령으로 43576 포트를 확인할 수 있다. 실제 오류에 나온 포트로 바꾼다.

```sh
docker context show
docker compose ls -a
docker ps --filter publish=43576/tcp --format '{{.Names}} | project={{.Label "com.docker.compose.project"}} | {{.Ports}}'
sudo ss -antp 'sport = :43576'
```

`ss`가 없다면 `sudo netstat -antp`에서 **로컬 주소** 끝의 `:43576`을 찾는다. 상대 포트 43576은 이 서버의 게시 포트 점유 근거가 아니다. PID가 보이지 않으면 권한 부족 또는 이미 종료된 소켓일 수 있다.

## down 후에도 충돌하는 경우

소스 실행의 기본 프로젝트는 `wook-observer`, 반입본은 `wook-observer-offline`이다. Compose의 `down`은 선택한 프로젝트를 대상으로 하므로 다른 프로젝트의 기존 앱은 남을 수 있다. `.env`와 셸의 `COMPOSE_PROJECT_NAME`, 명령의 `-p`, 실행 디렉터리 및 Docker 컨텍스트를 비교한다.

기존 설치를 업데이트하는 경우에는 기존 컨테이너 라벨 또는 DB 볼륨의 `com.docker.compose.project` 라벨로 실제 프로젝트명을 확인한다. 새 디렉터리에 기존 `.env`를 복사하고 다음 항목을 **기존 프로젝트명**으로 저장한다.

```dotenv
# 기존 설치가 실제로 wook-observer 프로젝트인 경우의 예
COMPOSE_PROJECT_NAME=wook-observer
```

셸에 다른 `COMPOSE_PROJECT_NAME`을 export했거나 `-p`를 사용하면 그 값이 `.env`보다 우선하므로 함께 맞춘다. 앱 게시 포트도 셸의 `OBSERVER_PORT`·`OBSERVER_BIND_ADDRESS`가 `.env`를 덮어쓸 수 있다. 변경 후 `./diagnose.sh`로 **적용값**을 확인하고 `./start.sh`를 실행한다. 프로젝트명을 맞추면 기존 프로젝트의 앱을 교체하고 같은 DB 볼륨을 사용한다. DB 비밀번호·암호화 키는 기존 값을 유지해야 한다.

신규 1.12.1 설치는 프로젝트명을 `.env`에 저장한다. 기존 `.env`는 자동 수정하지 않는다. `stop.sh`는 선택한 프로젝트의 컨테이너만 중지하며 다른 프로젝트나 호스트 프로세스는 중지하지 않는다. `down -v`는 DB 볼륨을 삭제하므로 포트 충돌 해결 명령으로 사용하지 않는다.

## 호스트 TCP 연결이 점유하는 경우

진단에서 컨테이너가 없고 호스트 프로세스의 LISTEN/ESTABLISHED 등이 보이면 해당 프로세스와 로컬 주소·포트가 원인 후보이다. `ip_local_port_range`에 포함된 높은 포트는 외부 연결용 자동 할당에 쓰일 수 있다. 범위 안이라는 이유만으로 현재 점유 중이라고 판정하지는 않는다. 운영 서비스 포트는 실제 점유와 조직의 예약 정책을 확인한 뒤 결정한다. 스크립트는 프로세스 강제 종료나 커널 예약 포트 설정 변경을 하지 않는다.

컨테이너와 소켓 조회가 모두 비어 있으면 수집 시점 차이, 다른 Docker 데몬/네임스페이스, Docker의 남은 포트 할당 상태 등을 확인한다. 오류 원문과 `diagnose.sh` 결과를 함께 비교해야 하며, 이 단계에서 전체 Docker 서비스를 임의로 재시작하지 않는다. 포트 바인딩을 통과한 뒤 앱 상태 검사가 실패한 경우에는 `./logs.sh app`으로 애플리케이션 기동 오류를 확인한다.

## 공식 동작 근거

- [Compose 프로젝트명과 우선순위](https://docs.docker.com/compose/how-tos/project-name/)
- [Compose down의 제거 범위와 볼륨 옵션](https://docs.docker.com/reference/cli/docker/compose/down/)
- [Compose config의 적용 설정 조회](https://docs.docker.com/reference/cli/docker/compose/config/)

2026-11-17 확인. 별도 시험 프로젝트의 포트 충돌 재현 결과이며 사용자의 설치 서버 점유자를 대신 확정하지 않는다.
