#!/usr/bin/env python3
import csv
import pathlib
import sys


REQUIRED_P0 = {
    "RC-001", "RC-003", "RC-004", "RC-005", "RC-010", "RC-012", "RC-014", "RC-015", "RC-019", "RC-020"
}


def main() -> int:
    csv_path = pathlib.Path("release_test_cases.csv")
    if not csv_path.exists():
        print("release_test_cases.csv missing")
        return 1

    with csv_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        rows = list(reader)

    if not rows:
        print("release_test_cases.csv has no rows")
        return 1

    ids = [row.get("id", "").strip() for row in rows]
    if len(ids) != len(set(ids)):
        print("Duplicate test IDs found in release_test_cases.csv")
        return 1

    missing_required = sorted(REQUIRED_P0 - set(ids))
    if missing_required:
        print("Missing required P0 release cases:", ", ".join(missing_required))
        return 1

    bad_rows = []
    for row in rows:
        rid = row.get("id", "").strip()
        for field in ("area", "test_case", "expected_result", "priority"):
            if not row.get(field, "").strip():
                bad_rows.append(f"{rid}: missing {field}")
    if bad_rows:
        print("Invalid release rows:")
        for issue in bad_rows:
            print("-", issue)
        return 1

    print(f"release_test_cases.csv validated ({len(rows)} cases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
