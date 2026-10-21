#!/usr/bin/env python3
"""Add (or replace) translations in the four tables the apps speak, each at its place.

    python3 src/apps/tools/i18n_add.py new-texts.json

new-texts.json maps each English text, exactly as written in t("…"), to its
Polish, German, French and Spanish:

    {"Watch": {"pl": "Obserwuj", "de": "Beobachten", "fr": "Suivre", "es": "Seguir"}}

Keep placeholders (%s, %d) and their order. The tables are sorted by the
English, ignoring case; I18nTest fails while any t("…") has no entry.
"""
import json, os, re, sys

TABLES = {"pl": "Polish", "de": "German", "fr": "French", "es": "Spanish"}
DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "shared/src/main/kotlin/com/hubidubitask/core/i18n")
ENTRY = re.compile(r'^    "((?:[^"\\]|\\.)*)" to .*$')


def quote(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main(path):
    spec = json.load(open(path, encoding="utf-8"))
    for key, tr in spec.items():
        missing = [c for c in TABLES if not tr.get(c)]
        if missing:
            sys.exit(f"{key!r} has no {', '.join(missing)}")
        for ph in re.findall(r"%[sd]", key):
            for c in TABLES:
                if re.findall(r"%[sd]", tr[c]) != re.findall(r"%[sd]", key):
                    sys.exit(f"{key!r}: the {c} text must keep the placeholders {re.findall(r'%[sd]', key)} in order")
    for code, name in TABLES.items():
        p = os.path.join(DIR, name + ".kt")
        lines = open(p, encoding="utf-8").read().split("\n")
        for key, tr in spec.items():
            new = f"    {quote(key)} to {quote(tr[code])},"
            idx = [i for i, l in enumerate(lines) if ENTRY.match(l)]
            same = [i for i in idx if ENTRY.match(lines[i]).group(1) == quote(key)[1:-1]]
            if same:
                lines[same[0]] = new
                continue
            at = next((i for i in idx if ENTRY.match(lines[i]).group(1).lower() > key.lower()), idx[-1] + 1)
            lines.insert(at, new)
        open(p, "w", encoding="utf-8").write("\n".join(lines))
    print(f"added {len(spec)} text(s) to {', '.join(TABLES.values())}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
