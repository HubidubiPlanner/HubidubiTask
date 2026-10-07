#!/bin/sh
# Step 2: the website as it will be published, in out/public.
#
#   sh deploy/website/2-site/assemble.sh
#
# Copies site/ and the downloads from step 1, puts in the address and contact
# from site.env, and refuses to finish when the pages and the files disagree:
# the version on the download page, the file sizes it states, links to files
# that are not there.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
. "$here/site.env"
site_url=${SITE_URL%/}
version=$(sed -n 's/^hubidubitask.version=//p' "$repo/src/apps/gradle.properties")
downloads="$here/../1-downloads/out"
public="$here/out/public"

for f in HubidubiTask.dmg hubidubitask-x86_64.pkg.tar.zst hubidubitask-agent.tar.gz SHA256SUMS; do
  [ -f "$downloads/$f" ] || { echo "missing $downloads/$f: run ../1-downloads/build.sh first" >&2; exit 1; }
done

rm -rf "$here/out"
mkdir -p "$public"
cp -R "$repo/site/." "$public/"
rm -f "$public/README.md"
rm -rf "$public"/_render_* "$public"/_view_*
if [ -z "$DOWNLOADS_URL" ]; then
  mkdir -p "$public/downloads"
  cp "$downloads"/* "$public/downloads/"
fi

python3 - "$public" "$site_url" "$CONTACT_EMAIL" "${DOWNLOADS_URL%/}" "$version" "$downloads" <<'PY'
import glob, os, re, sys
public, site_url, email, dl_url, version, downloads = sys.argv[1:]
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
    if name == "download.html":
        if "Version %s" % version not in s:
            problems.append("download.html does not say Version %s" % version)
        for f in ("HubidubiTask.dmg", "hubidubitask-x86_64.pkg.tar.zst"):
            mb = round(os.path.getsize(os.path.join(downloads, f)) / 1e6)
            if "%s, %d MB" % (f, mb) not in s:
                problems.append("download.html should say %s, %d MB" % (f, mb))
    if name == "agent.html":
        kb = round(os.path.getsize(os.path.join(downloads, "hubidubitask-agent.tar.gz")) / 1e3)
        if "hubidubitask-agent.tar.gz, %d KB" % kb not in s:
            problems.append("agent.html should say hubidubitask-agent.tar.gz, %d KB" % kb)
for p in problems:
    print("!! " + p, file=sys.stderr)
sys.exit(1 if problems else 0)
PY

case "$site_url$CONTACT_EMAIL" in
  *hubidubitask.example*) echo "!! site.env still has the placeholder address or email; set them before publishing" >&2 ;;
esac
echo "==> $public ($(du -sh "$public" | cut -f1)), for $site_url"
echo "    preview: python3 -m http.server -d $public 8000"
