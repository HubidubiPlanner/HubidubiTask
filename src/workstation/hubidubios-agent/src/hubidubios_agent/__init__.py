"""hubidubios-agent: natural-language control of the vault.

The model plans narrowly, code executes. One skill call per turn, strict
schema, deterministic code doing the real work — local models degrade badly
on long free-form tool chains.
"""

MAX_TURNS = 5
MAX_SCHEMA_RETRIES = 3
