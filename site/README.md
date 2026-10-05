# HubidubiOS website

Plain static HTML and CSS — no build step, no framework, no third-party
requests. Upload this folder as-is to any static host.

```
index.html      landing page
privacy.html    privacy policy (the URL Google Play asks for)
style.css       the app's palette (Piasek light / Mech dark) and type
fonts/          the app's own fonts (OFL, see fonts/LICENSE.txt)
assets/         icon, favicon, social preview image
```

Preview locally: `python3 -m http.server -d site 8000` → http://localhost:8000

Before going live, replace:
- `hubidubios.example` — canonical and Open Graph URLs in both pages
- `hello@hubidubios.example` — contact address (also used in the Play listing)
- `downloads/HubidubiOS.dmg` — the Mac download (`cd src/apps && ./gradlew :macos:packageDmg`),
  or remove the Mac buttons from `index.html`

The page is one column that tells the story in four steps, then a table of
taps per action and the choice of AI. It follows the system light or dark
setting. The tap counts in the table come from the app: recount them when a
flow changes.
