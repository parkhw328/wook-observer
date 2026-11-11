# Wook Observer

서버의 **호스트 → 프로세스 → JVM·라이브러리**, **Docker → 컨테이너 → 내부 서비스**를 조사하고 서비스 직군별 명세 문서로 만드는 Spring Boot 도구입니다.

현재 버전: **0.1.0**. Linux에서 실행 가능한 범위의 메타데이터를 수집합니다. 확인하지 못한 정보와 미지원 계층은 보고서에 표시합니다.

## 프로젝트 관리

- [규칙](rules/rules.md): 개발·분석·문서화·버전 정책
- [요구사항](rules/requirements.md): 확정 요구사항과 단계별 범위
- [버전 이력](rules/versions.md): 구현 및 검증 기록
- GitHub: <https://github.com/parkhw328/wook-observer>

루트 `resources/`는 공유 폴더이며 커밋하지 않습니다. Spring 설정을 담는 `src/main/resources/`는 Git으로 관리합니다.

## 빌드 및 실행

필수 환경은 **JDK 21.0.13 이상인 Java 21 계열**입니다. Spring Boot 4.1.2와 Maven Wrapper를 사용하며 Maven을 별도로 설치할 필요는 없습니다. 최초 빌드에는 의존성 다운로드를 위한 인터넷 연결이 필요합니다.

```bash
java -version
./mvnw verify
java -jar target/wook-observer-0.1.0.jar
```

빌드와 JAR 기동 시 Java 버전을 검사합니다. 이 작업공간에서 받은 검증용 JDK를 사용하려면 다음과 같이 지정할 수 있습니다. `.tools/`는 Git에서 제외됩니다.

```bash
export JAVA_HOME="$PWD/.tools/jdk-21.0.13+9"
export PATH="$JAVA_HOME/bin:$PATH"
```

기본 HTTP 주소는 `127.0.0.1:8080`입니다. 분석은 요청 시 실행하며 시작만으로 서버를 조사하지 않습니다.

```bash
curl --fail http://127.0.0.1:8080/actuator/health

# JSON 스냅샷
curl --fail -X POST http://127.0.0.1:8080/api/v1/observations

# 직군별 명세 ZIP 다운로드
mkdir -p reports
curl --fail -X POST http://127.0.0.1:8080/api/v1/reports \
  --output reports/latest.zip
```

각 POST는 새 스냅샷을 수집합니다. 동시에 두 분석을 요청하면 뒤 요청은 HTTP 409로 응답합니다. 분석은 동기식이며 대상 수와 조회 실패 시간에 따라 소요 시간이 늘어납니다. 인증 기능은 아직 없으므로 기본 루프백 바인딩으로 사용합니다.

## 일회성 보고서 생성

```bash
java -jar target/wook-observer-0.1.0.jar \
  --observer.report-dir=reports/run-001
```

이 옵션은 HTTP 서버를 띄우지 않고 분석 후 종료합니다. 이미 존재하는 출력 디렉터리는 덮어쓰지 않으므로 실행마다 새 경로를 사용합니다.

```text
reports/run-001/
├── summary.md
├── report.json
└── services/
    ├── application/
    │   ├── processes.md
    │   ├── open-source.md
    │   └── containers.md
    ├── web/
    ├── database/
    ├── cache/
    ├── messaging/
    ├── infrastructure/
    └── other/
```

일곱 직군마다 동일한 세 명세를 생성합니다. PID, 컨테이너 ID, 라이브러리 경로 및 수집 근거로 명세 사이의 관계를 확인할 수 있습니다. 자동 분류는 실행 파일과 이미지 이름의 휴리스틱입니다.

## 수집 범위와 설정

| 계층 | 0.1.0 수집 내용 |
| --- | --- |
| 호스트 | OS·커널·아키텍처·CPU 수·접근 가능한 메모리 |
| 프로세스 | PID·부모 PID·소유자·시작 시각·누적 CPU·RSS·스레드·실행 파일 |
| Java | `jcmd VM.system_properties`의 허용된 속성, JVM 버전, 실행 JAR·클래스패스 |
| 라이브러리 | 일반/묶음 JAR의 Maven 메타데이터, manifest, Spring Boot/WAR 내부 라이브러리 |
| Docker | 엔진 버전·이미지 태그/ID·상태·포트·네트워크·마운트·호스트 PID |

