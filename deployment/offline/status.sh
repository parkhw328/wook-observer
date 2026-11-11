#!/bin/sh
set -eu
. "$(dirname -- "$0")/lib/common.sh"
require_runtime
require_environment
compose ps
