"""prodos-sync CLI: serve the change feed, mint pair tokens."""

import argparse
import sys

from prodos_vault.vault import Vault

from . import auth, certs, db


def cmd_serve(vault: Vault, args) -> int:
    from .server import serve

    if args.bind == "0.0.0.0":
        print("refusing to bind 0.0.0.0 — bind the LAN interface explicitly",
              file=sys.stderr)
        return 1
    srv = serve(vault, args.bind, args.port, use_tls=not args.no_tls)
    scheme = "http" if args.no_tls else "https"
    print(f"prodos-sync on {scheme}://{args.bind}:{args.port} (vault: {vault.root})")
    if srv.fingerprint:
        print(f"cert fingerprint: {srv.fingerprint}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
    return 0


def cmd_pair(vault: Vault, args) -> int:
    conn = db.connect(db.sync_db_path(vault))
    token = auth.mint_pair_token(conn)
    conn.close()
    print(f"one-time pair token (valid {auth.PAIR_WINDOW_S // 60} min): {token}")
    cert, _ = certs.cert_paths(vault)
    if cert.exists():
        print(f"cert fingerprint: {certs.fingerprint(cert)}")
    return 0


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
    p = argparse.ArgumentParser(prog="prodos-sync",
                                description="prodos change-feed server")
    sub = p.add_subparsers(dest="command", required=True)

    sp = sub.add_parser("serve", help="run the sync API")
    sp.add_argument("--bind", default="127.0.0.1",
                    help="LAN interface address (never 0.0.0.0)")
    sp.add_argument("--port", type=int, default=5233)
    sp.add_argument("--no-tls", action="store_true",
                    help="plain HTTP, for local development only")
    sp.set_defaults(func=cmd_serve)

    sp = sub.add_parser("pair", help="mint a one-time pairing token")
    sp.set_defaults(func=cmd_pair)

    sp = sub.add_parser("devices", help="list paired devices")
    sp.set_defaults(func=cmd_devices)

    args = p.parse_args(argv)
    return args.func(Vault.locate(), args)


if __name__ == "__main__":
    sys.exit(main())
