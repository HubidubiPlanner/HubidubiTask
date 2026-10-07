# 3. Publish

The assembled site is plain files: four pages, a stylesheet, fonts, images
and, unless `DOWNLOADS_URL` is set, the three downloads. About 290 MB, almost
all of it the Mac and Linux downloads (about 150 MB each).

## Where to host it

Any static host with HTTPS. Check its limit on a single file first:

- **Your own server or a small VPS** (nginx, Caddy): no limit. Upload with
  `publish.sh` after setting `PUBLISH_TARGET` in `../2-site/site.env`.
- **Static-site services** often cap single files below the downloads' size
  (Cloudflare Pages at 25 MiB, GitHub Pages at 100 MB, for example). Host the
  pages there and the downloads where large files are allowed (your server,
  an object-storage bucket, or the release assets of a public repository),
  and set `DOWNLOADS_URL` to that place before step 2. The three files and
  `SHA256SUMS` from `../1-downloads/out/` go there.

The repository's GitLab project is private, so its release files are not a
place the public can download from.

## After publishing

1. Open `SITE_URL`: all four pages, light and dark (switch the system theme).
2. Download each file once and compare it with `SHA256SUMS`:
   `shasum -a 256 -c SHA256SUMS`.
3. On a Mac, open the downloaded app and follow the page's first-start steps.
4. Paste into Play Console: the privacy policy URL (`SITE_URL/privacy.html`,
   Google Play step 4), the website and the contact email (step 2).

The Google Play buttons link to the app's Play page, which works once the app
is published there.
