# Publishing the website

The site's source is [`site/`](../../site): Home, Download, Agent and Privacy,
designed together in the design canvas "HubidubiTask Website". Publishing it
takes three steps, one folder each:

| Step | Folder | What it does |
|---|---|---|
| 1 | [`1-downloads/`](1-downloads) | Builds the free Mac app, the Linux package and the Agent, with checksums |
| 2 | [`2-site/`](2-site) | Puts the site and the downloads together, with your address and email from `site.env`, and checks the pages against the files |
| 3 | [`3-publish/`](3-publish) | Uploads it, and what to check afterwards |

```sh
sh deploy/website/1-downloads/build.sh
sh deploy/website/2-site/assemble.sh
sh deploy/website/3-publish/publish.sh
```

The site is live at <https://hubidubitask.github.io/HubidubiTask/>: GitLab Pages
publishes it from main by itself, for now without downloads (the buttons say
"Coming soon"; see [`3-publish/`](3-publish)). The three commands are for
checking it locally or publishing somewhere else.

The screenshots in `site/assets/screens/` are the real apps with made-up data:
the phone ones from `deploy/google-play/3-graphics/shoot.py`, the computer
ones from `sh deploy/website/2-site/desktop-shots.sh`. Make them again when a
screen changes.

`SITE_URL` and `CONTACT_EMAIL` are in [`2-site/site.env`](2-site/site.env).
The Google Play listing uses the same two, and its privacy policy is this
site's `privacy.html`.

The Mac app is free and not signed with an Apple developer certificate, so
macOS asks once before opening it; the Download page walks people through
that. Signing and notarizing it would need an Apple Developer ID.
