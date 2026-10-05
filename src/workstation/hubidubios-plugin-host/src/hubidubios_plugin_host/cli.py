"""hubidubios-plugins CLI: inspect plugins, refresh the entity registry, run skills."""

import argparse
import json
import sys

from hubidubios_vault.vault import Vault

from . import registry as reg_mod
from .registry import RegistryError


def cmd_list(vault, args) -> int:
    reg = reg_mod.discover()
    for p in reg.plugins.values():
        m = p.manifest
        parts = []
        if m.get("entities"):
            parts.append("entities: " + ", ".join(e["type"] for e in m["entities"]))
        if m.get("skills"):
            parts.append("skills: " + ", ".join(s["name"] for s in m["skills"]))
        if m.get("views"):
            parts.append("views: " + ", ".join(v["id"] for v in m["views"]))
        print(f"{p.name} {m['version']} (api {m['api']})  [{p.dir}]")
        for part in parts:
            print(f"    {part}")
    for err in reg.errors:
        print(f"ERROR: {err}", file=sys.stderr)
    if not reg.plugins:
        print("no plugins installed")
    return 1 if reg.errors else 0


def cmd_refresh(vault, args) -> int:
    reg = reg_mod.discover()
    for err in reg.errors:
        print(f"ERROR: {err}", file=sys.stderr)
    try:
        path = reg_mod.export_entity_types(vault, reg)
    except RegistryError as e:
        print(f"FATAL: {e}", file=sys.stderr)
        return 1
    types = ", ".join(sorted(reg.entity_types()))
    print(f"wrote {path} ({types})")
    from hubidubios_vault.index import indexer

    conn = indexer.open_index(vault)  # sweep applies the new registry
    conn.close()
    return 1 if reg.errors else 0


def cmd_run_skill(vault, args) -> int:
    from .sandbox import SandboxError, SkillError, run_skill

    reg = reg_mod.discover()
    found = reg.skill(args.plugin, args.skill)
    if not found:
        print(f"no skill {args.plugin}/{args.skill}", file=sys.stderr)
        return 1
    plugin, spec = found
    try:
        params = json.loads(args.params) if args.params else json.load(sys.stdin)
    except ValueError as e:
        print(f"params are not JSON: {e}", file=sys.stderr)
        return 1
    try:
        result = run_skill(vault, plugin, spec, params)
    except (SandboxError, SkillError) as e:
        print(f"skill failed: {e}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2))
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="hubidubios-plugins",
                                description="hubidubios plugin host")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("list", help="installed plugins and their contributions")
    sp.set_defaults(func=cmd_list)

    sp = sub.add_parser("refresh",
                        help="rewrite .hubidubios/entity-types.json from installed plugins")
    sp.set_defaults(func=cmd_refresh)

    sp = sub.add_parser("run-skill", help="invoke one skill (params: JSON arg or stdin)")
    sp.add_argument("plugin")
    sp.add_argument("skill")
    sp.add_argument("params", nargs="?")
    sp.set_defaults(func=cmd_run_skill)

    args = p.parse_args(argv)
    return args.func(Vault.locate(), args)


if __name__ == "__main__":
    sys.exit(main())
