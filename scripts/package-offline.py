#!/usr/bin/env python3
"""Assemble the verified 1.1.0 images and public catalogs into an air-gap bundle.

Runs only on the preparation machine. Never exports live containers, .env, or DB volumes.
"""
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.1.0"
IMAGES = [
    ("wook-observer:1.1.0", "wook-observer-1.1.0.tar.gz"),
    ("postgres:18.6-bookworm", "postgres-18.6-bookworm.tar.gz"),
]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, cwd=ROOT, **kwargs)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def checksums(bundle):
    if any(path.is_symlink() for path in bundle.rglob("*")):
        raise SystemExit("Bundle must not contain filesystem symlinks")
    lines = []
    for path in sorted(bundle.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS":
            lines.append(f"{sha256(path)}  {path.relative_to(bundle).as_posix()}\n")
    (bundle / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")


def archive(bundle):
    manifest = json.loads((bundle / "PACKAGE-MANIFEST.json").read_text())
    actual = {path.relative_to(bundle).as_posix() for path in bundle.rglob("*") if path.is_file() and path.name != "SHA256SUMS"}
    if actual != set(manifest["files"]):
        raise SystemExit("Bundle file list changed; do not include private or runtime files")
    checksums(bundle)
    output = bundle.with_name(bundle.name + ".tar.gz")
    if output.exists():
        raise SystemExit(f"Refusing to overwrite {output}")
    # Archives already contain compressed image layers; modest outer compression is sufficient.
    with tarfile.open(output, "w:gz", compresslevel=3) as tar:
        tar.add(bundle, arcname=bundle.name, recursive=True)
    digest = sha256(output)
    output.with_name(output.name + ".sha256").write_text(f"{digest}  {output.name}\n")
    print(json.dumps({"archive": str(output), "bytes": output.stat().st_size, "sha256": digest}, indent=2))


def prepare(bundle):
    if bundle.exists():
        raise SystemExit(f"Refusing to overwrite {bundle}")
    catalog = ROOT / "updates/maven-osv.zip"
    origin = json.loads((ROOT / "updates/maven-osv.manifest.json").read_text())
    if sha256(catalog) != origin["sha256"] or catalog.stat().st_size != origin["bytes"]:
        raise SystemExit("Maven catalog and its manifest do not match")
    with zipfile.ZipFile(catalog) as source:
        count = len([entry for entry in source.infolist() if not entry.is_dir()])
        if count != origin["records"] or source.testzip() is not None:
            raise SystemExit("Maven catalog ZIP validation failed")
    commit = run("git", "rev-parse", f"v{VERSION}^{{commit}}", capture_output=True, text=True).stdout.strip()
    tracked = run("git", "ls-tree", "-r", "--name-only", commit, capture_output=True, text=True).stdout.splitlines()
    if any(name == ".env" or name.startswith(("resources/", ".tools/", "reports/")) for name in tracked):
        raise SystemExit("Source snapshot contains private/generated paths")
    images = []
    image_template = (ROOT / "deployment/offline/lib/image-fingerprint.template").read_text().strip()
    for reference, filename in IMAGES:
        value = run("docker", "image", "inspect", reference, "--format", "{{.Id}} {{.Os}}/{{.Architecture}} {{.Size}}", capture_output=True, text=True).stdout.split()
        if value[1] != "linux/amd64":
            raise SystemExit(f"Incorrect platform for {reference}: {value[1]}")
        material = run("docker", "image", "inspect", reference, "--format", image_template, capture_output=True).stdout
        images.append({"reference": reference, "idBeforeExport": value[0], "runtimeFingerprint": hashlib.sha256(material).hexdigest(), "platform": value[1], "uncompressedBytes": int(value[2]), "archive": "images/" + filename})
    bundle.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(ROOT / "deployment/offline", bundle)
    for name in ("images", "updates", "source", "docs"):
        (bundle / name).mkdir(exist_ok=True)
    for image in images:
        output = bundle / image["archive"]
        temporary = output.with_suffix("")
        print(f"Saving {image['reference']} (linux/amd64)", flush=True)
        run("docker", "image", "save", "--platform", "linux/amd64", "--output", str(temporary), image["reference"])
        with temporary.open("rb") as source, output.open("wb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=3, mtime=0) as destination:
            shutil.copyfileobj(source, destination, length=1024 * 1024)
        temporary.unlink()
        image["archiveBytes"] = output.stat().st_size
        image["sha256"] = sha256(output)
    (bundle / "images/images.list").write_text("".join(f"{image['reference']} {image['runtimeFingerprint']} {image['archive']}\n" for image in images))
    shutil.copy2(catalog, bundle / "updates/maven-osv.zip")
    shutil.copy2(ROOT / "updates/maven-osv.manifest.json", bundle / "updates/maven-osv.manifest.json")
    # Read application seed and documentation from the same immutable source tag as the shipped application.
    seed = run("git", "show", f"{commit}:src/main/resources/catalog/bootstrap.json", capture_output=True).stdout
    (bundle / "updates/catalog.json").write_bytes(seed)
    for source, destination in (("LICENSE", "LICENSE"), ("README.md", "docs/application-readme.md"), ("docs/catalog.md", "docs/catalog.md"), ("docs/ssh-access.md", "docs/ssh-access.md")):
        (bundle / destination).write_bytes(run("git", "show", f"{commit}:{source}", capture_output=True).stdout)
    source_archive = bundle / f"source/wook-observer-{VERSION}-source.tar.gz"
    run("git", "archive", "--format=tar.gz", f"--prefix=wook-observer-{VERSION}-source/", "--output", str(source_archive), commit)
    readme = (bundle / "README.md").read_text()
    readme = readme.replace("@@BUNDLE_NAME@@", bundle.name).replace("@@MAVEN_RECORDS@@", f"{count:,}").replace("@@MAVEN_MODIFIED@@", origin["sourceLastModified"])
    (bundle / "README.md").write_text(readme)
    manifest = {
        "applicationVersion": VERSION,
        "bundleRevision": 1,
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "platform": "linux/amd64",
        "sourceTag": f"v{VERSION}",
        "sourceCommit": commit,
        "images": images,
        "catalog": origin,
        "initialCceRecords": 2,
        "initialPolicyRecords": 1,
        "installation": "Fresh database and newly generated administrator credentials; Maven ZIP import through the admin UI",
        "excluded": [".env", "live DB data and backups", "SSH private keys", "user accounts and scan targets", "inspection reports"],
    }
    (bundle / "VERIFICATION.md").write_text("# 배포 묶음 검증\n\n검증 결과를 기록한 후 최종 압축 파일을 생성합니다.\n")
    manifest["files"] = sorted([path.relative_to(bundle).as_posix() for path in bundle.rglob("*") if path.is_file()] + ["PACKAGE-MANIFEST.json"])
    (bundle / "PACKAGE-MANIFEST.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    for path in bundle.rglob("*"):
        if path.is_dir():
            path.chmod(0o755)
        elif path.suffix == ".sh":
            path.chmod(0o755)
        else:
            path.chmod(0o644)
    bundle.chmod(0o755)
    checksums(bundle)
    print(json.dumps({"bundle": str(bundle), "mavenRecords": count, "sourceCommit": commit}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "resources/offline" / f"wook-observer-{VERSION}-linux-amd64-{datetime.now().strftime('%Y%m%d')}")
    parser.add_argument("--archive-only", action="store_true", help="After recording verification, checksum and archive an existing pristine bundle")
    args = parser.parse_args()
    bundle = args.output.resolve()
    if args.archive_only:
        if not bundle.is_dir() or (bundle / ".env").exists() or (bundle / "backups").exists():
            raise SystemExit("Archive only a pristine bundle without .env or live backups")
        archive(bundle)
    else:
        prepare(bundle)


if __name__ == "__main__":
    main()
