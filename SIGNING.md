# Checking a download

Every release tag (`vX.Y.Z`) and every release's `SHA256SUMS` file is
signed with the project's release key, an SSH ed25519 key:

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIELaOkHNRB/RO8SJWPUkCKWvDhzhpL98X0Zp2ivmRvhx HubidubiPlanner (HubidubiTask releases)
```

Its fingerprint is `SHA256:8DpwSXhg22vF/6jUPBRH/XfUV+c9IYtp4043u9zhTw4`.
The same key is in [`.github/allowed_signers`](.github/allowed_signers).

## A Mac or Linux download

Download the file, `SHA256SUMS` and `SHA256SUMS.sig` from the same release, then:

```sh
ssh-keygen -Y verify -f allowed_signers -I hubidubiplanner@hubidubitask.invalid -n file \
  -s SHA256SUMS.sig < SHA256SUMS        # Good "file" signature …
shasum -a 256 -c SHA256SUMS --ignore-missing
```

(`allowed_signers` is the file from `.github/` in this repository.)

## A tag

```sh
git config gpg.ssh.allowedSignersFile .github/allowed_signers
git verify-tag v3.1.0
```

The Android app is signed by Google Play (from Play) or by F-Droid (from
F-Droid); each store checks its own signature when it installs or updates.
