# HubidubiTask website

Plain static HTML and CSS: no build step, no framework, no third-party
requests. Designed page by page in the design canvas "HubidubiTask Website";
publish it with [`deploy/website`](../deploy/website).

```
index.html      Home: Next, the + and the steps, taps, the AI choices, pairing
download.html   Android, the free Mac app, Linux; first start on a Mac; pairing
agent.html      Running your own Agent at home, with its download
privacy.html    The privacy policy (the URL Google Play asks for)
style.css       The app's sand (light) and moss (dark), IBM Plex Sans only
fonts/          The app's own fonts (OFL, see fonts/LICENSE.txt)
assets/         Icon, link preview image, and screens/: the app's screenshots, light and dark
```

Preview: `python3 -m http.server -d site 8000`, then http://localhost:8000.
The download links work once `deploy/website` has assembled the site.

The pages follow the system's light or dark setting; screenshots switch with
it. `hubidubitask.example` and `hello@hubidubitask.example` are placeholders
that `deploy/website/2-site/assemble.sh` replaces from its `site.env`.

When the app changes: retake the screenshots with
`deploy/google-play/3-graphics/shoot.py`, recount the taps table, and update
the version and file sizes on the Download and Agent pages (the assemble step
checks them against the real files).
