# 3. Publish

The assembled site is plain files: four pages, a stylesheet, fonts and
images, a few MB. If the site serves the downloads itself, they add about
150 MB each.

## GitHub Pages (where it lives)

The site is at <https://hubidubitask.github.io/HubidubiTask/>, published by
[`.github/workflows/pages.yml`](../../../.github/workflows/pages.yml). It runs
on every push to main that changes `site/`, `deploy/website/`, the version or
the workflow, and by hand from Actions › Pages › Run workflow. In the
repository's Settings › Pages, the source is "GitHub Actions".

GitHub Pages takes no single file over 100 MB, so the downloads stay on the
release page: the Mac and Linux buttons link to
<https://github.com/hubidubitask/HubidubiTask/releases/latest>, and
`assemble.sh` takes no files.

## Other hosts

Any static host with HTTPS. To serve the downloads from the site, link them
from the pages (`downloads/…`), then run step 1 before step 2:
[`../1-downloads/build.sh`](../1-downloads/build.sh) builds them,
[`../1-downloads/fetch-release.sh`](../1-downloads/fetch-release.sh) takes
them from the current version's GitHub release. Check the host's limit on a
single file first:

- **Your own server or a small VPS** (nginx, Caddy): no limit. Upload with
  `publish.sh` after setting `PUBLISH_TARGET` in `../2-site/site.env`.
- **Static-site services** often cap single files below the downloads' size
  (Cloudflare Pages at 25 MiB, GitHub Pages at 100 MB, for example). Host the
  pages there and the downloads where large files are allowed (your server or
  an object-storage bucket), and set `DOWNLOADS_URL` to that place before
  step 2. The three files and `SHA256SUMS` from `../1-downloads/out/` go
  there.

## After publishing

1. Open `SITE_URL`: all four pages, light and dark (switch the system theme).
2. If the site serves the downloads, download each file once and compare it
   with `SHA256SUMS`: `shasum -a 256 -c SHA256SUMS`.
3. Paste into Play Console: the privacy policy URL (`SITE_URL/privacy.html`,
   Google Play step 4) and the website (step 2).

The Google Play buttons link to the app's Play page, which works once the app
is published there.
