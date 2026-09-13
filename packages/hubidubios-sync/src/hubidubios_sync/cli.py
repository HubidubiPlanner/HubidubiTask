"""hubidubios-sync CLI: serve the change feed, mint pair tokens."""

import argparse
import sys

from hubidubios_vault.vault import Vault

from . import auth, certs, db


def cmd_serve(vault: Vault, args) -> int:
    from .server import serve

    # 'auto' resolves the primary LAN IP so the unit needn't hardcode an address
    # (DHCP-friendly, and pairing then works out of the box).
    bind = _lan_ip() if args.bind == "auto" else args.bind
    if bind == "0.0.0.0":
        print("refusing to bind 0.0.0.0 — bind the LAN interface explicitly",
              file=sys.stderr)
        return 1
    srv = serve(vault, bind, args.port, use_tls=not args.no_tls)
    scheme = "http" if args.no_tls else "https"
    print(f"hubidubios-sync on {scheme}://{bind}:{args.port} (vault: {vault.root})")
    if srv.fingerprint:
        print(f"cert fingerprint: {srv.fingerprint}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0


def _lan_ip() -> str:
    """Best-effort primary LAN IPv4 (no packet is actually sent)."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))  # TEST-NET-1, unroutable; just to pick the iface
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def cmd_pair(vault: Vault, args) -> int:
    import json

    conn = db.connect(db.sync_db_path(vault))
    token = auth.mint_pair_token(conn)
    conn.close()

    # A pinned pairing needs the cert fingerprint too, so mint the cert now if
    # the server has never been started (fingerprint must be known out-of-band).
    cert, _ = certs.ensure_cert(vault)
    fp = certs.fingerprint(cert)

    host = args.host or _lan_ip()
    port = args.port
    # The full payload the phone app consumes (paste it, or scan the QR): the
    # app pins `cert_fingerprint` on the first connection, then POSTs the token.
    payload = {
        "host": host,
        "port": port,
        "cert_fingerprint": fp,
        "one_time_token": token,
    }

    line = json.dumps(payload, separators=(",", ":"))
    print(f"one-time pair token (valid {auth.PAIR_WINDOW_S // 60} min): {token}")
    print(f"cert fingerprint: {fp}")
    print()
    # Scanning is the primary path: the payload is ~150 chars of JSON and the
    # workstation is usually a screen you cannot copy/paste off of.
    if not getattr(args, "no_qr", False):
        print("In the phone app: Settings -> Pair workstation -> Scan QR, then "
              "point the camera here:")
        _print_qr(line)
        print("...or paste this line instead:")
    else:
        print("In the phone app: Settings -> Pair workstation, then paste this line:")
    print(line)
    print()
    print(f"(Make sure the sync server is reachable: "
          f"hubidubios-sync serve --bind {host} --port {port})")
    return 0


def _print_qr(text: str) -> None:
    """Render the pairing payload as a scannable QR in the terminal.

    'qrcode' is a hard dependency (python-qrcode), but the import is kept
    defensive so a broken install degrades to the pasteable line rather than
    taking the whole `pair` command down with it.
    """
    try:
        import qrcode

        qr = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_L)
        qr.add_data(text)
        qr.make(fit=True)
        # invert=True gives dark-on-light modules, which phone cameras expect
        # even when the terminal itself is a dark theme.
        qr.print_ascii(invert=True)
    except Exception as exc:  # pragma: no cover - depends on a broken install
        print(f"(could not render the QR: {exc}; "
              f"install python-qrcode — paste the line below instead)")


def cmd_devices(vault: Vault, args) -> int:
    conn = db.connect(db.sync_db_path(vault))
    rows = conn.execute(
        "SELECT id, name, created, last_seen FROM devices ORDER BY created").fetchall()
    conn.close()
    for r in rows:
        print(f"{r['id']}  {r['name']}  paired:{r['created']}  "
              f"last_seen:{r['last_seen'] or 'never'}")
    if not rows:
        print("no paired devices")
    return 0


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="hubidubios-sync",
                                description="hubidubios change-feed server")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("serve", help="run the sync API")
    sp.add_argument("--bind", default="127.0.0.1",
                    help="LAN interface address, or 'auto' to detect it (never 0.0.0.0)")
    sp.add_argument("--port", type=int, default=5233)
    sp.add_argument("--no-tls", action="store_true",
                    help="plain HTTP, for local development only")
    sp.set_defaults(func=cmd_serve)

    sp = sub.add_parser("pair", help="mint a one-time pairing token + payload")
    sp.add_argument("--host", default=None,
                    help="LAN address the phone should reach (default: autodetect)")
    sp.add_argument("--port", type=int, default=5233,
                    help="sync server port (default: 5233)")
    sp.add_argument("--no-qr", action="store_true",
                    help="don't render the QR, just print the pasteable payload")
    sp.set_defaults(func=cmd_pair)

    sp = sub.add_parser("devices", help="list paired devices")
    sp.set_defaults(func=cmd_devices)

    args = p.parse_args(argv)
    return args.func(Vault.locate(), args)


if __name__ == "__main__":
    sys.exit(main())
