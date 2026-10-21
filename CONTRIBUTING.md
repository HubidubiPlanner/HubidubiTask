# Contributing

HubidubiTask is one Gradle build in `src/apps`: `android/` the phone app,
`desktop/` the macOS and Linux app, `shared/` what both use.

## Build and test

You need JDK 17 and an Android SDK (`ANDROID_HOME`, or `sdk.dir` in
`src/apps/local.properties`).

```sh
cd src/apps
./gradlew :shared:test :desktop:test :android:assembleDebug
```

This is what to run before you open a pull request; CI runs the tests on
every push. Without an Android SDK, `-Phubidubitask.desktopOnly=true` leaves
the phone app out.

The computer app starts with `./gradlew :desktop:run`. It runs on, and its
packages bundle, a JDK 27 or newer (for post-quantum TLS): on a Mac
`brew install openjdk`, elsewhere point `HUBIDUBITASK_RUNTIME_JDK` at one. The Linux packages are built in containers
by `distribution/arch/build.sh` and `distribution/appimage/build.sh` (they
need Docker).

## Sign off your commits

Every commit in a pull request needs a `Signed-off-by:` line with your name
(or the name you publish under) and an email:

```sh
git commit -s
```

By signing off you certify the Developer Certificate of Origin
(<https://developercertificate.org>): you wrote the change, or have the right
to submit it under the project's licence. A pull request with commits that
are not signed off can't be merged.

## House rules

- **Every text in the UI goes through `t("…")`**, with its Polish, German,
  French and Spanish translations in
  `src/apps/shared/src/main/kotlin/com/hubidubitask/core/i18n/`. Keep those
  tables sorted by their English key, case-insensitively. `I18nTest` checks
  that they are complete.
- **No hint or helper text in the UI.** No placeholder explanations, no lines
  that tell people how to use a screen.
- **The phone and the computer mirror each other.** A screen changed on one
  changes on the other in the same pull request.
- **Never weaken the security design:**
  - pairing (the code as a J-PAKE password, mixed with ML-KEM-768) and the
    sealed sync, with fresh keys every round;
  - the TLS policy in `Agent.http`: a server on the internet needs a
    certificate an authority vouches for, a server at home has its own
    certificate pinned on first use, plain HTTP goes only to home-network
    addresses;
  - web pages and calendar links are fetched only through `SafeFetch`
    (HTTPS only, never the home network);
  - text from outside (web pages, imports, calendars) is fenced as data, never
    instructions, whenever it goes to an AI;
  - feeds only ever go to Claude, never to the user's own agent, which can run
    commands.

## Adding an import source, a feed kind or an AI agent type

Three Claude Code skills in `.claude/skills/` walk through these:
`add-import-source`, `add-feed-kind` and `add-agent-type`. Without Claude
Code, follow the steps in each skill's `SKILL.md` by hand; they are plain
Markdown.

## Pull requests

- One topic per pull request, with tests for what changes.
- Bugs and ideas go to the issues:
  <https://github.com/hubidubitask/HubidubiTask/issues>.
- Security problems never go to a public issue; see [SECURITY.md](SECURITY.md).

## Licence

HubidubiTask is under the GNU General Public License, version 3 or later
([LICENSE](LICENSE)). What you contribute is under the same licence.
