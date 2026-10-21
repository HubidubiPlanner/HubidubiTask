# HubidubiTask website

Plain static HTML and CSS: no build step, no framework, no third-party
requests. Designed page by page in the design canvas "HubidubiTask Website";
publish it with [`deploy/website`](../deploy/website).

```
index.html      Home: Next, the + and the steps, taps, the AI choices, pairing
download.html   Android, Mac and Linux (the release on GitHub); pairing
agent.html      Plugging in the AI agent you already run at home
privacy.html    The privacy policy (the URL Google Play asks for)
style.css       The app's sand (light) and moss (dark), IBM Plex Sans only
fonts/          The app's own fonts (OFL, see fonts/LICENSE.txt)
assets/         Icon, link preview image, and screens/: the app's screenshots, light and dark
```

Preview: `python3 -m http.server -d site 8000`, then http://localhost:8000.

The pages follow the system's light or dark setting; screenshots switch with
it. `https://hubidubitask.example` and `https://contact.hubidubitask.example`
are placeholders that `deploy/website/2-site/assemble.sh` replaces with
`SITE_URL` and `CONTACT_URL` from its `site.env`.

When the app changes: retake the screenshots with
`deploy/google-play/3-graphics/shoot.py` and recount the taps table.
