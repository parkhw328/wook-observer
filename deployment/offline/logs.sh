#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
require_runtime
require_environment
service=${1:-app}
case "$service" in app|db) ;; *) fail '사용법: ./logs.sh [app|db]';; esac
compose logs --tail 200 --follow "$service"
