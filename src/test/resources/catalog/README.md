# RPM fixtures

`rpm-advisories.json` contains official RHSA-2022:7288, RLSA-2022:7288 and ALSA-2022:7288 records retrieved from the OSV ecosystem dumps on 2026-11-12. Only the `affected` arrays were filtered to RHEL/Rocky/AlmaLinux 9 `openssl` and `openssl-libs` entries. Original advisory identity, relationships, timestamps and fixed EVR are retained. See the [data attribution and licenses](../../../../docs/catalog.md#출처와-재배포-고지).

`rpm-ordering.tsv` records 400 EVR comparisons returned by Rocky Linux 9 RPM 4.16.1.3 `rpm.vercmp`. It includes all pairs of ten boundary examples and seeded random pairs (seed 1540). The expected values were produced by the native RPM implementation, independently of the Java comparator. Tests do not require Docker or RPM.
