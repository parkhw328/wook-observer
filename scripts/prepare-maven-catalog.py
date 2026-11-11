#!/usr/bin/env python3
"""Prepare an official OSV Maven snapshot on an internet-connected build machine."""
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time
from datetime import datetime, timezone
import urllib.request
import zipfile

SOURCE = "https://storage.googleapis.com/osv-vulnerabilities/Maven/all.zip"
DESTINATION = Path(__file__).resolve().parents[1] / "updates" / "maven-osv.zip"
MAX_COMPRESSED = 64 * 1024 * 1024
MAX_EXPANDED = 256 * 1024 * 1024


def main():
    DESTINATION.parent.mkdir(exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=DESTINATION.parent, suffix=".part", delete=False) as output:
            temporary = Path(output.name)
            digest = hashlib.sha256()
            count = 0
            started = time.monotonic()
            with urllib.request.urlopen(SOURCE, timeout=30) as response:
                modified = response.headers.get("Last-Modified", "")
                while chunk := response.read(65536):
                    count += len(chunk)
                    if count > MAX_COMPRESSED or time.monotonic() - started > 120:
                        raise ValueError("Download exceeds size or time limit")
                    output.write(chunk)
                    digest.update(chunk)
        with zipfile.ZipFile(temporary) as archive:
            all_entries = archive.infolist()
            entries = [entry for entry in all_entries if not entry.is_dir()]
            if len(all_entries) > 50000 or sum(entry.file_size for entry in all_entries) > MAX_EXPANDED:
                raise ValueError("Split this catalog into smaller batches before import")
            if any(entry.is_dir() and entry.file_size != 0 for entry in all_entries):
                raise ValueError("ZIP directory entries must not contain data")
            if any(not entry.filename.endswith(".json") or entry.file_size > 4 * 1024 * 1024 for entry in entries):
                raise ValueError("Unsupported or oversized ZIP entry")
            if archive.testzip() is not None:
                raise ValueError("ZIP CRC verification failed")
        # The container's non-root account must be able to read the staged, public catalog.
        os.chmod(temporary, 0o644)
        os.replace(temporary, DESTINATION)
        manifest = {
            "source": SOURCE,
            "downloadedAt": datetime.now(timezone.utc).isoformat(),
            "sourceLastModified": modified,
            "sha256": digest.hexdigest(),
            "bytes": count,
            "records": len(entries),
            "attribution": "OSV data sources: https://google.github.io/osv.dev/data/",
        }
        DESTINATION.with_suffix(".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps({"file": str(DESTINATION), **manifest}, indent=2))
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
