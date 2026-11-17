#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
. "$bundle_root/lib/diagnostics.sh"
require_runtime
require_environment
show_start_context
compose stop
printf '컨테이너를 중지했습니다. DB 볼륨과 .env는 보존됩니다. ./start.sh로 다시 시작하세요.\n'
