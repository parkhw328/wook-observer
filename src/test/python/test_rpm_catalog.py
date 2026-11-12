import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("rpm_catalog", Path(__file__).resolve().parents[3] / "scripts/prepare-rpm-catalog.py")
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class RpmCatalogTest(unittest.TestCase):
    def test_keeps_security_and_withdrawals_without_altering_original_bytes(self):
        def record(identifier, ecosystem="AlmaLinux:9", **extra):
            return {"id": identifier, "modified": "2026-11-01T00:00:00Z", "affected": [
                {"package": {"name": "openssl", "ecosystem": ecosystem}}], **extra}
        records = [record("ALSA-2026:0001"), record("ALBA-2026:0002"), record("ALSA-2026:0003", "AlmaLinux:7"),
                   record("ALSA-2026:0004", affected=[], withdrawn="2026-11-02T00:00:00Z")]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            raw = [json.dumps(r, indent=3).encode() for r in records]
            with zipfile.ZipFile(root / "source.zip", "w") as archive:
                for i, data in enumerate(raw): archive.writestr(f"{i}.json", data)
            rows, excluded = catalog.selected_records(root / "source.zip", "almalinux", "ALSA-")
            self.assertEqual([r[1] for r in rows], [raw[0], raw[3]])
            self.assertEqual(excluded, {"nonSecurity": 1, "otherPlatforms": 1})
            with patch.object(catalog, "MAX_BATCH", len(raw[0]) + 1):
                files = catalog.write_batches(rows, "almalinux", root)
            self.assertEqual(len(files), 2)
            for item, original in zip(files, [raw[0], raw[3]]):
                self.assertEqual(catalog.sha256(root / item["file"]), item["sha256"])
                with zipfile.ZipFile(root / item["file"]) as archive:
                    self.assertEqual(archive.read(archive.namelist()[0]), original)

    def test_rejects_duplicate_ids_and_cache_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record = {"id": "RLSA-2026:0001", "modified": "2026-11-01T00:00:00Z", "withdrawn": "2026-11-02T00:00:00Z"}
            with zipfile.ZipFile(root / "source.zip", "w") as archive:
                archive.writestr("one.json", json.dumps(record))
                archive.writestr("two.json", json.dumps(record))
            with self.assertRaisesRegex(ValueError, "duplicate"):
                catalog.selected_records(root / "source.zip", "rocky", "RLSA-")
            (root / "rocky.zip").write_bytes(b"tampered")
            (root / "rocky.manifest.json").write_text(json.dumps({"source": "https://storage.googleapis.com/osv-vulnerabilities/Rocky%20Linux/all.zip", "sha256": "0" * 64, "bytes": 8}))
            with self.assertRaisesRegex(ValueError, "manifest"):
                catalog.fetch(root, "rocky", "Rocky Linux", True)


if __name__ == "__main__":
    unittest.main()
