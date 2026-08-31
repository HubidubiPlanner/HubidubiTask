#!/usr/bin/env bash
# shellcheck disable=SC2034
# archiso profile for prodos-workstation (PLAN.md Phase 7).
# Derived from the `releng` profile; build.sh overlays this on a fresh copy of
# releng so the bootloader configs (efiboot/, syslinux/, grub/) come from
# upstream and only the package list, pacman.conf, and airootfs are ours.

iso_name="prodos-workstation"
iso_label="PRODOS_$(date +%Y%m)"
iso_publisher="prodos <https://gitlab.local/prodos>"
iso_application="prodos workstation live/install medium"
iso_version="$(date +%Y.%m.%d)"
install_dir="arch"
buildmodes=('iso')
bootmodes=('bios.syslinux.mbr' 'bios.syslinux.eltorito'
           'uefi-ia32.systemd-boot.esp' 'uefi-x64.systemd-boot.esp'
           'uefi-ia32.systemd-boot.eltorito' 'uefi-x64.systemd-boot.eltorito')
arch="x86_64"
pacman_conf="pacman.conf"
airootfs_image_type="squashfs"
airootfs_image_tool_options=('-comp' 'zstd' '-Xcompression-level' '20' '-b' '1M')
bootstrap_tarball_compression=('zstd' '-c' '-T0' '--auto-threads=logical')
file_permissions=(
  ["/etc/shadow"]="0:0:400"
  ["/root"]="0:0:750"
  ["/root/.automated_script.sh"]="0:0:755"
  ["/usr/local/bin/prodos-firstboot"]="0:0:755"
)
