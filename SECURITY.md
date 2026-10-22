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
  someone on the Wi-Fi learns nothing from watching or from posing as the
  other device.
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
