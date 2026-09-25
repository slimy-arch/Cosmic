#!/usr/bin/env python3
"""Add cash equipment from Character.wz XML files to catalog.tsv.

Put this script and catalog.tsv inside the wz folder, then double-click the
script or run:  python build_cash_catalog.py

Expected layout:
    wz/build_cash_catalog.py
    wz/catalog.tsv
    wz/Character.wz/accessory/010xxxxx.img.xml
    wz/String.wz/Eqp.img.xml
"""

from __future__ import annotations

import csv
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


CATALOG_NAME = "catalog.tsv"
OUTPUT_NAME = "catalog_cash.tsv"
DEFAULT_PRICE = 0       # Character.wz has no Cash Shop price information.
DEFAULT_COUNT = 1
DEFAULT_PERIOD = 0
DEFAULT_GENDER = 2      # 0 = male, 1 = female, 2 = unisex

FOLDER_CATEGORIES = {
    "cap": 0,
    "longcoat": 3,
    "coat": 4,
    "pants": 5,
    "shoes": 6,
    "glove": 7,
    "weapon": 8,
    "ring": 9,
    "cape": 11,
}

ACCESSORY_CATEGORIES = {
    "101": 1,  # Face accessory
    "102": 2,  # Eye accessory
    "103": 9,  # Earrings
}

HEADER = ["itemId", "price", "count", "tab", "category",
          "period", "gender", "name"]
ID_PATTERN = re.compile(r"(?<!\d)(\d{7,8})(?!\d)")


def pause() -> None:
    if sys.stdin.isatty():
        input("\nPress Enter to close...")


def find_value(root: ET.Element, name: str) -> str | None:
    """Find a WZ XML property by name and return its value."""
    for element in root.iter():
        if element.get("name", "").lower() == name.lower():
            return element.get("value")
    return None


def get_item_id(xml_path: Path, root: ET.Element) -> int | None:
    # Normal Character.wz files are named 1000000.img.xml or 1000000.xml.
    match = ID_PATTERN.search(xml_path.name)
    if match:
        return int(match.group(1))

    # Also support exports whose top-level imgdir name contains the item ID.
    match = ID_PATTERN.search(root.get("name", ""))
    return int(match.group(1)) if match else None


def get_category(folder: str, item_id: int) -> int | None:
    if folder == "accessory":
        return ACCESSORY_CATEGORIES.get(str(item_id)[:3])
    return FOLDER_CATEGORIES.get(folder)


