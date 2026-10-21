# 2. The store listing

The texts live in [`fastlane/metadata/android/`](../../../fastlane/metadata/android),
where F-Droid reads them. Paste the same ones into **Grow users › Store
presence › Main store listing**.

| Field | Limit | English | Polish |
|---|---|---|---|
| App name | 30 | [`en-US/title.txt`](../../../fastlane/metadata/android/en-US/title.txt) | [`pl-PL/title.txt`](../../../fastlane/metadata/android/pl-PL/title.txt) |
| Short description | 80 | [`en-US/short_description.txt`](../../../fastlane/metadata/android/en-US/short_description.txt) | [`pl-PL/short_description.txt`](../../../fastlane/metadata/android/pl-PL/short_description.txt) |
| Full description | 4000 | [`en-US/full_description.txt`](../../../fastlane/metadata/android/en-US/full_description.txt) | [`pl-PL/full_description.txt`](../../../fastlane/metadata/android/pl-PL/full_description.txt) |

Add Polish under **Manage translations › Add your own translations**. The
Polish description uses the app's Polish names (Nadchodzące, Projekty,
Przegląd z AI), as a Polish phone shows them.

Play forbids ranking or price claims in the app name ("best", "#1",
"free"), and repeated keywords or runs of emoji anywhere. These texts avoid
them; "free and open source" in the description names the licence, not a
price.

## Store settings

| Field | Value |
|---|---|
| App category | Productivity |
| Tags (up to 5) | To-do list, Task management, Calendar, Note taking, Productivity: the closest names Play offers |
| Email | Required by Play and shown publicly; set it in Play Console, it is not kept in this repository |
| Website | `SITE_URL` from `deploy/website/2-site/site.env` |
| Phone | Leave empty |
