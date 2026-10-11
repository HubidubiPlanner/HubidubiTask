#!/bin/sh
# Step 2: the website as it will be published, in out/public.
#
#   sh deploy/website/2-site/assemble.sh
#
# Copies site/ and the downloads from step 1, puts in the address and contact
# from site.env, and refuses to finish when the pages and the files disagree:
# the version on the download page, the file sizes it states, links to files
# that are not there. While no page links to downloads/ (the Mac, Linux and
# Agent buttons say "Coming soon"), it takes no downloads and checks none.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
. "$here/site.env"
site_url=${SITE_URL%/}
version=$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")
downloads="$here/../1-downloads/out"
public="$here/out/public"

offers=0
grep -q 'href="downloads/' "$repo"/site/*.html && offers=1
if [ $offers = 1 ]; then
  for f in HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst SHA256SUMS; do
    [ -f "$downloads/$f" ] || { echo "missing $downloads/$f: run ../1-downloads/build.sh first" >&2; exit 1; }
  done
fi

rm -rf "$here/out"
mkdir -p "$public"
cp -R "$repo/site/." "$public/"
rm -f "$public/README.md"
rm -rf "$public"/_render_* "$public"/_view_*
if [ $offers = 1 ] && [ -z "$DOWNLOADS_URL" ]; then
  mkdir -p "$public/downloads"
  cp "$downloads"/* "$public/downloads/"
fi

python3 - "$public" "$site_url" "$CONTACT_EMAIL" "${DOWNLOADS_URL%/}" "$version" "$downloads" "$offers" <<'PY'
import glob, os, re, sys
public, site_url, email, dl_url, version, downloads, offers = sys.argv[1:]
problems = []
for page in glob.glob(os.path.join(public, "*.html")):
    s = open(page, encoding="utf-8").read()
    s = s.replace("https://hubidubitask.example", site_url).replace("hello@hubidubitask.example", email)
    if dl_url:
        s = s.replace('href="downloads/', 'href="%s/' % dl_url)
    open(page, "w", encoding="utf-8").write(s)
    name = os.path.basename(page)
    # Every local link and image must be there.
    for ref in re.findall(r'(?:href|src|srcset)="([^"#:]+)(?:#[^"]*)?"', s):
        if ref and not os.path.exists(os.path.join(public, ref)):
            problems.append("%s links to %s, which is not there" % (name, ref))
    if offers == "1" and name == "download.html":
        if s.count("<dt>Version</dt><dd>%s</dd>" % version) != 2:
            problems.append("download.html should give version %s for the Mac and Linux apps" % version)
        for f in ("HubidubiTask.dmg", "hubidubitask-x86_64.pkg.tar.zst"):
            mb = round(os.path.getsize(os.path.join(downloads, f)) / 1e6)
            if "%s, %d MB" % (f, mb) not in s:
                problems.append("download.html should say %s, %d MB" % (f, mb))
for p in problems:
    print("!! " + p, file=sys.stderr)
sys.exit(1 if problems else 0)
PY

case "$site_url$CONTACT_EMAIL" in
  *hubidubitask.example*) echo "!! site.env still has the placeholder address or email; set them before publishing" >&2 ;;
esac
echo "==> $public ($(du -sh "$public" | cut -f1)), for $site_url"
echo "    preview: python3 -m http.server -d $public 8000"
