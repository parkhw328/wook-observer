#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
. "$bundle_root/lib/diagnostics.sh"
require_runtime
require_environment
diagnose_startup
