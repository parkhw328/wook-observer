#!/bin/sh
# Sourced after common.sh. All operations below are read-only.
compose_summary() {
  compose config --quiet || return $?
  compose config | awk -f "$bundle_root/lib/compose-summary.awk"
}

show_start_context() {
  observer_summary=$(compose_summary) || return $?
  printf 'Docker 컨텍스트: %s\n' "$(docker context show)"
  printf 'Compose 파일: %s/compose.yaml\n' "$bundle_root"
  [ -z "${DOCKER_HOST:-}" ] || printf 'DOCKER_HOST 환경변수가 설정되어 있습니다. down/up이 같은 Docker 데몬을 사용하는지 확인하세요.\n'
  printf '%s\n' "$observer_summary" | awk -F '\t' '
    $1 == "PROJECT" { printf "Compose 프로젝트: %s\n", $2 }
    $1 == "PORT" { printf "앱 게시 포트 (적용값): %s:%s → %s/%s\n", $2, $3, $4, $5 }
  '
}

socket_command() {
  if command -v timeout >/dev/null 2>&1; then timeout -k 1 5 "$@"; else "$@"; fi
}

diagnose_startup() {
  show_start_context || return $?
  printf '\n선택한 프로젝트의 컨테이너 (중지 상태 포함)\n'
  compose ps --all || true
  printf '%s\n' "$observer_summary" | while IFS="$(printf '\t')" read -r observer_kind observer_bind observer_port observer_target observer_protocol; do
    [ "$observer_kind" = PORT ] || continue
    case "$observer_port" in ''|*[!0-9]*) printf '\n포트 범위 %s는 개별 포트별로 확인하세요.\n' "$observer_port"; continue;; esac
    [ "$observer_port" -gt 0 ] && [ "$observer_port" -le 65535 ] || continue
    printf '\n호스트 포트 %s/%s를 게시한 실행 중 컨테이너\n' "$observer_port" "$observer_protocol"
    observer_owners=$(docker ps --filter "publish=$observer_port/$observer_protocol" --format '{{.Names}} | project={{.Label "com.docker.compose.project"}} | {{.Ports}}') || observer_owners='Docker 조회 실패'
    printf '%s\n' "${observer_owners:-조회된 컨테이너 없음}"
    [ "$observer_protocol" = tcp ] || continue
    printf '\n이 스크립트를 실행한 호스트의 TCP 로컬 포트 %s (LISTEN·ESTABLISHED·TIME_WAIT 등, 최대 40줄)\n' "$observer_port"
    printf '원격 Docker를 사용한다면 이 호스트의 소켓과 Docker 서버의 소켓은 다를 수 있습니다.\n'
    if command -v ss >/dev/null 2>&1; then
      if observer_sockets=$(socket_command ss -antp "sport = :$observer_port" 2>/dev/null); then
        printf '%s\n' "$observer_sockets" | head -n 40
      else printf 'ss 조회 실패 또는 시간 초과. 서버에서 권한·도구 상태를 확인하세요.\n'; fi
    elif command -v netstat >/dev/null 2>&1; then
      if observer_sockets=$(socket_command netstat -antp 2>/dev/null); then
        printf '%s\n' "$observer_sockets" | awk -v port="$observer_port" '$1 ~ /^tcp/ && $4 ~ ":" port "$" { if (++count <= 40) print } END { if (!count) print "조회된 TCP 소켓 없음" }'
      else printf 'netstat 조회 실패 또는 시간 초과. 서버에서 권한·도구 상태를 확인하세요.\n'; fi
    else printf 'ss/netstat가 없어 호스트 소켓을 확인하지 못했습니다.\n'; fi
    if [ -r /proc/sys/net/ipv4/ip_local_port_range ]; then
      awk -v port="$observer_port" '{ printf "호스트 자동 할당 포트 범위: %s ~ %s", $1, $2; if (port >= $1 && port <= $2) printf " (선택한 포트가 범위 안에 있음; 점유 여부와는 별개)"; print "" }' /proc/sys/net/ipv4/ip_local_port_range
    fi
  done
  printf '\n진단은 순간 조회입니다. 컨테이너가 없어도 호스트 프로세스·TCP 연결·Docker 포트 할당 상태가 원인일 수 있습니다.\n'
  printf '다른 프로젝트의 앱이면 기존 .env와 DB를 유지하고 COMPOSE_PROJECT_NAME을 기존 프로젝트명으로 맞추세요.\n'
  printf '원인 확인 전 포트·프로젝트를 임의 변경하거나 컨테이너/DB 볼륨을 삭제하지 마세요. 앱 자체 기동 오류는 ./logs.sh app에서 확인하세요.\n'
}
