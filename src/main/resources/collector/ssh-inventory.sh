#!/bin/sh
# Fixed, read-only inventory. No target Java installation or uploaded executable is required. Privilege is selected before this script runs.
export LC_ALL=C
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
if [ "$(uname -s)" != Linux ]; then
  printf 'ERROR\tLinux target required\n'
  exit 2
fi
printf 'HOST\t%s\t%s\t%s\t%s\t%s\n' "$(hostname)" "$(uname -r)" "$(uname -m)" "$(getconf _NPROCESSORS_ONLN)" "$(awk '/MemTotal:/{print $2 * 1024}' /proc/meminfo)"
printf 'ACCESS\t%s\t%s\n' "$(id -u)" "$(id -un)"
printf 'SCOPE\t%s\n' "$(if [ -f /.dockerenv ] || [ -f /run/.containerenv ]; then echo container; else echo host; fi)"
if [ -r /etc/os-release ]; then
  awk -F= '$1=="ID" {gsub(/"/,"",$2); printf "FACT\thost:os.id\t%s\n",$2} $1=="VERSION_ID" {gsub(/"/,"",$2); printf "FACT\thost:os.version\t%s\n",$2}' /etc/os-release
fi
if ! command -v ps >/dev/null 2>&1; then printf 'ERROR\tps unavailable\n'; exit 2; fi
ps -eo pid=,ppid=,user=,rss=,nlwp=,comm= | head -n 4097 | sed 's/^/PROCESS\t/'
if command -v docker >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
  dv=$(timeout 5 docker --host unix:///var/run/docker.sock version --format '{{.Server.Version}}' 2>/dev/null)
  if [ -n "$dv" ]; then
    printf 'DOCKER\t%s\n' "$dv"
    ids=$(timeout 5 docker --host unix:///var/run/docker.sock ps -aq --no-trunc 2>/dev/null)
    if [ "$?" -ne 0 ]; then printf 'ISSUE\tdocker\tContainer listing failed\n'; fi
    ids=$(printf '%s\n' "$ids" | head -n 257)
    count=0
    for id in $ids; do
      count=$((count + 1))
      if [ "$count" -gt 256 ]; then printf 'ISSUE\tdocker\tcontainer limit 256\n'; break; fi
      value=$(timeout 3 docker --host unix:///var/run/docker.sock inspect --format '{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Config.Image}},"imageId":{{json .Image}},"state":{{json .State.Status}},"pid":{{json .State.Pid}},"ports":{{json .NetworkSettings.Ports}},"networks":{{json .NetworkSettings.Networks}},"mounts":{{json .Mounts}},"privileged":{{json .HostConfig.Privileged}},"readOnlyRootfs":{{json .HostConfig.ReadonlyRootfs}},"user":{{json .Config.User}}}' "$id" 2>/dev/null)
      if [ -n "$value" ]; then printf 'CONTAINER\t%s\n' "$value"; else printf 'ISSUE\tdocker\tinspect unavailable\n'; fi
      process_list=$(timeout 3 docker --host unix:///var/run/docker.sock top "$id" -eo pid 2>/dev/null)
      if [ "$?" -ne 0 ]; then
        case "$value" in *'"state":"running"'*) printf 'ISSUE\tdocker\tContainer %s: process listing unavailable\n' "$id";; esac
      fi
      pids=$(printf '%s\n' "$process_list" | awk 'NR>1 && $1 ~ /^[0-9]+$/{print $1}' | head -n 4096 | tr '\n' ',')
      printf 'CPIDS\t%s\t%s\n' "$id" "$pids"
    done
  else printf 'ISSUE\tdocker\tDocker socket unavailable or permission denied\n'; fi
else printf 'ISSUE\tdocker\tDocker CLI or timeout unavailable; containers not assessed\n'; fi
for parameter in kernel.randomize_va_space kernel.kptr_restrict; do
  value=$(sysctl -n "$parameter" 2>/dev/null)
  if [ -n "$value" ]; then printf 'FACT\thost:%s\t%s\n' "$parameter" "$value"; fi
done
if command -v sshd >/dev/null 2>&1; then
  # Effective global configuration only; Match blocks can override per-connection behavior.
  sshd -T 2>/dev/null | awk '$1=="permitrootlogin" || $1=="passwordauthentication" {printf "FACT\thost:ssh.%s\t%s\n",$1,$2}'
fi
java_pids=$(ps -eo pid=,comm= | awk '$2=="java"{print $1}' | head -n 65)
count=0
for pid in $java_pids; do
  count=$((count + 1))
  if [ "$count" -gt 64 ]; then printf 'ISSUE\tjava\tJava process limit 64\n'; break; fi
  printf 'JAVA\t%s\n' "$pid"
  executable=$(readlink "/proc/$pid/exe" 2>/dev/null)
  jcmd="${executable%/*}/jcmd"
  if [ ! -x "$jcmd" ]; then jcmd=$(command -v jcmd); fi
  if [ -n "$jcmd" ] && command -v timeout >/dev/null 2>&1; then
    properties=$(timeout 3 "$jcmd" "$pid" VM.system_properties 2>/dev/null | awk '/^(java.version|java.runtime.version|java.vendor|java.vm.name|java.vm.version|java.home|java.class.path|jdk.module.path|os.name|os.arch|os.version)=/ {print}')
    if [ -n "$properties" ]; then
      printf '%s\n' "$properties" | awk -v pid="$pid" '{printf "JPROP\t%s\t%s\n",pid,$0}'
    else printf 'ISSUE\tjava\tPID %s: JVM attach unavailable or permission denied\n' "$pid"; fi
  else printf 'ISSUE\tjava\tPID %s: jcmd or timeout unavailable; JVM properties not assessed\n' "$pid"; fi
done
if [ -n "$java_pids" ]; then
  if command -v python3 >/dev/null 2>&1; then
    python3 - <<'PY'
import os, glob, json, zipfile, io, re

def emit(kind, value):
    print(kind + '\t' + json.dumps(value, ensure_ascii=True))

def properties(data):
    result = {}
    for line in data.decode('utf-8', 'replace').splitlines():
        if '=' in line and not line.startswith('#'):
            key, value = line.split('=', 1)
            result[key.strip()] = value.strip()
    return result

def metadata(archive, path):
    for info in archive.infolist()[:4096]:
        if info.filename.startswith('META-INF/maven/') and info.filename.endswith('/pom.properties') and info.file_size < 65536:
            props = properties(archive.read(info))
            if props.get('groupId') and props.get('artifactId') and props.get('version'):
                return props['groupId'] + ':' + props['artifactId'], props['version'], 'Maven pom.properties'
    name = path.rsplit('/', 1)[-1]
    match = re.match(r'(.+)-([0-9][A-Za-z0-9.+_-]*)\.jar$', name)
    return (match.group(1), match.group(2), 'filename inference') if match else (name, '', 'version unavailable')

java_count = 0
for proc in glob.glob('/proc/[0-9]*'):
    try:
        if open(proc + '/comm').read().strip() != 'java': continue
        java_count += 1
        if java_count > 64: break
        pid = int(proc.rsplit('/', 1)[1])
        with open(proc + '/cmdline', 'rb') as f: args = f.read(65536).decode('utf-8', 'replace').split('\0')
        # Arguments are used only to locate archives and are never emitted.
        paths = []
        for i, value in enumerate(args[:-1]):
            if value in ('-jar', '-cp', '-classpath', '--class-path', '--module-path', '-p'):
                paths.extend(args[i + 1].split(':'))
        for fd in glob.glob(proc + '/fd/*')[:2048]:
            try:
                value = os.readlink(fd)
                if value.endswith('.jar'): paths.append(value)
            except OSError: pass
        if not paths:
            emit('LIBISSUE', {'pid': pid, 'message': 'No readable JAR paths; custom class loaders/environment classpaths not assessed'})
        seen, count, expanded = set(), 0, 0
        for path in paths[:128]:
            source = proc + ('/root' + path if path.startswith('/') else '/cwd/' + path)
            candidates = glob.glob(source + '.jar') if source.endswith('/*') else [source]
            for candidate in candidates[:512]:
                if not candidate.endswith(('.jar', '.war')) or candidate in seen: continue
                seen.add(candidate)
                if count >= 512: raise ValueError('library limit 512')
                if os.path.getsize(candidate) > 536870912: raise ValueError('archive exceeds 512 MiB')
                with zipfile.ZipFile(candidate) as archive:
                    name, version, evidence = metadata(archive, path)
                    emit('LIBRARY', {'pid': pid, 'name': name, 'version': version, 'path': path, 'evidence': evidence})
                    count += 1
                    for info in archive.infolist()[:4096]:
                        if not (info.filename.startswith(('BOOT-INF/lib/', 'WEB-INF/lib/')) and info.filename.endswith('.jar')): continue
                        if count >= 512 or info.file_size > 16777216 or expanded + info.file_size > 67108864:
                            raise ValueError('nested library inspection limit')
                        expanded += info.file_size
                        with zipfile.ZipFile(io.BytesIO(archive.read(info))) as nested:
                            name, version, evidence = metadata(nested, info.filename)
                            emit('LIBRARY', {'pid': pid, 'name': name, 'version': version, 'path': path + '!/' + info.filename, 'evidence': evidence})
                            count += 1
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError):
        emit('LIBISSUE', {'pid': int(proc.rsplit('/', 1)[1]), 'message': 'Some archives unavailable, invalid, or inspection limit exceeded'})
PY
  else printf 'ISSUE\tjava\tPython 3 unavailable; archive metadata not assessed. Java installation is not required for base inventory.\n'; fi
fi
printf 'DONE\n'
