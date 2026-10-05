"""System-prompt assembly.

The available tool set is exactly the installed plugins' skills plus the
built-in vault-query, so it changes as plugins are installed. Skill
descriptions come from SKILL.md; parameter schemas from schema.json.
"""

import json

from .context import VAULT_QUERY_SKILL

_PROTOCOL = """\
You control a personal productivity vault. On every turn you output ONE JSON \
object and nothing else. It is either a tool call:

  {"tool": "<name>", "params": { ... }}

or a final answer to the user:

  {"answer": "<your reply>"}

Rules:
- Exactly one JSON object per turn. No prose outside the JSON. No markdown.
- Call at most one tool per turn. Never invent tools or parameters.
- Prefer vault-query to look things up before answering questions about the \
user's tasks, notes, events, or inbox.
- When you have enough information, return an answer. Do not keep calling tools.
"""


def _skill_catalog(registry) -> list[dict]:
    """[{name, description, schema}] for vault-query + every plugin skill."""
    catalog = [{
        "name": VAULT_QUERY_SKILL["name"],
        "description": VAULT_QUERY_SKILL["description"],
        "schema": VAULT_QUERY_SKILL["schema"],
    }]
    for plugin, skill in registry.all_skills():
        sdir = plugin.dir / "skills" / skill["name"]
        try:
            desc = (sdir / "SKILL.md").read_text(encoding="utf-8").strip()
            schema = json.loads((sdir / "schema.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        catalog.append({
            "name": f"{plugin.name}.{skill['name']}",
            "description": desc,
            "schema": schema,
        })
    return catalog


def build_system_prompt(registry) -> str:
    tools = _skill_catalog(registry)
    lines = [_PROTOCOL, "", "Available tools:"]
    for t in tools:
        lines.append(f"\n## {t['name']}\n{t['description']}")
        lines.append(f"parameters (JSON Schema): {json.dumps(t['schema'])}")
    return "\n".join(lines)


def skill_index(registry) -> dict[str, dict]:
    """name -> {schema, plugin, skill_spec} for dispatch. vault-query is
    represented with plugin=None."""
    idx: dict[str, dict] = {
        VAULT_QUERY_SKILL["name"]: {"schema": VAULT_QUERY_SKILL["schema"],
                                    "plugin": None, "skill": None},
    }
    for plugin, skill in registry.all_skills():
        sdir = plugin.dir / "skills" / skill["name"]
        try:
            schema = json.loads((sdir / "schema.json").read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        idx[f"{plugin.name}.{skill['name']}"] = {
            "schema": schema, "plugin": plugin, "skill": skill}
    return idx
