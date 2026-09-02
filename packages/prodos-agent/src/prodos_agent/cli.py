"""prodos-agent CLI: `status`, `ask` one-shot, and `chat` TUI.

The endpoint is resolved from $PRODOS_MODEL_ENDPOINT, then the vault's
[inference] config, then the local selfhostedai-agent default (see config.resolve).
`--endpoint` overrides all of them.
"""

import argparse
import json
import sys

from prodos_vault.vault import Vault

from . import config as agent_config


def _client(vault: Vault, args):
    overrides = {}
    if getattr(args, "endpoint", None):
        overrides["endpoint"] = args.endpoint
    return agent_config.client_for(vault, **overrides)


def build_agent(vault: Vault, args, client=None):
    from prodos_plugin_host.registry import discover
    from prodos_vault.index import indexer

    from .loop import Agent

    registry = discover()
    conn = indexer.open_index(vault)
    if client is None:
        client = _client(vault, args)
    return Agent(vault, conn, registry, client), conn, client


def _print_event(e: dict) -> None:
    if e["event"] == "tool_call":
        print(f"  → {e['tool']}({e['params']})", file=sys.stderr)
    elif e["event"] == "tool_result" and e["status"] == "error":
        print(f"  ! {e['tool']} failed: {e['result'].get('error')}", file=sys.stderr)


def cmd_status(vault: Vault, args) -> int:
    health = _client(vault, args).health()
    if args.json:
        print(json.dumps(health))
        return 0 if health["connected"] else 1
    if health["connected"]:
        models = ", ".join(health["models"]) or "(none advertised)"
        print(f"● connected  {health['server']}  models: {models}")
        return 0
    print(f"○ disconnected  {health['server']}  ({health.get('error', 'unreachable')})")
    return 1


def _connection_banner(client) -> None:
    h = client.health()
    if not h["connected"]:
        print(f"○ inference offline ({h['server']}): {h.get('error', 'unreachable')}",
              file=sys.stderr)


def cmd_ask(vault: Vault, args) -> int:
    agent, conn, client = build_agent(vault, args)
    _connection_banner(client)
    try:
        for e in agent.run_streamed(" ".join(args.query)):
            if e["event"] == "answer":
                print(e["answer"])
            else:
                _print_event(e)
    finally:
        conn.close()
    return 0


def cmd_chat(vault: Vault, args) -> int:
    agent, conn, client = build_agent(vault, args)
    print("prodos chat — Ctrl-D to exit")
    _connection_banner(client)
    try:
        while True:
            try:
                line = input("\n> ").strip()
            except EOFError:
                print()
                break
            if not line:
                continue
            for e in agent.run_streamed(line):
                if e["event"] == "answer":
                    print(e["answer"])
                else:
                    _print_event(e)
    finally:
        conn.close()
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="prodos-agent",
                                description="natural-language vault control")
    p.add_argument("--endpoint", default=None,
                   help="OpenAI-compatible endpoint (overrides config/env)")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("status", help="inference connection status")
    sp.add_argument("--json", action="store_true")
    sp.set_defaults(func=cmd_status)

    sp = sub.add_parser("ask", help="one-shot question")
    sp.add_argument("query", nargs="+")
    sp.set_defaults(func=cmd_ask)

    sp = sub.add_parser("chat", help="interactive TUI")
    sp.set_defaults(func=cmd_chat)

    args = p.parse_args(argv)
    return args.func(Vault.locate(), args)


if __name__ == "__main__":
    sys.exit(main())
