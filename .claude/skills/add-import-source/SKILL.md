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

Kotlin paths below are under `src/apps/`; `docs/` and the rest are from the
repository root. Work on an up-to-date checkout: if
`src/apps/shared/src/main/kotlin/com/hubidubitask/mobile/importer/ImportSource.kt`
isn't there, pull first.

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
- The examples to copy from, all in `sources/`:
  - `TodoTxtSource.kt`: a file, with the parsing, caps and counting in one object
  - `TodoistSource.kt`: an API with a token
  - `GoogleCalendarSource.kt`: an address
- `ImportWriter` is the only code that writes. It marks every project, task and note it writes with `origin = "import"`, and the user's AI agent is told those words came from another app (`core/agent/ItemsContext.kt`). That's why a source never writes records itself.

## Steps

1. **Learn the format before writing code.**
   - Read the other app's documented export format or API. Write a small sample by hand that follows it exactly, with invented content: never put a real person's data in the repo.
   - Decide the mapping into what HubidubiTask holds:
     - projects, by name
     - tasks: text, `due` as `yyyy-MM-dd`, an optional `body`
     - notes under a task: an `ImportItem` with `parentKey`
     - calendar events
   - What the user would notice missing goes in `lost` with a count, e.g. `t("%d tasks' labels", n)`: priorities, labels, contexts, repeats, attachments. The user should see what was left out, not lose it silently. Words you don't recognise stay in the task's text rather than being dropped.
   - Skip finished items unless the format has nothing else, and say how many with a muted line (`TodoTxtSource` does: "%d done — skipped").

2. **Choose the input.**
   - A file the user exports from the other app is the most private choice: nothing leaves the device. Prefer it when the app offers an export.
   - An API needs a token: use `Secret`, read only, and never keep the token anywhere.
   - `howTo` is the one line under the field that says where to find the value (see Todoist's). Leave it null when the label says enough, because the app keeps hint text out (CONTRIBUTING.md). A file input has no field and no `howTo`.

3. **Write `shared/src/main/kotlin/com/hubidubitask/mobile/importer/sources/<Name>Source.kt`.**
   - Make it an `object <Name>Source : ImportSource`.
   - `id` is lowercase, never shown and never changed.
   - `title` is the app's name as it brands itself, a plain string.
   - `subtitle` and every other shown text is a getter through `t("…")`, e.g. `override val subtitle get() = t("Tasks and projects")`. A getter follows the language the user picks later; a plain `val` would freeze it.
   - Every shown text must be a **literal** `t("…")` call with the English inside. `I18nTest` finds texts by scanning the source for that pattern; `t(someVariable)` hides a text from the check, and it then shows untranslated.
   - Keep parsing in a pure function in the source object, so tests need no network: `fun plan(text: String, existingProjects, existingItems): ImportPlan`, as `TodoTxtSource` has it. Call it from `read`.
   - **Network only through `here.web`**, e.g. `here.web.get(url, mapOf("Authorization" to "Bearer $token")) { r -> … }`. `SafeFetch` allows https only and never reaches the user's home network. A test fails if a source opens its own `OkHttpClient` or `URL` connection.
   - **Skip what is already here.** Compare case-insensitively and trimmed against `here.existingItems` and `here.projectNames`, as `Todoist.plan` does. Count the skipped ones as a muted line: `ImportLine(t("%d already in the app — skipped", n), muted = true)`. A second import of the same file must add nothing.
   - Treat the input as hostile, as `TodoTxtSource` does:
     - re-check `ImportValue.FILE_LIMIT` in `read` (the apps stop reading past it, but the source shouldn't rely on that)
     - refuse what can't be the format: binary content (a NUL byte) in a text format, invalid JSON
     - cap the count of what you read (`TodoTxtSource` takes at most 20,000 lines)
     - never crash: throw an `IOException` with a short literal `t("…")` message; the screen shows it
     - XML: parse only with `SafeXml.parse(text)` (`core/net/SafeXml.kt`). It refuses any DOCTYPE or ENTITY before parsing and hardens the parser as far as each platform allows; Android's parser rejects some of the JDK's settings, so don't set them yourself.

4. **Register it.** Add `<Name>Source` to `ImportSources.all` in `ImportSource.kt`, with its import (the sources live in the `sources` package).

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
   - Write `shared/src/test/kotlin/com/hubidubitask/mobile/importer/<Name>SourceTest.kt`, with fixtures in `shared/src/test/resources/import/<id>/`, read from the classpath: `javaClass.getResource("/import/<id>/sample.txt")!!.readText()`. `TodoTxtSourceTest` is the example. Cover:
     - the mapping (projects, tasks, dates, notes under tasks)
     - what lands in `lost`
     - malformed input
     - that a second run against the result of the first adds nothing: apply the plan with `ImportWriter.apply` into `ImportSourcesTest.Sink()`, build a new `ImportContext` on that sink, and read again
   - `ImportSourcesTest` then checks the registry-wide rules on its own: unique ids, only the fetcher it's handed, imported items marked.

8. **Docs.**
   - In `docs/MANUAL.md`, the import sources are described after the backup paragraph in "## A copy of everything". Add a short paragraph there, like todo.txt's.
   - Add a bullet under `## Unreleased` in `docs/CHANGELOG.md`.
   - If the store texts (`fastlane/metadata/android/*/full_description.txt`) or `site/` list the sources, add it there too: `grep -rn "Todoist" README.md site fastlane docs` shows where.

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
- [ ] Every shown text is a literal `t("…")`, with all four translations. Brand names plain.
- [ ] A second import of the same data adds nothing. What doesn't fit is counted in `lost`.
- [ ] `./gradlew :shared:test :desktop:test :android:assembleDebug` passes.
- [ ] MANUAL and CHANGELOG updated. Commits signed off (`git commit -s`).
