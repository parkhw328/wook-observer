#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
. "$bundle_root/lib/diagnostics.sh"
require_runtime
require_environment
verify_images
help=$(docker compose up --help)
for flag in --wait --wait-timeout --pull; do
  printf '%s\n' "$help" | grep -q -- "$flag" || fail "현재 Compose가 $flag 옵션을 지원하지 않습니다. 지원 버전의 Compose 플러그인을 준비하세요."
done
show_start_context
# Only images verified above are allowed. This command never builds or pulls.
if compose up -d --no-build --pull never --wait --wait-timeout 180; then
  :
else
  observer_start_status=$?
  printf '\n앱 시작 실패 (종료 코드 %s). 현재 포트·프로젝트 상태를 확인합니다.\n' "$observer_start_status" >&2
  diagnose_startup || true
  exit "$observer_start_status"
fi
compose ps
printf '\n앱 바인딩 주소: '
compose port app 8080
printf '처음에는 관리자 계정으로 로그인한 뒤 README.md의 취약점 데이터 반입 단계를 진행하세요.\n'
