"""Read-only JAR metadata inventory; embedded in the fixed SSH script, never installed."""
import glob
import io
import json
import os
import re
import zipfile

MAX_LIBRARIES = 512
MAX_ENTRIES = 100000


def emit(kind, value):
    print(kind + '\t' + json.dumps(value, ensure_ascii=True))


def properties(data):
    result = {}
    # Maven-generated pom.properties use ASCII coordinates and Java property escapes.
    text = data.decode('iso-8859-1')
    text = re.sub(r'\\\r?\n\s*', '', text)
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        pair = re.split(r'\s*[=:]\s*|\s+', line, maxsplit=1)
        if len(pair) == 2:
            result[pair[0]] = re.sub(r'\\u([0-9a-fA-F]{4})|\\(.)',
                lambda m: chr(int(m[1], 16)) if m[1] else m[2], pair[1])
    return result


def metadata(archive, path):
    """Return every Maven component; a Manifest is only a fallback."""
    entries = archive.infolist()
    if len(entries) > MAX_ENTRIES:
        raise ValueError('archive entry limit')
    artifacts = []
    for info in entries:
        if info.filename.startswith('META-INF/maven/') and info.filename.endswith('/pom.properties'):
            if info.file_size > 65536:
                raise ValueError('metadata size limit')
            props = properties(archive.read(info))
            if props.get('artifactId') and props.get('version'):
                name = (props['groupId'] + ':' if props.get('groupId') else '') + props['artifactId']
                artifacts.append((name, props['version'], path + '!/' + info.filename, 'Maven pom.properties'))
                if len(artifacts) > MAX_LIBRARIES:
                    raise ValueError('Maven metadata limit')
    if len(artifacts) == 1:
        name, version, _, evidence = artifacts[0]
        return [(name, version, path, evidence)]
    filename = path.rsplit('/', 1)[-1]
    match = re.fullmatch(r'(.+?)-(\d[^/]*)\.jar', filename)
    fallback = (match[1], match[2], path, 'filename inference') if match else (filename, '', path, 'version unavailable')
    if 'META-INF/MANIFEST.MF' in archive.namelist():
        info = archive.getinfo('META-INF/MANIFEST.MF')
        if info.file_size > 65536:
            raise ValueError('manifest size limit')
        raw = archive.read(info).decode('utf-8', 'replace').replace('\r\n', '\n').replace('\n ', '')
        attributes = dict(line.split(': ', 1) for line in raw.split('\n\n', 1)[0].splitlines() if ': ' in line)
        version = attributes.get('Implementation-Version') or attributes.get('Bundle-Version')
        if version:
            name = attributes.get('Implementation-Title') or attributes.get('Bundle-SymbolicName') or fallback[0]
            fallback = (name, version, path, 'META-INF/MANIFEST.MF')
    return [fallback] + artifacts


def inspect(candidate, path):
    if os.path.getsize(candidate) > 536870912:
        raise ValueError('archive exceeds 512 MiB')
    with zipfile.ZipFile(candidate) as archive:
        for item in metadata(archive, path):
            yield item
        expanded = 0
        for info in archive.infolist():
            if not (info.filename.startswith(('BOOT-INF/lib/', 'WEB-INF/lib/')) and info.filename.endswith('.jar')):
                continue
            if info.file_size > 16777216 or expanded + info.file_size > 67108864:
                raise ValueError('nested inspection size limit')
            expanded += info.file_size
            with zipfile.ZipFile(io.BytesIO(archive.read(info))) as nested:
                for item in metadata(nested, path + '!/' + info.filename):
                    yield item


def collect(proc_root='/proc'):
    java_count = 0
    for proc in sorted(glob.glob(proc_root + '/[0-9]*')):
        pid = int(proc.rsplit('/', 1)[1])
        try:
            with open(proc + '/comm') as stream:
                if stream.read().strip() != 'java':
                    continue
            java_count += 1
            if java_count > 64:
                break
            with open(proc + '/cmdline', 'rb') as stream:
                args = stream.read(65536).decode('utf-8', 'replace').split('\0')
            # Arguments locate archives; they are never returned to the caller.
            paths = []
            for i, value in enumerate(args[:-1]):
                if value in ('-jar', '-cp', '-classpath', '--class-path', '--module-path', '-p'):
                    paths.extend(args[i + 1].split(':'))
                elif value.startswith(('--class-path=', '--module-path=')):
                    paths.extend(value.split('=', 1)[1].split(':'))
            for fd in glob.glob(proc + '/fd/*')[:2048]:
                try:
                    value = os.readlink(fd)
                    if value.endswith(('.jar', '.war')):
                        paths.append(value)
                except OSError:
                    pass
            if not paths:
                emit('LIBISSUE', {'pid': pid, 'message': 'No readable JAR paths; custom/environment classpaths not assessed'})
            if len(paths) > 128:
                emit('LIBISSUE', {'pid': pid, 'message': 'JAR path limit 128'})
            seen, count = set(), 0
            for path in paths[:128]:
                root = proc + ('/root' if path.startswith('/') else '/cwd/')
                source = root + path
                candidates = sorted(glob.glob(source + '.jar')) if source.endswith('/*') else [source]
                if len(candidates) > MAX_LIBRARIES:
                    emit('LIBISSUE', {'pid': pid, 'message': 'Wildcard library limit 512'})
                for candidate in candidates[:MAX_LIBRARIES]:
                    if not candidate.endswith(('.jar', '.war')):
                        continue
                    if count >= MAX_LIBRARIES:
                        raise ValueError('library limit 512')
                    try:
                        stat = os.stat(candidate)
                        identity = (stat.st_dev, stat.st_ino)
                        if identity in seen:
                            continue
                        seen.add(identity)
                        # A wildcard must not give every JAR the same asset/path.
                        display = candidate[len(root):] if path.endswith('/*') else path
                        for name, version, location, evidence in inspect(candidate, display):
                            if count >= MAX_LIBRARIES:
                                raise ValueError('library limit 512')
                            emit('LIBRARY', {'pid': pid, 'name': name, 'version': version, 'path': location, 'evidence': evidence})
                            count += 1
                    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError, NotImplementedError):
                        emit('LIBISSUE', {'pid': pid, 'message': 'Archive unavailable/invalid or inspection limit: ' + path})
        except (OSError, ValueError):
            emit('LIBISSUE', {'pid': pid, 'message': 'Process unavailable or library inspection limit reached'})


if __name__ == '__main__':
    collect()
