#!/bin/sh
# Observer 1.10.1: run on the Linux server being inspected.
# Usage: sh observer-collect.sh [output-directory]
# No network transfer, installation, service changes or automatic privilege escalation.
set -eu
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077
if [ "${1:-}" = --help ] || [ "$#" -gt 1 ]; then
  printf 'Usage: sh observer-collect.sh [output-directory]\nRun on the target Linux host. Upload the resulting .observer file in Observer.\n'
  exit 0
fi
[ "$(uname -s)" = Linux ] || { printf 'Linux is required.\n' >&2; exit 2; }
for observer_tool in timeout mktemp sha256sum ps awk head wc date; do
  command -v "$observer_tool" >/dev/null 2>&1 || { printf 'Required tool unavailable: %s\n' "$observer_tool" >&2; exit 2; }
done
observer_output_dir=${1:-.}
[ -d "$observer_output_dir" ] && [ -w "$observer_output_dir" ] || { printf 'Output directory is not writable.\n' >&2; exit 2; }
observer_output_dir=$(cd -- "$observer_output_dir" && pwd)
observer_work=$(mktemp -d "${TMPDIR:-/tmp}/observer-collect.XXXXXXXX")
observer_result=''
observer_success=0
observer_cleanup() {
  rm -rf -- "$observer_work"
  if [ "$observer_success" -ne 1 ] && [ -n "$observer_result" ]; then rm -f -- "$observer_result"; fi
}
trap observer_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP
observer_time=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
observer_result=$(mktemp "$observer_output_dir/observer-$(date -u '+%Y%m%dT%H%M%SZ')-XXXXXXXX.observer")
cat > "$observer_work/inventory.sh" <<'OBSERVER_FIXED_INVENTORY_END'
# OBSERVER_INVENTORY
OBSERVER_FIXED_INVENTORY_END
printf 'Collecting local inventory (up to 180 seconds)...\n' >&2
if ! (ulimit -f 16384; timeout -k 5 180 sh "$observer_work/inventory.sh" > "$observer_work/payload" 2>/dev/null); then
  printf 'Collection failed or exceeded the time/size limit. No upload file was kept.\n' >&2
  exit 2
fi
observer_size=$(wc -c < "$observer_work/payload")
if [ "$observer_size" -gt 16776000 ] || [ "$(tail -n 1 "$observer_work/payload")" != DONE ]; then
  printf 'Incomplete inventory or output larger than 16 MiB. No upload file was kept.\n' >&2
  exit 2
fi
observer_hash=$(sha256sum "$observer_work/payload" | awk '{print $1}')
{
  printf 'OBSERVER_OFFLINE\t1\nVERSION\t1.10.1\nCOLLECTED_AT\t%s\nSHA256\t%s\n\n' "$observer_time" "$observer_hash"
  cat "$observer_work/payload"
} > "$observer_result"
observer_success=1
printf 'Collection complete. Upload this file in Observer:\n%s\n' "$observer_result"
