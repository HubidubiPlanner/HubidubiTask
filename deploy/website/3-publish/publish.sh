#!/bin/sh
# Step 3: upload the assembled site (step 2) to PUBLISH_TARGET from
# ../2-site/site.env, with rsync. Hosts that take uploads another way
# (a web dashboard, git, a CLI of their own): upload ../2-site/out/public/ there.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
. "$here/../2-site/site.env"
public="$here/../2-site/out/public"
[ -d "$public" ] || { echo "run ../2-site/assemble.sh first" >&2; exit 1; }
[ -n "$PUBLISH_TARGET" ] || { echo "set PUBLISH_TARGET in ../2-site/site.env" >&2; exit 1; }
case "$SITE_URL $CONTACT_URL" in
  *hubidubitask.example*) echo "site.env still has the placeholder address or contact" >&2; exit 1 ;;
esac
rsync -av --delete "$public/" "$PUBLISH_TARGET"
echo "==> published to $PUBLISH_TARGET; now check $SITE_URL (see README.md)"
