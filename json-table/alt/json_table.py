#!/usr/bin/env python3
"""The same table as the Java json-table, with the Python standard library only (no colours).

Usage: json_table.py FILE      or      cat FILE | json_table.py -
"""
import json
import sys

KNOWN = ("id", "name", "createdAt")


def quote(value):
    return json.dumps(value, ensure_ascii=False)


def pretty(value, indent=""):
    """Pretty JSON in the layout of Jackson's default pretty printer."""
    if isinstance(value, dict):
        if not value:
            return "{ }"
        inner = indent + "  "
        entries = (f"{inner}{quote(key)} : {pretty(item, inner)}" for key, item in value.items())
        return "{\n" + ",\n".join(entries) + "\n" + indent + "}"
    if isinstance(value, list):
        return "[ " + ", ".join(pretty(item, indent) for item in value) + " ]" if value else "[ ]"
    return quote(value)


def text(value):
    if value is None:
        return ""
    return value if isinstance(value, str) else quote(value)


def cell(value):
    return pretty(value) if isinstance(value, (dict, list)) else text(value)


def render(items):
    extra = list(dict.fromkeys(key for item in items for key in item if key not in KNOWN))
    headers = [*KNOWN, *(f'"{key}"' for key in extra)]
    rows = [
        [text(item.get(key)).split("\n") for key in KNOWN] + [cell(item.get(key)).split("\n") for key in extra]
        for item in items
    ]
    widths = [
        max(len(header), max((len(line) for row in rows for line in row[c]), default=0))
        for c, header in enumerate(headers)
    ]
    rule = "─┼─".join("─" * width for width in widths)
    lines = [" │ ".join(header.ljust(width) for header, width in zip(headers, widths)), rule]
    for row in rows:
        for i in range(max(len(column) for column in row)):
            lines.append(" │ ".join(
                (column[i] if i < len(column) else "").ljust(width) for column, width in zip(row, widths)))
        lines.append(rule)
    return "\n".join(lines) + "\n"


def main(args):
    if not args or args[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0
    try:
        if args[0] == "-":
            items = json.load(sys.stdin)
        else:
            with open(args[0], encoding="utf-8") as source:
                items = json.load(source)
        if not isinstance(items, list) or not all(isinstance(item, dict) for item in items):
            raise ValueError("the input is not a JSON array of objects")
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stdout.write(render(items))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
