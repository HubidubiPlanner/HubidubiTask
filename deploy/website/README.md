# Publishing the website

The site's source is [`site/`](../../site): Home, Download, Agent and Privacy,
designed together in the design canvas "HubidubiTask Website". Publishing it
takes three steps, one folder each:

| Step | Folder | What it does |
|---|---|---|
| 1 | [`1-downloads/`](1-downloads) | Builds a release's files (Mac app, Arch package, AppImage, Android APK) with signed checksums, and `release.sh` publishes them as the GitHub release; `fetch-release.sh` takes them back from there for a host that serves the downloads itself |
| 2 | [`2-site/`](2-site) | Puts the site and any downloads together, with the address and contact link from `site.env`, and checks the pages against the files |
| 3 | [`3-publish/`](3-publish) | Uploads it, and what to check afterwards |

```sh
sh deploy/website/1-downloads/build.sh
sh deploy/website/2-site/assemble.sh
sh deploy/website/3-publish/publish.sh
```

The site is at <https://hubidubiplanner.github.io/HubidubiTask/>: GitHub Pages
publishes it from main by itself
([`.github/workflows/pages.yml`](../../.github/workflows/pages.yml)). The Mac
and Linux buttons link to the latest GitHub release, so the site carries no
downloads (see [`3-publish/`](3-publish)). The three commands are for
checking it locally or publishing somewhere else.

A release: tag the version (`git tag -s v<version>`) and push the tag,
then `sh deploy/website/1-downloads/build.sh --rebuild` and
`sh deploy/website/1-downloads/release.sh`. The site's download buttons and
F-Droid take the files from that release.

The screenshots in `site/assets/screens/` are the real apps with made-up data:
the phone ones from `deploy/google-play/3-graphics/shoot.py`, the computer
ones from `sh deploy/website/2-site/desktop-shots.sh`. Make them again when a
screen changes.

`SITE_URL` and `CONTACT_URL` are in [`2-site/site.env`](2-site/site.env).
The Google Play listing uses `SITE_URL` as its website, and its privacy
policy is this site's `privacy.html`.

The Mac app is free and not signed with an Apple developer certificate, so
macOS asks once before opening it. The Download page has no install steps;
signing and notarizing the app would need an Apple Developer ID.