JVM 조회에는 JDK의 `jcmd`와 대상에 대한 접근 권한이 필요합니다. 컨테이너 내부 Java는 `docker top`의 호스트 PID와 `/proc/<pid>/root`를 연결하여 조사합니다. 대상 컨테이너 안에서 명령을 실행하지 않습니다. Docker CLI와 로컬 Unix 소켓이 없거나 접근할 수 없으면 해당 계층의 제한을 기록합니다.

JDK 최소 버전은 Wook Observer의 빌드·실행 기준입니다. 분석 대상 JVM은 접근 권한과 `jcmd` 호환성 범위에서 조회하며, 실행 파일 이름이 `java` 또는 `java.exe`인 프로세스를 우선 식별합니다.

외부 `application.yml` 예시:

```yaml
observer:
  docker-enabled: true
  docker-socket: /var/run/docker.sock
  jvm-attach-enabled: true
  command-timeout: 3s
  max-processes: 4096
  max-containers: 256
  max-java-processes: 64
  max-jars-per-process: 512
  service-overrides:
    "[container:orders]": APPLICATION
    "[container:kafka]": MESSAGING
    "[pid:1234]": DATABASE
```

```bash
java -jar target/wook-observer-0.1.0.jar \
  --spring.config.additional-location=file:/path/to/application.yml

# 대상 JVM에 attach하지 않고 접근 가능한 파일 메타데이터만 조사
java -jar target/wook-observer-0.1.0.jar \
  --observer.report-dir=reports/passive-001 \
  --observer.jvm-attach-enabled=false
```

사용자 지정 직군은 `APPLICATION`, `WEB`, `DATABASE`, `CACHE`, `MESSAGING`, `INFRASTRUCTURE`, `OTHER`입니다. 컨테이너 이름은 앞의 `/` 없이 입력합니다. PID 설정이 컨테이너 분류보다 우선하며 PID는 재사용될 수 있으므로 고정 서비스에는 컨테이너 이름 설정을 권장합니다.

루트리스 Docker는 해당 사용자의 소켓 경로를 지정합니다. `DOCKER_HOST`와 원격 Docker 컨텍스트는 사용하지 않습니다. 수집기를 컨테이너 안에서 실행하면 호스트 PID 오연결을 피하기 위해 Docker PID 연결을 생략하며 호스트 전체를 관측했다고 표시하지 않습니다.

## 현재 한계

- 라이브러리의 존재와 실제 클래스 로딩은 다릅니다. 동적 클래스 로더, Java agent, 삭제된 JAR는 완전히 탐색하지 않습니다.
- 이름에서 추정한 버전은 명시하며 오픈소스 여부·라이선스·취약점은 아직 판정하지 않습니다. 비 Java 런타임과 OS 패키지 상세 수집은 후속 범위입니다.
- 호스트 리스닝 포트, systemd, 서비스 호출 관계, 원격 서버, Kubernetes/containerd 직접 연동은 후속 범위입니다.
- 권한 부족, attach 차단, 종료된 프로세스, 수집량 제한을 `summary.md`와 JSON의 `issues`에 남깁니다.
- 외부 명령 출력은 1 MiB, 중첩 JAR는 16 MiB, 메타데이터는 64 KiB로 제한합니다. JVM 변경·힙 덤프·강제 GC·에이전트 주입은 수행하지 않습니다.
- 환경변수 값, 전체 명령행, 임의 JVM 속성은 보고서에 포함하지 않습니다. 보고서에는 호스트·프로세스·경로 정보가 있으므로 `reports/`도 Git에서 제외합니다.

## 개발 및 테스트

```bash
./mvnw spring-boot:run  # 로컬 HTTP 서비스
./mvnw test            # JUnit 단위·HTTP 통합 테스트
./mvnw verify          # 버전 검사, 테스트, 실행 JAR 패키징
git diff --check       # 변경 파일 공백 점검
```

Java 코드는 `src/main/java/com/wook/observer/`, 테스트는 같은 패키지 구조의 `src/test/java/`에 둡니다. 테스트 파일은 `*Test.java`를 사용합니다. Docker 데이터와 JAR 메타데이터는 고정 fixture로 검증하며 테스트가 운영 컨테이너를 변경하지 않습니다.

기술 근거: [Spring Boot 요구사항](https://docs.spring.io/spring-boot/system-requirements.html), [JDK 21 jcmd](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jcmd.html), [Docker inspect](https://docs.docker.com/reference/cli/docker/container/inspect/), [Docker top](https://docs.docker.com/reference/cli/docker/container/top/).
