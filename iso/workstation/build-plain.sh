#!/usr/bin/env bash
# Build the PLAIN hubidubios ISO — a terminal-first Arch live/install image with
# hubidubios baked in and a seeded vault. Self-contained: one privileged x86_64
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
HUBIDUBIOS_REPO=/build/hubidubios-repo
WORK="$(mktemp -d)"
PROFILE="$WORK/profile"

command -v mkarchiso >/dev/null || pacman -Sy --noconfirm --needed archiso
command -v makepkg   >/dev/null || pacman -Sy --noconfirm --needed base-devel

# pacman's landlock sandbox is often unavailable in VM/container kernels.
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i '/^\[options\]/a DisableSandbox' /etc/pacman.conf

echo "==> Building hubidubios packages"
pacman -Sy --noconfirm --needed base-devel git \
  python-build python-installer python-hatchling python-wheel
id build &>/dev/null || useradd -m build
echo 'build ALL=(ALL) NOPASSWD: ALL' >/etc/sudoers.d/build
install -d -o build "$HUBIDUBIOS_REPO"
for p in packages/hubidubios-vault packages/hubidubios-plugin-host \
         packages/hubidubios-sync packages/hubidubios-agent \
         plugins/gtd plugins/calendar; do
  cp -a "$REPO/$p" "/tmp/$(basename "$p")"
  chown -R build "/tmp/$(basename "$p")"
  ( cd "/tmp/$(basename "$p")" && sudo -u build makepkg -f --nodeps --skipinteg )
  cp "/tmp/$(basename "$p")"/*.pkg.tar.* "$HUBIDUBIOS_REPO"/
done
repo-add "$HUBIDUBIOS_REPO/hubidubios.db.tar.gz" "$HUBIDUBIOS_REPO"/*.pkg.tar.*

echo "==> Assembling the archiso profile (releng + hubidubios)"
cp -r /usr/share/archiso/configs/releng "$PROFILE"
echo "-> releng copied; branding"

# Branding: make it announce itself as HubidubiOS, not vanilla Arch. (The name
# is the OS branding; the CLI/packages stay `hubidubios`.)
sed -i \
  -e 's/^iso_name=.*/iso_name="hubidubios"/' \
  -e 's/^iso_label=.*/iso_label="HUBIDUBIOS_$(date +%Y%m)"/' \
  -e 's#^iso_publisher=.*#iso_publisher="HubidubiOS <https://github.com/hubidubitask/HubidubiTask>"#' \
  -e 's/^iso_application=.*/iso_application="HubidubiOS workstation live medium"/' \
  "$PROFILE/profiledef.sh"
# Retitle the boot-menu entries. Best-effort and non-fatal: releng's exact
# label string varies between archiso versions, so a no-match must NOT kill the
# build (it did once, silently, under `set -o pipefail`).
find "$PROFILE"/syslinux "$PROFILE"/efiboot "$PROFILE"/grub -type f 2>/dev/null | while read -r f; do
  sed -i -e 's/Arch Linux install medium/HubidubiOS live/g' \
         -e 's/^\(title[[:space:]]*\)Arch Linux.*/\1HubidubiOS live/' "$f" 2>/dev/null || true
done
# The epic-font banner in airootfs/etc/motd greets you at login.

# Append hubidubios + a usable base to releng's package list (keeps all boot deps).
cat >> "$PROFILE/packages.x86_64" <<'PKGS'

# --- hubidubios ---
hubidubios-vault
hubidubios-plugin-host
hubidubios-sync
hubidubios-agent
hubidubios-plugin-gtd
hubidubios-plugin-calendar
openssh
nftables
avahi
# NVIDIA open kernel driver — REQUIRED to light a Blackwell/RTX 50-series card
# in the live env (nouveau can't drive it, and the bare framebuffer console goes
# black mid-boot on this GPU). nvidia-utils pulls the matching userspace.
nvidia-open
nvidia-utils
tmux
git
vim
PKGS

# Add the local hubidubios repo so mkarchiso installs those packages offline.
cat >> "$PROFILE/pacman.conf" <<PACMAN

