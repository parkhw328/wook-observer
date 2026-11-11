#!/bin/sh
# Shared by local and SSH collection. Fixed queries only; never source release files.
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
system_value() {
  system_key=$1
  shift
  if ! command -v base64 >/dev/null 2>&1; then
    printf 'OSINFO\t%s\tUNAVAILABLE\t\n' "$system_key"
    return
  fi
  # Only uname or a bounded head call is allowed here. Shell strips final newlines.
  system_output=$("$@" 2>/dev/null)
  system_rc=$?
  if [ "$system_rc" -eq 0 ] && [ -n "$system_output" ]; then
    system_encoded=$(printf '%s' "$system_output" | head -c 16385 | base64 | tr -d '\n')
    printf 'OSINFO\t%s\tCOLLECTED\t%s\n' "$system_key" "$system_encoded"
  else
    printf 'OSINFO\t%s\tFAILED\t\n' "$system_key"
  fi
}
system_file() {
  if [ ! -e "$2" ]; then
    printf 'OSINFO\t%s\tUNAVAILABLE\t\n' "$1"
  elif [ ! -r "$2" ]; then
    printf 'OSINFO\t%s\tFAILED\t\n' "$1"
  else
    system_value "$1" head -c 16385 "$2"
  fi
}
system_value uname_all uname -a
system_value kernel_release uname -r
system_value kernel_version uname -v
system_value architecture uname -m
system_file os_release /etc/os-release
system_file redhat_release /etc/redhat-release
system_file proc_version /proc/version

if ! command -v rpm >/dev/null 2>&1; then
  printf 'RPM_STATUS\tUNAVAILABLE\n'
elif ! command -v timeout >/dev/null 2>&1; then
  # Do not launch an unbounded RPM query when timeout is absent.
  printf 'RPM_STATUS\tNO_TIMEOUT\n'
else
  (
    timeout -k 2 20 rpm -qa --qf 'RPM\t%{NAME}\t%{EPOCH}\t%{VERSION}\t%{RELEASE}\t%{ARCH}\n' 2>/dev/null
    system_rc=$?
    case "$system_rc" in
      0) printf '\nRPM_STATUS\tCOLLECTED\n';;
      124|137) printf '\nRPM_STATUS\tTIMEOUT\n';;
      *) printf '\nRPM_STATUS\tFAILED\n';;
    esac
  ) | head -c 4194304 | awk '
    /^RPM\t/ { count++; if (count <= 20000) print; else limited=1; next }
    /^RPM_STATUS\t/ { status=$0 }
    END {
      if (limited || status == "") print "RPM_STATUS\tTRUNCATED";
      else print status;
    }'
fi
