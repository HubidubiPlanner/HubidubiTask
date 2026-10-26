# Security

## Reporting a vulnerability

Report it privately, through GitHub's private vulnerability reporting:

<https://github.com/HubidubiPlanner/HubidubiTask/security/advisories/new>

Never in a public issue, a pull request or a discussion. There is no email
address for reports; GitHub keeps the report visible only to you and the
maintainers until a fix is out.

Please include:

- what is affected (pairing, sync, TLS, an AI connection, feeds, imports,
  backups, what is stored on a device);
- the version and the system (Android, macOS or Linux);
- how to reproduce it, and what an attacker gains.

## Supported versions

Only the latest release gets fixes.

## What the design promises

- Pairing uses the typed code as a J-PAKE password, mixed with ML-KEM-768:
  someone on the Wi-Fi learns nothing from watching or, with today's
  computers, from posing as the other device.
- Every sync round is sealed with fresh keys, inside TLS pinned to the
  certificate exchanged at pairing. The computer answers only addresses on
  the home network.
- An AI server on the internet needs a certificate an authority vouches for;
  one at home has its own certificate pinned on first use. Plain HTTP goes
  only to home-network addresses.
- Web pages and calendar links are fetched over HTTPS only, never from the
  home network. Their text reaches an AI fenced as data, and feeds go only to
  Claude.
- Keys and tokens are kept in the Android Keystore, the macOS Keychain or the
  Linux Secret Service.

A way around any of these is a vulnerability. The details are in
[docs/ecosystem.html](docs/ecosystem.html) and [docs/MANUAL.md](docs/MANUAL.md).

## Against a quantum computer

A recording made today stays closed even to a future quantum computer:

- every sync round, whose keys come partly from a fresh ML-KEM-768 exchange;
- the key a pairing makes, mixed with ML-KEM-768 (a recording could give
  away the code, which is used up by then, but not the key);
- backups, sealed with AES-256-GCM.

Both apps offer post-quantum TLS (X25519MLKEM768) on every connection; a
server on the internet decides whether it takes it.

Still classical, as in the rest of the industry for now:

- J-PAKE in pairing. Someone on the Wi-Fi with a quantum computer, while a
  code is open, could learn the code and pair a device of their own; your
  phone would then fail to pair with that code. Pairing moves to a
  post-quantum PAKE once one is standardised.
- Signatures: the computer's certificate, the release tags and SHA256SUMS,
  the APK and Google Play's key.
