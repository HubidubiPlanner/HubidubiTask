"""prodos-agent CLI: `ask` one-shot and `chat` TUI.

These are also exposed as `prodos ask` / `prodos chat` via the agent being
installed alongside the vault CLI; here they stand alone.
"""

import argparse
import sys

from prodos_vault.vault import Vault

from .client import DEFAULT_ENDPOINT, LlamaClient


def build_agent(vault: Vault, endpoint: str, client=None):
    from prodos_plugin_host.registry import discover
    from prodos_vault.index import indexer

    from .loop import Agent

    registry = discover()
    conn = indexer.open_index(vault)
    if client is None:
        client = LlamaClient(endpoint=endpoint)
    return Agent(vault, conn, registry, client), conn


def _print_event(e: dict) -> None:
    if e["event"] == "tool_call":
        print(f"  → {e['tool']}({e['params']})", file=sys.stderr)
    elif e["event"] == "tool_result" and e["status"] == "error":
        print(f"  ! {e['tool']} failed: {e['result'].get('error')}", file=sys.stderr)


def cmd_ask(vault: Vault, args) -> int:
    agent, conn = build_agent(vault, args.endpoint)
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
    agent, conn = build_agent(vault, args.endpoint)
    print("prodos chat — Ctrl-D to exit")
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
    p.add_argument("--endpoint", default=DEFAULT_ENDPOINT,
                   help="llama.cpp OpenAI-compatible endpoint")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("ask", help="one-shot question")
    sp.add_argument("query", nargs="+")
    sp.set_defaults(func=cmd_ask)

    sp = sub.add_parser("chat", help="interactive TUI")
    sp.set_defaults(func=cmd_chat)

    args = p.parse_args(argv)
    return args.func(Vault.locate(), args)


if __name__ == "__main__":
    sys.exit(main())
