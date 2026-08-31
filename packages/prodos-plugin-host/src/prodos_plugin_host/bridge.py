"""The host side of the view bridge.

Plugin views run in isolated iframes and reach data only through postMessage;
every request lands here and is validated against the view's declared
`permissions` before any data is touched. Violations are rejected AND logged
— never silently answered with empty results, which would hide broken
manifests from plugin authors.

The same code path serves desktop and mobile hosts (Phase 5): the transport
differs, the permission model does not.
"""

import json
import logging

from prodos_vault import apply as vault_apply
from prodos_vault import ids
from prodos_vault.entities import now_rfc3339
from prodos_vault.index import indexer, queries

log = logging.getLogger("prodos.bridge")


class Bridge:
    def __init__(self, vault, index_conn, plugin_name: str, view: dict):
        self.vault = vault
        self.conn = index_conn
        self.plugin = plugin_name
        self.view_id = view["id"]
        self.can_read = set(view["permissions"].get("read", []))
        self.can_write = set(view["permissions"].get("write", []))

    def _deny(self, op: str, etype: str) -> dict:
        log.warning("view %s/%s denied: op=%s type=%s not in declared permissions",
                    self.plugin, self.view_id, op, etype)
        return {"error": "permission_denied", "op": op, "type": etype}

    def handle(self, request: dict) -> dict:
        op = request.get("op")
        handler = {"list": self._list, "get": self._get, "search": self._search,
                   "upsert": self._upsert, "delete": self._delete}.get(op)
        if not handler:
            return {"error": "unknown_op", "op": op}
        try:
            return handler(request)
        except (KeyError, ValueError, TypeError) as e:
            return {"error": "bad_request", "detail": str(e)}

    # ------------------------------------------------------------- reads

    def _list(self, req: dict) -> dict:
        etype = req["type"]
        if etype not in self.can_read:
            return self._deny("list", etype)
        rows = self.conn.execute(
            "SELECT data FROM entities WHERE type = ? ORDER BY updated DESC LIMIT ?",
            (etype, int(req.get("limit", 200)))).fetchall()
        return {"entities": [json.loads(r["data"]) for r in rows]}

    def _get(self, req: dict) -> dict:
        row = self.conn.execute(
            "SELECT type, data FROM entities WHERE id = ?", (req["id"],)).fetchone()
        if not row:
            return {"entity": None}
        if row["type"] not in self.can_read:
            return self._deny("get", row["type"])
        return {"entity": json.loads(row["data"])}

    def _search(self, req: dict) -> dict:
        hits = queries.search(self.conn, req["q"], limit=int(req.get("limit", 20)))
        readable = [h for h in hits if h["type"] in self.can_read]
        return {"entities": readable}

    # ------------------------------------------------------------ writes

    def _upsert(self, req: dict) -> dict:
        etype = req["type"]
        if etype not in self.can_write:
            return self._deny("upsert", etype)
        d = dict(req["data"])
        if not d.get("id"):
            if etype not in ids.PREFIXES:
                return {"error": "bad_request",
                        "detail": f"cannot mint ids for type {etype}"}
            d["id"] = ids.new_id(etype)
        d["updated"] = now_rfc3339()  # the host stamps time, not the view
        row = self.conn.execute(
            "SELECT file FROM entities WHERE id = ?", (d["id"],)).fetchone()
        path = vault_apply.upsert(self.vault, etype, d,
                                  row["file"] if row else None)
        indexer.index_file(self.conn, self.vault, path)
        return {"entity": {"id": d["id"], "updated": d["updated"]}}

    def _delete(self, req: dict) -> dict:
        etype = req["type"]
        if etype not in self.can_write:
            return self._deny("delete", etype)
        row = self.conn.execute(
            "SELECT file, type FROM entities WHERE id = ?", (req["id"],)).fetchone()
        if not row:
            return {"deleted": False}
        if row["type"] != etype:
            return self._deny("delete", row["type"])
        path = vault_apply.delete(self.vault, etype, req["id"], row["file"])
        if path:
            indexer.index_file(self.conn, self.vault, path)
        return {"deleted": True}
