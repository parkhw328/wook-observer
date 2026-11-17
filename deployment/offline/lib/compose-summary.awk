# Read only public routing fields from Compose's normalized YAML. Never print environment values.
function value(line) {
    sub(/^[^:]+:[[:space:]]*/, "", line)
    gsub(/^["\047]|["\047]$/, "", line)
    return line
}
function emit() {
    if (published != "") printf "PORT\t%s\t%s\t%s\t%s\n", host == "" ? "0.0.0.0" : host, published, target, protocol == "" ? "tcp" : protocol
    host = published = target = protocol = ""
}
/^name:/ { print "PROJECT\t" value($0) }
/^  app:$/ { app = 1; next }
/^  [^ ]/ { if (app) emit(); app = ports = 0 }
app && /^    ports:/ { ports = 1; next }
ports && /^    [^ ]/ { emit(); ports = 0 }
ports {
    if ($0 ~ /^      - /) emit()
    line = $0; sub(/^[[:space:]]*(- )?/, "", line)
    if (line ~ /^host_ip:/) host = value(line)
    if (line ~ /^published:/) published = value(line)
    if (line ~ /^target:/) target = value(line)
    if (line ~ /^protocol:/) protocol = value(line)
}
END { emit() }
