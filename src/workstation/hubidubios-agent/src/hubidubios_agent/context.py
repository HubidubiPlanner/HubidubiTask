"""The vault-query skill: the agent's only window into the vault contents.

Never dump the vault into the prompt. The agent queries the index and
receives ranked snippets capped at a fixed token budget, truncated
oldest-first (older, lower-ranked snippets drop first).

This is a built-in skill — it needs read access to the whole index, which no
sandboxed plugin skill is granted — but it is presented to the model exactly
like any other skill.
"""

from hubidubios_vault.index import queries

# Rough chars-per-token for budgeting; deliberately conservative.
CHARS_PER_TOKEN = 4
DEFAULT_TOKEN_BUDGET = 1500

VAULT_QUERY_SKILL = {
    "name": "vault-query",
    "description": (
        "Search the vault for entities relevant to a query and get back ranked "
        "snippets. Use this to find tasks, notes, events, or inbox items before "
        "answering questions about what the user has. Returns text snippets only."),
    "schema": {
        "type": "object",
        "required": ["query"],
        "additionalProperties": False,
        "properties": {
            "query": {"type": "string", "description": "What to search for"},
            "limit": {"type": "integer", "minimum": 1, "maximum": 50},
        },
    },
}


def _snippet(entity: dict) -> str:
    t = entity["type"]
    if t == "task":
        due = f" (due {entity['due']})" if entity.get("due") else ""
        state = "done" if entity.get("done") else "todo"
        return f"[task/{state}] {entity['text']}{due} — project {entity.get('project','')}"
    if t == "note":
        body = entity.get("body", "")
        head = body[:200] + ("…" if len(body) > 200 else "")
        return f"[note] {entity.get('title','')}: {head}"
    if t == "event":
        return f"[event] {entity.get('summary','')} @ {entity.get('dtstart','')}"
    if t == "inbox_item":
        return f"[inbox] {entity.get('date','')} {entity.get('time','')} {entity.get('text','')}"
    return f"[{t}] {entity.get('id')}"


def _updated(entity: dict) -> str:
    return entity.get("updated", "")


def run_vault_query(index_conn, params: dict,
                    token_budget: int = DEFAULT_TOKEN_BUDGET) -> dict:
    """Execute vault-query. Returns {snippets: [...], truncated: bool}."""
    limit = int(params.get("limit", 20))
    hits = queries.search(index_conn, params["query"], limit=limit)
    # Rank order is best-first; when the budget bites we keep the highest
    # ranked and, among equals, the most recently updated — so oldest and
    # least relevant drop first.
    char_budget = token_budget * CHARS_PER_TOKEN
    snippets: list[str] = []
    used = 0
    truncated = False
    for h in hits:
        s = _snippet(h)
        if used + len(s) > char_budget and snippets:
            truncated = True
            break
        snippets.append(s)
        used += len(s)
    return {"snippets": snippets, "truncated": truncated,
            "count": len(snippets)}
