#!/usr/bin/env bash
# Build the PLAIN prodos ISO — a terminal-first Arch live/install image with
# prodos baked in and a seeded vault. Self-contained: one privileged x86_64
# Arch environment, no Docker-in-Docker, no external Omarchy repo.
#
# Run on x86_64 Arch Linux as root (a live Arch USB, a VM, WSL2 w/ arch, or a
# privileged x86_64 container). On this arm64 Mac it runs inside an x86_64
# Colima VM (see build-plain-in-colima.sh).
#
#   sudo iso/workstation/build-plain.sh [output_dir]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${1:-$HERE/out}"
PRODOS_REPO=/build/prodos-repo
WORK="$(mktemp -d)"
PROFILE="$WORK/profile"

command -v mkarchiso >/dev/null || pacman -Sy --noconfirm --needed archiso
command -v makepkg   >/dev/null || pacman -Sy --noconfirm --needed base-devel

# pacman's landlock sandbox is often unavailable in VM/container kernels.
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i '/^\[options\]/a DisableSandbox' /etc/pacman.conf

echo "==> Building prodos packages"
pacman -Sy --noconfirm --needed base-devel git \
  python-build python-installer python-hatchling python-wheel
id build &>/dev/null || useradd -m build
echo 'build ALL=(ALL) NOPASSWD: ALL' >/etc/sudoers.d/build
install -d -o build "$PRODOS_REPO"
for p in packages/prodos-vault packages/prodos-plugin-host \
         packages/prodos-sync packages/prodos-agent \
         plugins/gtd plugins/calendar; do
  cp -a "$REPO/$p" "/tmp/$(basename "$p")"
  chown -R build "/tmp/$(basename "$p")"
  ( cd "/tmp/$(basename "$p")" && sudo -u build makepkg -f --nodeps --skipinteg )
  cp "/tmp/$(basename "$p")"/*.pkg.tar.* "$PRODOS_REPO"/
done
repo-add "$PRODOS_REPO/prodos.db.tar.gz" "$PRODOS_REPO"/*.pkg.tar.*

echo "==> Assembling the archiso profile (releng + prodos)"
cp -r /usr/share/archiso/configs/releng "$PROFILE"

# Branding: make it announce itself as prodos, not vanilla Arch.
sed -i \
  -e 's/^iso_name=.*/iso_name="prodos"/' \
  -e 's/^iso_label=.*/iso_label="PRODOS_$(date +%Y%m)"/' \
  -e 's#^iso_publisher=.*#iso_publisher="prodos <https://github.com/hubidubitask/HubidubiTask>"#' \
  -e 's/^iso_application=.*/iso_application="prodos workstation live medium"/' \
  "$PROFILE/profiledef.sh"
# Retitle the boot-menu entries (SYSLINUX/BIOS + systemd-boot/UEFI + grub).
grep -rlZ "Arch Linux install medium" "$PROFILE" 2>/dev/null \
  | xargs -0 -r sed -i 's/Arch Linux install medium/prodos live/g'
# The epic-font banner in airootfs/etc/motd greets you at login.

# Append prodos + a usable base to releng's package list (keeps all boot deps).
cat >> "$PROFILE/packages.x86_64" <<'PKGS'

# --- prodos ---
prodos-vault
prodos-plugin-host
prodos-sync
prodos-agent
prodos-plugin-gtd
prodos-plugin-calendar
openssh
nftables
avahi
tmux
git
vim
PKGS

# Add the local prodos repo so mkarchiso installs those packages offline.
cat >> "$PROFILE/pacman.conf" <<PACMAN

[prodos]
SigLevel = Optional TrustAll
Server = file://$PRODOS_REPO
PACMAN

# Overlay our airootfs (prodos-firstboot, services, capture helpers) onto releng.
cp -a "$HERE/airootfs/." "$PROFILE/airootfs/"
# Do NOT auto-enable the firewall on the installer image — a strict ruleset
# with the wrong LAN subnet would break the installer's own networking.
rm -f "$PROFILE/airootfs/etc/systemd/system/multi-user.target.wants/nftables.service"

# Bake the seed vault into the image; prodos-firstboot copies it to ~/vault.
install -d "$PROFILE/airootfs/usr/share/prodos/seed"
cp -a "$HERE/seed/vault" "$PROFILE/airootfs/usr/share/prodos/seed/"

# Guarantee our helper scripts are executable in the image. The exec bit can be
# lost across the Lima/virtiofs mount, so set it authoritatively via mkarchiso's
# file_permissions (and chmod the staged copies as a belt-and-suspenders).
chmod 0755 "$PROFILE"/airootfs/usr/local/bin/prodos-* 2>/dev/null || true
sed -i '/^file_permissions=(/a\
  ["/usr/local/bin/prodos-firstboot"]="0:0:755"\
  ["/usr/local/bin/prodos-capture-gui"]="0:0:755"\
  ["/usr/local/bin/prodos-next-panel"]="0:0:755"' "$PROFILE/profiledef.sh"

# Ship the config files.
install -Dm644 "$REPO/config/nftables.conf" "$PROFILE/airootfs/etc/nftables.conf"
install -Dm644 "$REPO/config/avahi/prodos.service" "$PROFILE/airootfs/etc/avahi/services/prodos.service"
for unit in "$REPO"/config/systemd/prodos-*.service "$REPO"/config/systemd/vault-*.{service,timer}; do
  [ -f "$unit" ] && install -Dm644 "$unit" "$PROFILE/airootfs/etc/systemd/user/$(basename "$unit")"
done

# On the live medium's root autologin, seed the vault + build the index once,
# so booting the USB drops you into a ready-to-use prodos (great for trying it).
for rc in .zprofile .bash_profile; do
  cat >> "$PROFILE/airootfs/root/$rc" <<'HOOK'
if [ ! -e /root/.prodos-seeded ]; then
  prodos-plugins refresh >/dev/null 2>&1 || true
  prodos-firstboot || true
  touch /root/.prodos-seeded
fi
HOOK
done

echo "==> mkarchiso"
install -d "$OUT"
mkarchiso -v -w "$WORK/build" -o "$OUT" "$PROFILE"
echo "==> ISO(s) in $OUT:"; ls -la "$OUT"/*.iso
rm -rf "$WORK"
