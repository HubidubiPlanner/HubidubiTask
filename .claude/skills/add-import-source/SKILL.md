---
name: add-import-source
description: Add a new import source to HubidubiTask, a way to bring a user's tasks, projects, notes or calendar events in once from another app (an export file such as todo.txt, JSON or CSV, or an app's API with a token, or a web address). Use this whenever someone wants HubidubiTask to import from, migrate from or read the data of another app (TickTick, Things, Microsoft To Do, Google Tasks, Notion, Tasks.org, Trello, Obsidian, todo.txt and so on), even if they only name the app and never say "import source".
---

# Add an import source

An import source reads another app's data once and proposes what would
come in. The user confirms, then the app writes it. Sources live in one
registry, so adding one is one Kotlin file, one registry line,
translations and a test. Both the phone and the computer show it, with no
UI code to touch.

All paths below are under `src/apps/`.

## How the pieces fit

- `shared/src/main/kotlin/com/hubidubitask/mobile/importer/ImportSource.kt`:
  - **`ImportSource`**, the interface you implement:
    - `id`, `title`, `subtitle`, `icon`, `input`
    - `read(value, here): ImportPlan`
    - optional `error(e)`
  - **`ImportInput`**, what the user gives:
    - `Secret(label, howTo)`: a token, typed hidden
    - `Address(label, howTo)`: a web address
    - `File(mimeTypes, extensions)`: a file they pick, at most `ImportValue.FILE_LIMIT`
  - **`ImportValue`**: `Text(text)` or `File(name, bytes)`, which has a `.text` helper (UTF-8, BOM dropped).
  - **`ImportContext`**, as `here`:
    - `here.web`: the only way out to the network, a `SafeFetch`
    - `here.projectNames`
    - `here.existingItems`: (project name or null, text) → id
    - `here.has(id)`
    - `here.today` and `here.zone`
  - **`ImportPlan`**, what you return:
    - `newProjects`: names
    - `items`: `ImportItem`s, where parents come before the notes under them
    - `events`: `Ics.Occurrence`s, plus a `calendar` name
    - `lines`: the summary
    - `lost`: what has no place in the app, shown as "not carried over"
  - **`ImportSources.all`**: the registry. The order is the order in the list, and the HubidubiTask backup always follows the sources.
- `sources/TodoistSource.kt` (an API with a token) and `sources/GoogleCalendarSource.kt` (an address) are the two examples to copy from.
- `ImportWriter` is the only code that writes. It marks every project, task and note it writes with `origin = "import"`, and the user's AI agent is told those words came from another app (`core/agent/ItemsContext.kt`). That's why a source never writes records itself.

## Steps

1. **Learn the format before writing code.**
   - Find the other app's documented export format or API, and get a real sample. Keep it small and invent the content: never put a real person's data in the repo.
   - Decide the mapping into what HubidubiTask holds:
     - projects, by name
     - tasks: text, `due` as `yyyy-MM-dd`, an optional `body`
     - notes under a task: an `ImportItem` with `parentKey`
     - calendar events
   - Everything else goes in `lost` with a count, e.g. `t("%d tasks' labels", n)`. The user should see what was left out, not lose it silently.
   - Skip finished items unless the format has nothing else.

2. **Choose the input.**
   - A file the user exports from the other app is the most private choice: nothing leaves the device. Prefer it when the app offers an export.
   - An API needs a token: use `Secret`, read only, and never keep the token anywhere.
   - `howTo` is the one line under the field that says where to find the value (see Todoist's). Leave it null when the label says enough, because the app's owner dislikes helper text. A file input has no field and no `howTo`.

3. **Write `shared/src/main/kotlin/com/hubidubitask/mobile/importer/sources/<Name>Source.kt`.**
   - Make it an `object <Name>Source : ImportSource`.
   - `id` is lowercase, never shown and never changed.
   - `title` is the app's name as it brands itself, a plain string.
   - `subtitle` and every other shown text is a getter through `t("…")`, e.g. `override val subtitle get() = t("Tasks and projects")`. A getter follows the language the user picks later; a plain `val` would freeze it.
   - Keep parsing in a pure function, so tests need no network: `fun plan(text: String, …): ImportPlan`. Call it from `read`.
   - **Network only through `here.web`**, e.g. `here.web.get(url, mapOf("Authorization" to "Bearer $token")) { r -> … }`. `SafeFetch` allows https only and never reaches the user's home network. A test fails if a source opens its own `OkHttpClient` or `URL` connection.
   - **Skip what is already here.** Compare case-insensitively and trimmed against `here.existingItems` and `here.projectNames`, as `Todoist.plan` does. Count the skipped ones as a muted line: `ImportLine(t("%d already in the app — skipped", n), muted = true)`. A second import of the same file must add nothing.
   - Treat the input as hostile:
     - cap what you read
     - don't let a huge or malformed file crash the app; throw an `IOException` with a short `t("…")` message instead
     - an XML parser must have DOCTYPE and external entities off (see the feed skill's notes on XXE)

4. **Register it.** Add `<Name>Source` to `ImportSources.all` in `ImportSource.kt`.

5. **Icon.**
   - Reuse `ImportIcon.TASKS`, `CALENDAR` or `FILE` when one fits.
   - A new icon is a new enum value plus one line in `ImportIcon.vector()` in **both**:
     - `android/src/main/kotlin/com/hubidubitask/mobile/ui/ImportScreen.kt`
     - `desktop/src/main/kotlin/com/hubidubitask/desktop/ui/ImportScreen.kt`

6. **Translations.** Every new `t("…")` text needs Polish, German, French and Spanish.
   - Write them to a JSON file, then run `python3 src/apps/tools/i18n_add.py that.json` from the repo root. It places each one in sorted order in `core/i18n/{Polish,German,French,Spanish}.kt`.
   - Keep `%s`/`%d` in order, and write natural sentences in the app's plain voice.
   - Brand names stay untranslated and never go through `t()`.

7. **Tests.**
   - Write `shared/src/test/kotlin/com/hubidubitask/mobile/importer/<Name>SourceTest.kt`, with fixtures in `shared/src/test/resources/import/<id>/`. Cover:
     - the mapping (projects, tasks, dates, notes under tasks)
     - what lands in `lost`
     - malformed input
     - that a second run against the result of the first adds nothing: apply the plan with `ImportWriter.apply` into `ImportSourcesTest.Sink()`, build a new `ImportContext` on that sink, and read again
   - `ImportSourcesTest` then checks the registry-wide rules on its own: unique ids, only the fetcher it's handed, imported items marked.

8. **Docs.** Add a line to `docs/MANUAL.md` where Import is described, and a bullet under `## Unreleased` in `docs/CHANGELOG.md`.

9. **Run the checks** from `src/apps`:
   ```
   ./gradlew :shared:test :desktop:test :android:assembleDebug
   ```
   All must pass. If an Android emulator is running, install
   `android/build/outputs/apk/debug/android-debug.apk` and open Settings ›
   Import to see the new row. Try it with the fixture: `adb push` it to
   `/sdcard/Download/`.

## Done when

- [ ] One new file in `importer/sources/`, one line in `ImportSources.all`, and an icon mapping only if a new icon was needed.
- [ ] Network only through `here.web`. Nothing written by the source. No token or file kept.
- [ ] Every shown text through `t()`, with all four translations. Brand names plain.
- [ ] A second import of the same data adds nothing. What doesn't fit is counted in `lost`.
- [ ] `./gradlew :shared:test :desktop:test :android:assembleDebug` passes.
- [ ] MANUAL and CHANGELOG updated. Commits signed off (`git commit -s`).
