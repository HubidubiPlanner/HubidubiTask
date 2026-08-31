"""Self-signed TLS for the LAN.

A pinned self-signed cert is strictly stronger than public-CA trust here:
clients pin the fingerprint obtained at pairing time. Generated with the
system openssl (present on both macOS and Arch).
"""

import hashlib
import ssl
import subprocess
from pathlib import Path

CERT_DAYS = 3650


def cert_paths(vault) -> tuple[Path, Path]:
    return vault.prodos_dir / "sync-cert.pem", vault.prodos_dir / "sync-key.pem"


def ensure_cert(vault, hostname: str = "workstation.lan") -> tuple[Path, Path]:
    cert, key = cert_paths(vault)
    if cert.exists() and key.exists():
        return cert, key
    vault.prodos_dir.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
         "-keyout", str(key), "-out", str(cert),
         "-days", str(CERT_DAYS), "-subj", f"/CN={hostname}",
         "-addext", f"subjectAltName=DNS:{hostname},DNS:localhost,IP:127.0.0.1"],
        check=True, capture_output=True)
    key.chmod(0o600)
    return cert, key


def fingerprint(cert_path: Path) -> str:
    """sha256 of the DER cert — what clients pin and mDNS TXT advertises."""
    der = ssl.PEM_cert_to_DER_cert(cert_path.read_text())
    return "sha256:" + hashlib.sha256(der).hexdigest()
