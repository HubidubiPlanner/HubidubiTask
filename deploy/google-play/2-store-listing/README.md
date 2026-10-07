# 2. The store listing

Paste these into **Grow users › Store presence › Main store listing**.

| Field | Limit | English | Polish |
|---|---|---|---|
| App name | 30 | [`en-US/title.txt`](en-US/title.txt) | [`pl-PL/title.txt`](pl-PL/title.txt) |
| Short description | 80 | [`en-US/short-description.txt`](en-US/short-description.txt) | [`pl-PL/short-description.txt`](pl-PL/short-description.txt) |
| Full description | 4000 | [`en-US/full-description.txt`](en-US/full-description.txt) | [`pl-PL/full-description.txt`](pl-PL/full-description.txt) |

Add Polish under **Manage translations › Add your own translations**. Its
description ends by saying the app's interface is English for now; drop that
line once it is translated.

Play forbids ranking or price claims and repeated keywords in the name and
descriptions ("best", "#1", "free", runs of emoji). These texts avoid them.

## Store settings

| Field | Value |
|---|---|
| App category | Productivity |
| Tags (up to 5) | To-do list, Task management, Calendar, Note taking, Productivity: the closest names Play offers |
| Email | `CONTACT_EMAIL` from `deploy/website/2-site/site.env` (shown publicly) |
| Website | `SITE_URL` from the same file |
| Phone | Leave empty |
