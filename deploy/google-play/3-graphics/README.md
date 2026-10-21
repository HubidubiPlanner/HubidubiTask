# 3. Graphics

The graphics live in
[`fastlane/metadata/android/en-US/images/`](../../../fastlane/metadata/android/en-US/images),
where F-Droid reads them; upload the same files in Play Console. The Polish
listing uses the same ones.

| File | Play's field | Play's rule |
|---|---|---|
| `icon.png` | App icon | 512 × 512, 32-bit PNG |
| `featureGraphic.jpg` | Feature graphic | 1024 × 500, no transparency |
| `phoneScreenshots/*.jpg` | Phone screenshots | 2 to 8, 1080 × 2160 (2:1), in file order |

Tablet screenshots are optional; skip them, the app is made for phones.

## The screenshots

The real app, light theme, with made-up demo data and a clean status bar. The
demo data carries two reviews, kept up to date, so a summary shows under a title:

1. `1-next-day.jpg`: Incoming, where the app opens: one overdue task, today's tasks and a stand-up
2. `2-new.jpg`: the + sheet: project, task, note, photo, gallery, voice memo, file
3. `3-projects.jpg`: Projects, two of them open, a note filed under a task
4. `4-item.jpg`: an opened task: its due date, the review under its title, its note, Mark done
5. `5-review-with-ai.jpg`: a long press on a project: its summary under the title, Review with AI
6. `6-feeds.jpg`: three watched web pages
7. `7-next-week.jpg`: Incoming, Week view, grouped by day
8. `8-settings-ai.jpg`: Settings: the AI agent and what it needs, Pair device, the due date new tasks and notes get

## Making them again

```sh
sh deploy/google-play/3-graphics/render.sh     # icon and feature graphic, from the SVGs
python3 deploy/google-play/3-graphics/shoot.py # all screenshots, from a running emulator
```

`render.sh` draws `icon.svg` and `feature-graphic.svg`, next to it, and
needs `rsvg-convert` (`brew install librsvg`); it also refreshes the
website's link preview image. `shoot.py` needs a debug build
(`cd src/apps && ./gradlew :android:assembleDebug`, only debug builds carry
the demo data) and a running emulator. It installs the app fresh, seeds the
demo data, taps through the app by the words on screen, and also writes the
website's light and dark screenshots to `site/assets/screens/`.
