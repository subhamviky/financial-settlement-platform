"""Validate event samples against the settlement event contract.

Usage: python contracts/tools/validate_samples.py <samples.json> [...]
A samples file holds either one event or a list of feed items ({"sequence", "event"}).
Exit code 1 if any event violates the schema.
"""
import json
import sys
from pathlib import Path

import jsonschema

SCHEMA = Path(__file__).resolve().parents[1] / "events" / "settlement-event.v1.schema.json"


def events_in(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    items = data if isinstance(data, list) else [data]
    return [item.get("event", item) for item in items]


def main(paths) -> int:
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    validator = jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())
    failures, count, types = 0, 0, set()
    for path in map(Path, paths):
        for event in events_in(path):
            count += 1
            types.add(event.get("event_type"))
            for error in validator.iter_errors(event):
                failures += 1
                print(f"{path}: {event.get('event_id')}: {error.message}")
    print(f"{count} events checked, {failures} violations, types: {sorted(t for t in types if t)}")
    return 1 if failures or count == 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))