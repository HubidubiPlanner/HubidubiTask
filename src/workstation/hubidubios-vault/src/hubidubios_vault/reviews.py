"""Agent reviews, kept beside the vault as plain files (`reviews/<id>.md`).

A review is prose: what the agent made of an item, written once and read back
every time the item is opened. The storage is the shared sidecar mechanism in
sidecars.py; this module keeps the names its callers already use.
"""

from .sidecars import REVIEW

reviews_dir = REVIEW.dir
path_for = REVIEW.path_for
read = REVIEW.read
write = REVIEW.write
remove = REVIEW.remove
attach = REVIEW.attach