def load_eqp_names(path: Path, folders: list[str]) -> dict[str, dict[int, str]]:
    """Load item names separately from each Eqp.img.xml category."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise RuntimeError(f"Could not read {path}: {exc}") from exc

    wanted = set(folders)
    names: dict[str, dict[int, str]] = {folder: {} for folder in folders}

    # Eqp.img.xml uses sections such as Cap, Accessory, Coat, Weapon, etc.
    # Searching within the matching section prevents an ID from being assigned
    # a name from the wrong equipment category.
    for section in root.iter():
        section_name = section.get("name", "").lower()
        if section_name not in wanted:
            continue

        for item_node in section.iter():
            raw_id = item_node.get("name", "")
            if not raw_id.isdigit():
                continue

            item_id = int(raw_id)  # Also handles a leading zero if present.
            item_name = None
            for value_node in item_node.iter():
                if (value_node.tag.lower() == "string"
                        and value_node.get("name", "").lower() == "name"):
                    item_name = value_node.get("value")
                    break

            if item_name:
                names[section_name][item_id] = item_name

    return names


def load_catalog(path: Path) -> tuple[list[str], dict[int, list[str]]]:
    comments: list[str] = []
    rows: dict[int, list[str]] = {}

    with path.open("r", encoding="utf-8-sig", newline="") as file:
        for raw_line in file:
            if raw_line.startswith("#") or not raw_line.strip():
                comments.append(raw_line.rstrip("\r\n"))
                continue

            # QUOTE_NONE: the server splits on tabs only, so quotes are part of the name.
            row = next(csv.reader([raw_line.rstrip("\r\n")], delimiter="\t",
                                  quoting=csv.QUOTE_NONE))
            if row and row[0].strip().lower() == "itemid":
                continue
            if len(row) < 8 or not row[0].strip().isdigit():
                print(f"[WARNING] Ignoring invalid catalogue row: {raw_line.rstrip()}")
                continue

            row = row[:8]
            rows[int(row[0])] = row

    return comments, rows


def category_prices(catalog: dict[int, list[str]]) -> dict[int, int]:
    """Median price of the permanent Equip-tab rows already in each category."""
    prices: dict[int, list[int]] = {}
    for row in catalog.values():
        if row[3] == "2" and row[5] == "0" and row[1].isdigit() and int(row[1]) > 0:
            prices.setdefault(int(row[4]), []).append(int(row[1]))
    return {cat: int(statistics.median(p)) for cat, p in prices.items()}


def main() -> None:
    base = Path(__file__).resolve().parent
    character_root = base / "Character.wz"
    eqp_strings_path = base / "String.wz" / "Eqp.img.xml"
    catalog_path = base / CATALOG_NAME
    output_path = base / OUTPUT_NAME

    print("=" * 68)
    print("CHARACTER.WZ CASH CATALOG BUILDER")
    print("=" * 68)
    print(f"Folder : {base}")
    print(f"Catalog: {catalog_path.name}")

    if not catalog_path.is_file():
        print(f"\n[ERROR] Put {CATALOG_NAME} beside this script.")
        pause()
        return

    if not character_root.is_dir():
        print("\n[ERROR] Character.wz was not found beside this script.")
        pause()
        return

    if not eqp_strings_path.is_file():
        print("\n[ERROR] String.wz/Eqp.img.xml was not found.")
        pause()
        return

    comments, catalog = load_catalog(catalog_path)
    median_price = category_prices(catalog)
    scanned = cash_found = added = updated = skipped = errors = 0

    folders = ["accessory", "cap", "cape", "coat", "glove",
               "longcoat", "pants", "ring", "shoes", "weapon"]

    try:
        eqp_names = load_eqp_names(eqp_strings_path, folders)
    except RuntimeError as exc:
        print(f"\n[ERROR] {exc}")
        pause()
        return

    print(f"Strings: {eqp_strings_path}")

    for folder_name in folders:
        folder = character_root / folder_name
        if not folder.is_dir():
            print(f"[MISSING] {folder_name}")
            continue

        folder_cash = 0
        for xml_path in folder.rglob("*.xml"):
            scanned += 1
            try:
                root = ET.parse(xml_path).getroot()
            except (ET.ParseError, OSError) as exc:
                errors += 1
                print(f"[XML ERROR] {xml_path.relative_to(base)}: {exc}")
                continue

            if find_value(root, "cash") != "1":
                continue

            item_id = get_item_id(xml_path, root)
            if item_id is None:
                skipped += 1
                print(f"[NO ID] {xml_path.relative_to(base)}")
                continue

            category = get_category(folder_name, item_id)
            if category is None:
                skipped += 1
                print(f"[UNKNOWN ACCESSORY] {item_id} ({xml_path.relative_to(base)})")
                continue

            cash_found += 1
            folder_cash += 1
            gender = find_value(root, "gender")
            if gender not in {"0", "1", "2"}:
                gender = str(DEFAULT_GENDER)

            fallback_name = f"Cash Item {item_id}"
            string_name = eqp_names[folder_name].get(item_id)

            if item_id in catalog:
                row = catalog[item_id]
                # Preserve price, count, period, and gender.
                row[3] = "2"
                row[4] = str(category)
                if string_name:
                    row[7] = string_name
                updated += 1
            else:
                catalog[item_id] = [
                    str(item_id), str(median_price.get(category, DEFAULT_PRICE)),
                    str(DEFAULT_COUNT), "2",
                    str(category), str(DEFAULT_PERIOD), gender,
                    string_name or fallback_name,
                ]
                added += 1

            if not string_name:
                print(f"[NO STRING] {folder_name}/{item_id}")

        print(f"[{folder_name.upper():9}] cash items: {folder_cash}")

    if not comments:
        comments = [
            "# Cash Shop catalogue generated from Character.wz cash equipment.",
            "# itemId\tprice\tcount\ttab\tcategory\tperiod\tgender\tname",
        ]

    with output_path.open("w", encoding="utf-8", newline="") as file:
        for comment in comments:
            file.write(comment + "\n")
        writer = csv.writer(file, delimiter="\t", lineterminator="\n",
                            quoting=csv.QUOTE_NONE, quotechar=None)
        for item_id in sorted(catalog):
            writer.writerow(catalog[item_id])

    print("\n" + "=" * 68)
    print(f"XML files scanned : {scanned}")
    print(f"Cash items found  : {cash_found}")
    print(f"New rows added    : {added}")
    print(f"Rows recategorized: {updated}")
    print(f"Skipped           : {skipped}")
    print(f"XML errors        : {errors}")
    print(f"Output            : {output_path}")
    print("\nOriginal catalog.tsv was not overwritten.")
    pause()


if __name__ == "__main__":
    main()
