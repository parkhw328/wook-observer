#!/usr/bin/env python3
"""Prepare official RHEL/Rocky/AlmaLinux 8/9/10 security advisories for offline import.

Runs on the internet-connected preparation PC only. Preserves each selected OSV
record byte-for-byte, including withdrawals and non-selected product references.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import tempfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (("redhat", "Red Hat", r"RHSA-"), ("rocky", "Rocky Linux", r"(?:RLSA|RXSA)-"),
           ("almalinux", "AlmaLinux", r"ALSA-"))
MAX_SOURCE = 128 * 1024 * 1024
MAX_EXPANDED = 512 * 1024 * 1024
MAX_BATCH = 48 * 1024 * 1024
MAX_ENTRY = 4 * 1024 * 1024


def sha256(path):
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def fetch(cache, slug, ecosystem, use_cache):
    path = cache / (slug + ".zip")
    meta_path = path.with_suffix(".manifest.json")
    source = "https://storage.googleapis.com/osv-vulnerabilities/" + urllib.parse.quote(ecosystem, safe="") + "/all.zip"
    if use_cache:
        meta = json.loads(meta_path.read_text())
        if meta["source"] != source or sha256(path) != meta["sha256"] or path.stat().st_size != meta["bytes"]:
            raise ValueError("Cached source does not match its manifest: " + slug)
        return path, meta
    temporary = path.with_suffix(".part")
    try:
        with urllib.request.urlopen(source, timeout=40) as response, temporary.open("wb") as output:
            modified = response.headers.get("Last-Modified", "")
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
                if output.tell() > MAX_SOURCE:
                    raise ValueError("Source download exceeds 128 MiB")
        with zipfile.ZipFile(temporary) as archive:
            if sum(e.file_size for e in archive.infolist()) > MAX_EXPANDED or archive.testzip() is not None:
                raise ValueError("Invalid or oversized source archive")
        temporary.replace(path)
        meta = {"source": source, "downloadedAt": datetime.now(timezone.utc).isoformat(),
                "sourceLastModified": modified, "sha256": sha256(path), "bytes": path.stat().st_size}
        meta_path.write_text(json.dumps(meta, indent=2) + "\n")
        return path, meta
    finally:
        temporary.unlink(missing_ok=True)


def relevant(ecosystem, slug):
    if slug == "redhat":
        return re.fullmatch(r"Red Hat:(?:enterprise_linux|enterprise_linux_eus|rhel_(?:eus|aus|e4s|tus)):(?:8|9|10)(?:\.[0-9]+)?(?::.*)?", ecosystem) is not None
    prefix = "Rocky Linux" if slug == "rocky" else "AlmaLinux"
    return ecosystem in {prefix + ":" + major for major in ("8", "9", "10")}


def selected_records(path, slug, prefix):
    rows, seen = [], set()
    excluded = {"nonSecurity": 0, "otherPlatforms": 0}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len(entries) > 50000 or sum(e.file_size for e in entries) > MAX_EXPANDED:
            raise ValueError("Source ZIP limit exceeded")
        for entry in entries:
            if entry.is_dir():
                if entry.file_size:
                    raise ValueError("Nonempty directory in source")
                continue
            if not entry.filename.endswith(".json") or entry.file_size > MAX_ENTRY:
                raise ValueError("Invalid source record size/type")
            data = archive.read(entry)
            record = json.loads(data)
            advisory_id = record.get("id", "")
            if advisory_id in seen or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.:-]{2,159}", advisory_id):
                raise ValueError("Invalid or duplicate advisory ID")
            seen.add(advisory_id)
            for key in ("modified", "withdrawn"):
                if key == "modified" or record.get(key) is not None:
                    datetime.fromisoformat(record[key].replace("Z", "+00:00"))
            if not re.match(prefix, advisory_id):
                excluded["nonSecurity"] += 1
                continue
            if not record.get("withdrawn") and not any(relevant(a.get("package", {}).get("ecosystem", ""), slug) for a in record.get("affected", [])):
                excluded["otherPlatforms"] += 1
                continue
            if not isinstance(record.get("affected", []), list):
                raise ValueError("Invalid affected list")
            for affected in record.get("affected", []):
                if not affected.get("package", {}).get("name") or not affected.get("package", {}).get("ecosystem"):
                    raise ValueError("Missing package identity")
            rows.append((advisory_id, data, record))
    if not rows:
        raise ValueError("No matching security advisories in " + str(path))
    return sorted(rows, key=lambda row: row[0]), excluded


def write_batches(rows, slug, output):
    batches, batch, size = [], [], 0
    for row in rows:
        if batch and (size + len(row[1]) > MAX_BATCH or len(batch) >= 20000):
            batches.append(batch); batch = []; size = 0
        batch.append(row); size += len(row[1])
    if batch:
        batches.append(batch)
    files = []
    for number, batch in enumerate(batches, 1):
        path = output / f"rpm-{slug}-{number:02d}.zip"
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for advisory_id, data, _ in batch:
                # Colons are not portable file names on Windows; JSON IDs stay unchanged.
                name = advisory_id.replace(":", "_") + ".json"
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, data)
        if path.stat().st_size > 64 * 1024 * 1024:
            raise ValueError("Compressed import batch exceeds 64 MiB")
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Output CRC verification failed")
            for entry, row in zip(archive.infolist(), batch, strict=True):
                if archive.read(entry) != row[1]:
                    raise ValueError("Output does not preserve the original advisory")
        files.append({"file": path.name, "sha256": sha256(path), "bytes": path.stat().st_size,
                      "records": len(batch), "expandedBytes": sum(len(row[1]) for row in batch)})
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-directory", type=Path, default=ROOT / "resources/catalog-cache")
    parser.add_argument("--output-directory", type=Path, default=ROOT / "updates")
    parser.add_argument("--use-cache", action="store_true", help="Verify and reuse previously downloaded source archives")
    args = parser.parse_args()
    args.cache_directory.mkdir(parents=True, exist_ok=True)
    args.output_directory.mkdir(parents=True, exist_ok=True)
    manifest = {"format": "wook-rpm-catalog-1", "createdAt": datetime.now(timezone.utc).isoformat(),
                "platforms": [os + ":" + major for os in ("rhel", "rocky", "almalinux") for major in ("8", "9", "10")],
                "selection": "Security advisories applicable to 8/9/10; vendor withdrawals retained even without affected metadata. Whole selected records preserved; no field edits.",
                "sources": [], "files": []}
    # Build off to the side; publish the manifest last so partial runs cannot pass checksum verification.
    with tempfile.TemporaryDirectory(dir=args.output_directory, prefix=".rpm-catalog-") as staging:
        for slug, ecosystem, prefix in SOURCES:
            path, source = fetch(args.cache_directory, slug, ecosystem, args.use_cache)
            rows, excluded = selected_records(path, slug, prefix)
            files = write_batches(rows, slug, Path(staging))
            manifest["sources"].append({"ecosystem": ecosystem, **source, "selectedRecords": len(rows),
                                        "withdrawnRecords": sum(bool(row[2].get("withdrawn")) for row in rows), "excluded": excluded})
            manifest["files"].extend(files)
            print(json.dumps({"ecosystem": ecosystem, "records": len(rows), "batches": len(files), "excluded": excluded}), flush=True)
        manifest["records"] = sum(f["records"] for f in manifest["files"])
        for item in manifest["files"]:
            path = Path(staging) / item["file"]
            path.chmod(0o644)
            path.replace(args.output_directory / item["file"])
        destination = args.output_directory / "rpm-catalog.manifest.json"
        temporary = Path(staging) / destination.name
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(destination)
    print(json.dumps({"manifest": str(destination), "records": manifest["records"], "importFiles": len(manifest["files"])}))


if __name__ == "__main__":
    main()