[hubidubios]
SigLevel = Optional TrustAll
Server = file://$HUBIDUBIOS_REPO
PACMAN

# Overlay our airootfs (hubidubios-firstboot, services, capture helpers) onto releng.
cp -a "$HERE/airootfs/." "$PROFILE/airootfs/"
# Do NOT auto-enable the firewall on the installer image — a strict ruleset
# with the wrong LAN subnet would break the installer's own networking.
rm -f "$PROFILE/airootfs/etc/systemd/system/multi-user.target.wants/nftables.service"

# Bake the seed vault into the image; hubidubios-firstboot copies it to ~/vault.
install -d "$PROFILE/airootfs/usr/share/hubidubios/seed"
cp -a "$HERE/seed/vault" "$PROFILE/airootfs/usr/share/hubidubios/seed/"

# Bake the offline hubidubios package repo into the image so `hubidubios-add` can
# install hubidubios onto the disk after archinstall (its Arch deps come from the
# mirrors; hubidubios itself installs from this file repo — no cloud).
echo "-> baking offline hubidubios repo"
install -d "$PROFILE/airootfs/usr/share/hubidubios/repo"
cp "$HUBIDUBIOS_REPO"/*.pkg.tar.* "$PROFILE/airootfs/usr/share/hubidubios/repo/"
cp "$HUBIDUBIOS_REPO"/hubidubios.db* "$HUBIDUBIOS_REPO"/hubidubios.files* "$PROFILE/airootfs/usr/share/hubidubios/repo/" 2>/dev/null || true

# Guarantee our helper scripts are executable in the image. The exec bit can be
# lost across the Lima/virtiofs mount, so set it authoritatively via mkarchiso's
# file_permissions (and chmod the staged copies as a belt-and-suspenders).
chmod 0755 "$PROFILE"/airootfs/usr/local/bin/hubidubios-* 2>/dev/null || true
sed -i '/^file_permissions=(/a\
  ["/usr/local/bin/hubidubios-firstboot"]="0:0:755"\
  ["/usr/local/bin/hubidubios-capture-gui"]="0:0:755"\
  ["/usr/local/bin/hubidubios-next-panel"]="0:0:755"\
  ["/usr/local/bin/hubidubios-add"]="0:0:755"' "$PROFILE/profiledef.sh"

# NVIDIA graphics. On a machine whose only GPU is a brand-new NVIDIA card
# (RTX 50-series/Blackwell) with no iGPU, nouveau can't drive it, AND the bare
# framebuffer console (simpledrm/efifb) can't be sustained past early boot — you
# see a few log lines, then black. The only thing that reliably lights the card
# is NVIDIA's own kernel driver. So we bake in nvidia-open (the open kernel
# modules, the ones that support Blackwell), load them early via the initramfs,
# and boot with nvidia-drm.modeset=1 + nouveau blacklisted. Fallback entries
# (bare framebuffer, then nomodeset) stay for other/older hardware.
echo "-> configuring NVIDIA driver (nvidia-open, early KMS)"

# Early-load the nvidia modules in the ISO's initramfs so KMS lights the display
# before userspace, and blacklist nouveau at the module level (belt to the
# kernel-param suspenders below).
install -Dm644 /dev/stdin "$PROFILE/airootfs/etc/mkinitcpio.conf.d/nvidia.conf" <<'MKI'
# hubidubios: pull NVIDIA's KMS-capable modules into the live initramfs so the RTX
# lights up early instead of black-screening on the framebuffer console.
MODULES+=(nvidia nvidia_modeset nvidia_uvm nvidia_drm)
MKI
install -Dm644 /dev/stdin "$PROFILE/airootfs/etc/modprobe.d/hubidubios-nvidia.conf" <<'MOD'
blacklist nouveau
options nvidia_drm modeset=1
MOD

EFIENT="$PROFILE/efiboot/loader/entries"
base_entry="$(ls "$EFIENT"/*archiso*linux.conf 2>/dev/null | grep -v speech | head -1 || true)"
if [ -n "${base_entry:-}" ]; then
  # Default: drive the card with nvidia-open + KMS (blacklist nouveau).
  sed -e 's/^title.*/title    HubidubiOS live (NVIDIA driver)/' \
      -e 's/^\(options .*\)/\1 nvidia-drm.modeset=1 modprobe.blacklist=nouveau/' \
      "$base_entry" > "$EFIENT/00-hubidubios-nvidia.conf"
  # Fallback 1: no nvidia driver, keep the bare framebuffer console, block nouveau.
  sed -e 's/^title.*/title    HubidubiOS live (framebuffer — no nvidia driver)/' \
      -e 's/^\(options .*\)/\1 modprobe.blacklist=nouveau,nvidia,nvidia_drm nouveau.modeset=0/' \
      "$base_entry" > "$EFIENT/01-hubidubios-framebuffer.conf"
  # Fallback 2: disable all KMS.
  sed -e 's/^title.*/title    HubidubiOS live (fallback — nomodeset)/' \
      -e 's/^\(options .*\)/\1 nomodeset modprobe.blacklist=nouveau,nvidia,nvidia_drm/' \
      "$base_entry" > "$EFIENT/02-hubidubios-nomodeset.conf"
  # Declutter the menu: drop the stock archiso linux + speech entries (our three
  # HubidubiOS entries replace the plain one). Fewer entries = no scroll-off.
  rm -f "$EFIENT"/*archiso*linux.conf "$EFIENT"/*speech*.conf
  if grep -q '^default' "$PROFILE/efiboot/loader/loader.conf" 2>/dev/null; then
    sed -i 's/^default .*/default 00-hubidubios-nvidia.conf/' "$PROFILE/efiboot/loader/loader.conf"
  else
    echo 'default 00-hubidubios-nvidia.conf' >> "$PROFILE/efiboot/loader/loader.conf"
  fi
