"""hubidubios-mcp: the vault's skills as MCP tools, over stdio.

The Claude Code harness (hubidubios-harness) starts this server so the model
can use the same skills the workstation agent has: vault-query and every
installed plugin skill (gtd-next, agenda, capture, gtd-file, ...). Each call
is checked against the skill's JSON Schema and run in the same bubblewrap
sandbox as the agent's own calls; nothing here gives wider access.

The protocol is MCP's JSON-RPC 2.0, one message per line on stdin/stdout:
initialize, tools/list, tools/call and ping. Written against the protocol
directly so the workstation needs no extra package for it.
"""

from __future__ import annotations

import json
import re
import sys
from typing import Callable, TextIO

import jsonschema

from .context import run_vault_query
from .prompt import _skill_catalog, skill_index

PROTOCOL_VERSION = "2025-06-18"
SERVER = {"name": "hubidubios", "version": "0.1.0"}
INSTRUCTIONS = (
    "Tools over the user's HubidubiOS vault: their tasks, projects, notes, inbox and "
    "calendar. Look things up with vault-query before answering about them. Tools that "
    "change the vault (capture, task-add, gtd-file, gtd-archive) act at once; use them "
    "only when the user asked for that change.")

# MCP tool names allow letters, digits, _ and - only.
_NAME_OK = re.compile(r"[^A-Za-z0-9_-]")


def _mcp_name(qualified: str, taken: set[str]) -> str:
    """"gtd.gtd-next" -> "gtd-next"; the plugin prefix comes back only on a clash."""
    short = _NAME_OK.sub("-", qualified.split(".", 1)[-1])
    name = short if short not in taken else _NAME_OK.sub("-", qualified.replace(".", "_"))
    taken.add(name)
    return name[:64]


class Server:
    def __init__(self, vault, registry, index_conn,
                 sandbox_runner: Callable | None = None):
        if sandbox_runner is None:
            from hubidubios_plugin_host.sandbox import run_skill
            sandbox_runner = run_skill
        self.vault = vault
        self.conn = index_conn
        self.run_skill = sandbox_runner
        index = skill_index(registry)
        taken: set[str] = set()
        self.tools: dict[str, dict] = {}
        for entry in _skill_catalog(registry):
            name = _mcp_name(entry["name"], taken)
            self.tools[name] = {"qualified": entry["name"], "description": entry["description"],
                                "schema": entry["schema"], "dispatch": index[entry["name"]]}

    # ---- JSON-RPC

    def handle(self, msg: dict) -> dict | None:
        """One request in, one response out; notifications get None."""
        mid, method = msg.get("id"), msg.get("method")
        if mid is None:
            return None   # notifications/initialized and the like
        try:
            if method == "initialize":
                asked = (msg.get("params") or {}).get("protocolVersion") or PROTOCOL_VERSION
                result = {"protocolVersion": asked, "serverInfo": SERVER,
                          "capabilities": {"tools": {"listChanged": False}},
                          "instructions": INSTRUCTIONS}
            elif method == "ping":
                result = {}
            elif method == "tools/list":
                result = {"tools": [{"name": n, "description": t["description"],
                                     "inputSchema": t["schema"]} for n, t in self.tools.items()]}
            elif method == "tools/call":
                p = msg.get("params") or {}
                result = self.call(p.get("name", ""), p.get("arguments") or {})
            else:
                return {"jsonrpc": "2.0", "id": mid,
                        "error": {"code": -32601, "message": f"unknown method {method}"}}
        except Exception as e:   # a broken request must not end the session
            return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32603, "message": str(e)}}
        return {"jsonrpc": "2.0", "id": mid, "result": result}

    def call(self, name: str, args: dict) -> dict:
        """A tools/call result. Failures come back as isError content, as MCP asks,
        so the model can read what went wrong and try again."""
        tool = self.tools.get(name)
        if tool is None:
            return _content(f"No tool named {name}.", error=True)
        try:
            jsonschema.validate(args, tool["schema"])
        except jsonschema.ValidationError as e:
            return _content(f"Invalid arguments for {name}: {e.message}", error=True)
        d = tool["dispatch"]
        try:
            out = (run_vault_query(self.conn, args) if d["plugin"] is None
                   else self.run_skill(self.vault, d["plugin"], d["skill"], args))
        except Exception as e:
            return _content(f"{name} failed: {e}", error=True)
        return _content(json.dumps(out, ensure_ascii=False))

    def serve(self, inp: TextIO, out: TextIO) -> None:
        for line in inp:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                reply = {"jsonrpc": "2.0", "id": None,
                         "error": {"code": -32700, "message": "parse error"}}
            else:
                reply = self.handle(msg) if isinstance(msg, dict) else None
            if reply is not None:
                out.write(json.dumps(reply, ensure_ascii=False) + "\n")
                out.flush()


def _content(text: str, error: bool = False) -> dict:
    return {"content": [{"type": "text", "text": text}], "isError": error}


def main(argv: list[str] | None = None) -> int:
    from hubidubios_plugin_host.registry import discover
    from hubidubios_vault.index import indexer
    from hubidubios_vault.vault import Vault

    vault = Vault.locate()
    conn = indexer.open_index(vault)
    try:
        Server(vault, discover(), conn).serve(sys.stdin, sys.stdout)
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
