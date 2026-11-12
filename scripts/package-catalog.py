#!/usr/bin/env python3
"""Package prepared Maven and RPM catalogs, provenance and licenses for offline delivery."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "resources/catalog" / f"wook-observer-data-{datetime.now():%Y%m%d}-rpm")
    parser.add_argument("--verification-file", type=Path, help="Optional successful isolated verification receipt")
    args = parser.parse_args()
    output = args.output.resolve()
    if output.exists() or output.with_suffix(".zip").exists():
        raise SystemExit("Refusing to overwrite an existing delivery")
    maven = json.loads((ROOT / "updates/maven-osv.manifest.json").read_text())
    rpm = json.loads((ROOT / "updates/rpm-catalog.manifest.json").read_text())
    files = [{"file": "maven-osv.zip", **maven}] + rpm["files"]
    for item in files:
        name = item["file"]
        if Path(name).name != name:
            raise SystemExit("Invalid source filename")
        path = ROOT / "updates" / name
        if digest(path) != item["sha256"] or path.stat().st_size != item["bytes"]:
            raise SystemExit("Source checksum mismatch: " + name)
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None or len([e for e in archive.infolist() if not e.is_dir()]) != item["records"]:
                raise SystemExit("Invalid catalog ZIP: " + name)
    verification = None
    if args.verification_file:
        verification = json.loads(args.verification_file.read_text())
        if verification.get("status") != "PASS" or not verification.get("allCatalogDocumentsMatchSources"):
            raise SystemExit("A successful full-catalog verification receipt is required")
        verified = {i["file"]: i["sha256"] for i in verification["imports"]}
        if any(verified.get(i["file"]) != i["sha256"] for i in files):
            raise SystemExit("Verification does not cover these source files")
    (output / "import").mkdir(parents=True)
    for item in files:
        shutil.copy2(ROOT / "updates" / item["file"], output / "import" / item["file"])
    with zipfile.ZipFile(ROOT / "updates/maven-osv.zip") as archive:
        records = [json.loads(archive.read(e)) for e in archive.infolist() if not e.is_dir()]
    (output / "import/maven-osv.json").write_text(json.dumps(records, ensure_ascii=False, separators=(",", ":")) + "\n")
    seed = json.loads((ROOT / "src/main/resources/catalog/bootstrap.json").read_text())
    rules = [r for r in seed["records"] if r.get("kind") in ("CCE", "POLICY")]
    (output / "import/cce-policy.json").write_text(json.dumps({"format": "wook-catalog-1", "records": rules}, ensure_ascii=False, indent=2) + "\n")
    shutil.copytree(ROOT / "docs/licenses", output / "licenses")
    shutil.copy2(ROOT / "LICENSE", output / "licenses/wook-observer.txt")
    for name in ("maven-osv.manifest.json", "rpm-catalog.manifest.json"):
        shutil.copy2(ROOT / "updates" / name, output / name)
    shutil.copy2(ROOT / "src/main/resources/catalog/redhat-repositories.json", output / "redhat-repositories.json")
    shutil.copy2(ROOT / "docs/catalog.md", output / "catalog-guide.md")
    if verification:
        (output / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2) + "\n")
    total = maven["records"] + rpm["records"] + len(rules)
    table = "\n".join(f"| `{i['file']}` | {i['records']:,} |" for i in files)
    (output / "README.md").write_text(f"""# Observer 폐쇄망 데이터 반입

Maven {maven['records']:,}건과 RHEL·Rocky·AlmaLinux 8/9/10 RPM 보안 권고 {rpm['records']:,}건입니다. 기존 CCE/정책 {len(rules)}건을 포함한 기본 DB 예상 총수는 **{total:,}건**입니다. 보안 권고 수이며 고유 CVE 수와 다릅니다. RPM 설치 바이너리는 포함하지 않습니다.

## 적용 순서

1. **RPM 대조·열린 포트 수집은 Observer 1.5.0 이상으로 업데이트**해야 합니다. 별도 앱 배포 묶음의 기존 설치 업그레이드 절차를 따라 DB·계정·암호화 키를 유지하세요.
2. 이 전달 ZIP을 풀고 `sha256sum -c SHA256SUMS`로 확인합니다.
3. 관리자 **데이터 업데이트 → 취약점 목록 반입 → 검증 후 반영**에서 아래 `import/` 파일을 하나씩 반영합니다. 바깥 전달 ZIP·manifest·라이선스는 업로드하지 않습니다.

