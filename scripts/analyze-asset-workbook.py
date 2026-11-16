#!/usr/bin/env python3
"""Profile a wPost asset workbook without executing commands or exporting host addresses.

Development-only dependency: openpyxl. The original workbook is never changed.
Output contains counts, headers and cell references, not operational cell contents.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from openpyxl import load_workbook


def profile(path):
    book = load_workbook(path, data_only=False)
    result = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "sheets": []}
    hosts = {}
    for sheet in book:
        headers = [cell.value for cell in sheet[1]]
        rows = [(i, [cell.value for cell in row]) for i, row in enumerate(sheet.iter_rows(), 1)
                if i > 1 and any(cell.value is not None for cell in row)]
        host_col, ip_col = headers.index("HOST명"), headers.index("IP")
        host_keys = {(str(values[host_col]).strip().lower(), str(values[ip_col]).strip())
                     for _, values in rows if values[host_col] and values[ip_col]}
        hosts[sheet.title] = host_keys
        result["sheets"].append({
            "name": sheet.title, "physicalDataRows": len(rows), "columns": len(headers), "headers": headers,
            "explicitHostAddressPairs": len(host_keys), "mergedRanges": [str(r) for r in sheet.merged_cells.ranges],
            "populatedCells": {str(header): sum(value[j] is not None and str(value[j]).strip() != "" for _, value in rows)
                               for j, header in enumerate(headers)},
            "formulaCells": [cell.coordinate for row in sheet for cell in row if cell.data_type == "f"],
        })
    if {"1단계", "2단계", "병합"}.issubset(book.sheetnames):
        sheet = book["병합"]
        shared_rows = list(range(2, 8))
        continuation = sorted({i for region in sheet.merged_cells.ranges if region.min_col <= 7 <= region.max_col
                               and region.min_row > 7 for i in range(region.min_row + 1, region.max_row + 1)})
        candidates = [i for i in range(8, sheet.max_row + 1) if i not in continuation and any(c.value is not None for c in sheet[i])]
        teams = {str(book["2단계"].cell(i, 6).value).strip() for i in range(2, book["2단계"].max_row + 1)}
        accounts = {str(book["2단계"].cell(i, 9).value).strip() for i in range(2, book["2단계"].max_row + 1)}
        issues = []
        for i in candidates:
            for col, rule, matched in [
                (6, "team_in_version_column", str(sheet.cell(i, 6).value).strip() in teams),
                (9, "account_in_install_column", str(sheet.cell(i, 9).value).strip() in accounts),
                (12, "log_path_in_start_column", str(sheet.cell(i, 12).value).startswith(("/LOG", "D:\\LOG"))),
                (11, "data_path_in_port_column", str(sheet.cell(i, 11).value).startswith("/DATA")),
            ]:
                if matched: issues.append({"sheet": sheet.title, "cell": sheet.cell(i, col).coordinate, "issue": rule})
        for s in book:
            for row in s:
                for cell in row:
                    value = str(cell.value or "")
                    if "docekr" in value or "ps –" in value:
                        issues.append({"sheet": s.title, "cell": cell.coordinate, "issue": "command_spelling_or_unicode_dash_review"})
        result["interpretation"] = {
            "firstTwoSheetsUniqueHostAddressPairs": len(hosts["1단계"] | hosts["2단계"]),
            "mergedUniqueHostAddressPairs": len(hosts["병합"]),
            "additionalPairsInMerged": len(hosts["병합"] - hosts["1단계"] - hosts["2단계"]),
            "sharedModuleRows": shared_rows, "connectionContinuationRows": continuation,
            "hostServiceRowGroups": len(candidates),
            "groupCountCaveat": "Row groups are not unique services; some groups describe multiple services.",
            "portCellsInStageTwo": sum(book["2단계"].cell(i, 8).value is not None for i in range(2, book["2단계"].max_row + 1)),
            "reviewCounts": dict(Counter(issue["issue"] for issue in issues)), "reviewCells": issues,
        }
    book.close()
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.workbook.resolve() == args.output.resolve():
        parser.error("Output must differ from the original workbook")
    result = profile(args.workbook)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sheets": len(result["sheets"]), "output": str(args.output),
                      "interpretation": {k: v for k, v in result.get("interpretation", {}).items() if k != "reviewCells"}}, ensure_ascii=False))
