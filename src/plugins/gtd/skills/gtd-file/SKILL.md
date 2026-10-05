# gtd-file

Commit a confirmed clarification: create the target entity and remove the
inbox item.

Use only after the user has confirmed a classification (from gtd-clarify or by
hand). This is the commit step gtd-clarify deliberately never performs. Given
the inbox item id and a target kind (next_action, project, waiting_for,
someday, or trash), it writes the new entity and deletes the original inbox
line. Deterministic; no model.