| import/ 파일 | 권고 수 |
| --- | ---: |
{table}

4. 기본 설치의 CCE/정책은 유지됩니다. 복원용 `import/cce-policy.json`에는 기존 규칙만 있습니다. Maven은 동일 내용의 `maven-osv.json`도 제공하므로 ZIP/JSON 중 하나를 선택하세요. RPM ZIP 안에는 개별 권고 JSON이 있습니다.
5. 성공 이력·해시·전체 건수를 확인하고 **새 점검**을 시작합니다. 기존 보고서는 당시 결과를 유지합니다. 별도 자료가 이미 있으면 총수는 더 많을 수 있습니다.

## 판정과 수집 범위

RPM은 배포판·공급자·아키텍처·Epoch/Version/Release를 확인해 공식 권고와 대조합니다. 소스 RPM 대응은 의심, 모듈·RHEL 제품 스트림 미확인은 미판정입니다. 관련 CVE 전체가 각 바이너리에 개별 적용됨을 의미하지 않습니다. `redhat-repositories.json`은 앱에 내장한 공식 저장소/CPE 매핑의 참고 사본이며 업로드 파일이 아닙니다.

열린 포트는 대상의 `ss`·`timeout`으로 TCP 리스닝·UDP 바인딩, IPv4/IPv6·PID/프로세스를 수집합니다. 권한에 따라 PID가 없을 수 있으며 방화벽·외부 접속 가능성은 별도 확인합니다. 웹·JSON·Markdown·Excel로 확인하세요.

## 출처·라이선스·무결성

[카탈로그 안내](catalog-guide.md), Maven/RPM manifest, `licenses/`, `SHA256SUMS`를 함께 보관하세요. 원본 취득 시각과 HTTP Last-Modified를 구분합니다. 각 권고의 modified·withdrawn을 보존했으며 삭제 목록 대신 철회 정보를 반영합니다. 선별한 RPM 레코드는 원본 바이트를 그대로 유지합니다.

Red Hat, Inc. 보안 데이터/매핑과 GitHub Advisory Database는 CC BY 4.0, AlmaLinux OS Foundation 데이터는 MIT, Rocky 자료는 원본 라이선스 및 BSD 3-Clause 고지를 보존합니다. OpenSSF malicious-packages는 Apache 2.0, ComplianceAsCode는 BSD 3-Clause입니다. 자세한 고지는 동봉 문서에 있습니다.

검증 영수증이 동봉된 경우 별도 시험 환경의 결과이며 실제 폐쇄망 서버에 이미 적용했다는 뜻은 아닙니다. 운영 DB·계정·비밀번호·SSH 키·보고서는 포함하지 않습니다.
""", encoding="utf-8")
    manifest = {"format": "wook-data-delivery-1", "createdAt": datetime.now(timezone.utc).isoformat(),
                "applicationRequiredForRpmAndPorts": "1.5.0", "totalWithExistingRules": total,
                "mavenRecords": maven["records"], "rpmRecords": rpm["records"],
                "importFiles": files, "mavenJsonAlternative": "import/maven-osv.json",
                "files": {p.relative_to(output).as_posix(): {"sha256": digest(p), "bytes": p.stat().st_size}
                          for p in sorted(output.rglob("*")) if p.is_file()}}
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    (output / "SHA256SUMS").write_text("".join(f"{digest(p)}  {p.relative_to(output).as_posix()}\n"
                                               for p in sorted(output.rglob("*")) if p.is_file()))
    destination = output.with_suffix(".zip")
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file(): archive.write(path, arcname=output.name + "/" + path.relative_to(output).as_posix())
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None: raise SystemExit("Delivery CRC validation failed")
    checksum = digest(destination)
    destination.with_suffix(".zip.sha256").write_text(f"{checksum}  {destination.name}\n")
    print(json.dumps({"archive": str(destination), "bytes": destination.stat().st_size, "sha256": checksum, "records": total}, indent=2))


if __name__ == "__main__":
    main()
