# OS 자산 식별과 지원 종료 정책

## 적용 범위

1.11.0은 **RHEL 7.x·CentOS Linux 7.x**를 자산으로 수집·등록하고 OS 전환 대상을 표시한다. OS 지원 일정은 CVE/CCE 판정과 별개이며 발견·의심 건수에 더하지 않는다. RPM 취약점 대조 범위는 기존 RHEL·Rocky·AlmaLinux 8/9/10을 유지한다. 7.x의 RPM 목록을 수집한다고 RPM CVE 판정을 지원한다는 뜻은 아니다.

현재 등록한 정책은 아래 두 가지다. 다른 배포판·버전이나 식별 근거 부족은 **지원 정책 미확인**이며 지원 중/안전 판정이 아니다. 커널 버전이나 `ID_LIKE`만으로 RHEL·CentOS로 추정하지 않는다.

| 배포판 | 기준일 | 자산 표시 | 별도 지원 조건 |
| --- | --- | --- | --- |
| CentOS Linux 7 | 2024-06-30 | 지원 종료 (EOL) · OS 업그레이드 필요 | 공식 업데이트 종료. 제3자 계약은 판정하지 않음 |
| Red Hat Enterprise Linux 7 | 2024-06-30 | 정규 유지보수 종료 · OS 업그레이드 필요 | ELS는 별도 계약과 RHEL 7.9 조건에 따라 2029-05-31까지 제공. 이 서버의 계약·적용 여부는 미확인 |

정책 확인일은 **2026-11-17**이다. 표시 기준일은 서버의 현재 UTC 날짜다. 종료일 다음 날부터 전환 대상으로 표시하며, RHEL ELS가 모든 패키지·요청에 대한 일반 유지보수 연장을 의미하지 않는다. 개별 지원 계약을 확인하고 지원되는 OS로 전환 계획을 수립해야 한다.

## 식별과 보존

- 수집 성공한 `/etc/os-release`의 명시적인 `ID`·`VERSION_ID`를 우선 사용한다. 릴리스 파일은 데이터로 읽고 실행하지 않는다.
- 버전이 없거나 os-release가 없는 구형 설치는 명확한 `/etc/redhat-release`의 배포판·버전 문자열을 사용한다. 상충하는 ID나 CentOS Stream, 파생 배포판은 위 두 정책으로 평가하지 않는다.
- 기존 자산은 기동 시 최근 완료 점검의 OS 근거를 표시용 요약에 보완한다. 관측 호스트·서비스·수기 정보·revision과 원본 점검 JSON·당시 취약점 판정은 유지한다. 명확한 기존 OS 표시명도 호환해 읽는다.
- 대시보드 대상 수·목록 배지·필터·상세·업로드 미리보기와 MD/XLSX가 같은 정책을 사용한다. 내보내기는 공식 출처·정책 확인일·표시 기준일을 포함한다. 과거 점검 보고서 다운로드를 현재 정책으로 재작성하지 않는다.
- 실행 중인 앱은 외부 지원 일정 API를 호출하지 않는다. 정책을 바꾸려면 검증된 공식 근거와 함께 앱을 업데이트한다.

## 공식 근거

- [CentOS Linux 종료 안내](https://centos.org/centos-linux/): CentOS Linux 7 지원 종료일.
- [CentOS Project 종료 일정 공지](https://blog.centos.org/2023/04/end-dates-are-coming-for-centos-stream-8-and-centos-linux-7/): CentOS Linux 7과 CentOS Stream 8의 서로 다른 일정.
- [Red Hat RHEL 7 지원 종료 단계와 ELS 안내](https://access.redhat.com/articles/7005471): 유지보수 종료, RHEL 7.9·ELS 계약 조건과 기간.
- [Red Hat RHEL 7 유지보수 종료 안내](https://www.redhat.com/en/technologies/linux-platforms/enterprise-linux/rhel-7-end-of-maintenance): 지원 단계와 전환 안내.

수집기 호환성·오류 대응은 [직접 실행 수집과 파일 반입](offline-collection.md)을 참고한다.
