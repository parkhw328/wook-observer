#!/bin/sh
set -eu
cd -- "$(dirname -- "$0")"
command -v sha256sum >/dev/null 2>&1 || { printf 'sha256sum이 필요합니다.\n' >&2; exit 1; }
sha256sum -c SHA256SUMS
