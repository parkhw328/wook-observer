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
    # RPM 4.11 (EL7) rejects an unknown tag even inside a conditional and may exit 0.
    # Detect the tag before querying, within the same overall time limit.
    timeout -k 2 20 sh -c '
      rpm_module=""
      if rpm --querytags 2>/dev/null | awk '\''$0 == "MODULARITYLABEL" { found=1 } END { exit !found }'\''; then
        rpm_module="%|MODULARITYLABEL?{%{MODULARITYLABEL}}:{}|"
      fi
      exec rpm -qa --qf "RPM\\t%{NAME}\\t%{EPOCH}\\t%{VERSION}\\t%{RELEASE}\\t%{ARCH}\\t%{SOURCERPM}\\t${rpm_module}\\t%{VENDOR}\\n"
    ' 2>/dev/null
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
      else if (count == 0 && status == "RPM_STATUS\tCOLLECTED") print "RPM_STATUS\tFAILED";
      else print status;
    }'
fi

# Read only repository identifiers; never emit URLs, credentials, or run dnf/plugins.
(
  repo_count=0
  repo_status=COLLECTED
  for repo_file in /etc/yum.repos.d/*.repo; do
    [ -f "$repo_file" ] || continue
    repo_count=$((repo_count + 1))
    if [ "$repo_count" -gt 64 ]; then repo_status=TRUNCATED; break; fi
    if [ ! -r "$repo_file" ]; then repo_status=FAILED; continue; fi
    head -c 65537 "$repo_file" | awk '
      function emit() {
        if (id != "" && enabled == 1) {
          if (id ~ /^[A-Za-z0-9_.:-]+$/ && length(id) <= 200) print "RPM_REPO\t" id;
          else print "RPM_REPO_STATUS\tFAILED";
        }
      }
      { bytes += length($0) + 1; sub(/\r$/, ""); }
      /^[ \t]*\[/ { emit(); id=$0; sub(/^[ \t]*\[/,"",id); sub(/\][ \t]*$/, "",id); enabled=1; next }
      /^[ \t]*enabled[ \t]*=/ {
        value=$0; sub(/^[^=]*=[ \t]*/,"",value); sub(/[ \t]*[;#].*$/, "",value); sub(/[ \t]*$/, "",value);
        if (value !~ /^(0|1)$/) print "RPM_REPO_STATUS\tFAILED";
        enabled=(value == "1");
      }
      END { emit(); if (bytes > 65536) print "RPM_REPO_STATUS\tTRUNCATED"; }
    '
  done
  [ "$repo_count" -gt 0 ] || repo_status=UNAVAILABLE
  for repo_release in /etc/dnf/vars/releasever /etc/yum/vars/releasever; do
    [ -e "$repo_release" ] || continue
    if [ ! -r "$repo_release" ]; then repo_status=FAILED; continue; fi
    head -c 65 "$repo_release" | awk '
      { sub(/\r$/, ""); if ($0 ~ /^[0-9]+(\.[0-9]+)?$/ && length($0) <= 5) print "RPM_RELEASEVER\t" $0;
        else print "RPM_REPO_STATUS\tFAILED"; }
    '
  done
  printf 'RPM_REPO_STATUS\t%s\n' "$repo_status"
) | head -c 65536

# Listening TCP and all bound UDP sockets in this network namespace. No port scans.
if ! command -v ss >/dev/null 2>&1 || ! command -v timeout >/dev/null 2>&1; then
  printf 'SOCKET_STATUS\tUNAVAILABLE\n'
else
  (
    for socket_protocol in tcp udp; do
      (
        if [ "$socket_protocol" = tcp ]; then
          timeout -k 2 5 ss -lntp 2>/dev/null
        else
          timeout -k 2 5 ss -naup 2>/dev/null
        fi
        socket_rc=$?
        printf '\nSOCKET_QUERY_STATUS\t%s\t%s\n' "$socket_protocol" "$socket_rc"
      ) | awk -v protocol="$socket_protocol" '
        /^SOCKET_QUERY_STATUS\t/ { print; next }
        # Older iproute2 has no -H option. Skip only the known column header.
        $1 == "State" || $1 == "Netid" { next }
        NF { printf "SOCKET\t%s\t%s\n", protocol, $0 }
      '
    done
  ) | head -c 524288 | awk '
    /^SOCKET\t/ { count++; if (count <= 4096) print; else limited=1; next }
    /^SOCKET_QUERY_STATUS\t/ { print; next }
    END { if (limited) print "SOCKET_STATUS\tTRUNCATED"; }
  '
fi
