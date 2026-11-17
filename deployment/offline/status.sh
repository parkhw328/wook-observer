#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
. "$bundle_root/lib/diagnostics.sh"
require_runtime
require_environment
show_start_context
compose ps