else
  echo "   (warning: no base UEFI loader entry found; NVIDIA default not set)" >&2
fi

# systemd-boot on a large NVIDIA UEFI framebuffer (GOP) redraws the whole screen
# on every keypress, so the cursor lags for seconds. Pin an 80x25 text console
# (console-mode 0) so redraws are instant, and give a short timeout so the
# pre-selected NVIDIA entry just boots without any navigation.
LOADER="$PROFILE/efiboot/loader/loader.conf"
grep -q '^console-mode' "$LOADER" 2>/dev/null && sed -i 's/^console-mode.*/console-mode 0/' "$LOADER" || echo 'console-mode 0' >> "$LOADER"
grep -q '^timeout'      "$LOADER" 2>/dev/null && sed -i 's/^timeout.*/timeout 8/'        "$LOADER" || echo 'timeout 8'        >> "$LOADER"

# Ship the config files.
install -Dm644 "$REPO/config/nftables.conf" "$PROFILE/airootfs/etc/nftables.conf"
install -Dm644 "$REPO/config/avahi/hubidubios.service" "$PROFILE/airootfs/etc/avahi/services/hubidubios.service"
for unit in "$REPO"/config/systemd/hubidubios-*.service "$REPO"/config/systemd/vault-*.{service,timer}; do
  [ -f "$unit" ] && install -Dm644 "$unit" "$PROFILE/airootfs/etc/systemd/user/$(basename "$unit")"
done

# On the live medium's root autologin, seed the vault + build the index once,
# so booting the USB drops you into a ready-to-use hubidubios (great for trying it).
for rc in .zprofile .bash_profile; do
  cat >> "$PROFILE/airootfs/root/$rc" <<'HOOK'
if [ ! -e /root/.hubidubios-seeded ]; then
  hubidubios-firstboot || true   # seeds ~/vault, refreshes types, indexes
  touch /root/.hubidubios-seeded
fi
HOOK
done

echo "==> mkarchiso"
install -d "$OUT"
mkarchiso -v -w "$WORK/build" -o "$OUT" "$PROFILE"
echo "==> ISO(s) in $OUT:"; ls -la "$OUT"/*.iso
rm -rf "$WORK"
