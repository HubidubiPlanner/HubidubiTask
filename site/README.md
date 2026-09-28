# HubidubiOS website

Plain static HTML and CSS — no build step, no framework, no third-party
requests. Upload this folder as-is to any static host.

```
index.html      landing page
privacy.html    privacy policy (the URL Google Play asks for)
style.css       the app's palette (Piasek light / Mech dark) and type
fonts/          the app's own fonts (OFL, see fonts/LICENSE.txt)
assets/         icon, favicon, social preview image, screens/ (light + dark)
```

Preview locally: `python3 -m http.server -d site 8000` → http://localhost:8000

Before going live, replace:
- `hubidubios.example` — canonical and Open Graph URLs in both pages
- `hello@hubidubios.example` — contact address (also used in the Play listing)
- `downloads/HubidubiOS.dmg` — the Mac download (`cd mobile && ./gradlew :desktop:packageDmg`),
  or remove the Mac card from `index.html`

The page follows the system light/dark setting; the "Sand / Moss" button
overrides it and is remembered in the visitor's browser only. Each screenshot
has a light and a dark file and CSS shows the matching one.
