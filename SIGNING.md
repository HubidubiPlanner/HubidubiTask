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

## The Android app

Two signatures exist, one per store; Android checks it on every update, so an
update can only come from where the app was installed.

- **From F-Droid, or the `.apk` of a GitHub release:** signed with the
  project's APK key, which only the maintainer holds. Its certificate
  (`CN=HubidubiPlanner, O=HubidubiTask`) has the SHA-256 fingerprint

  ```
  9edba9349d2da2c0e032a855b09b2e8ca046d26dc7ed2a13257eb093a99d8184
  ```

  F-Droid doesn't sign the app itself: it builds it from the tagged source,
  checks that its build is identical to the signed APK of the release, and
  then ships that signed APK. Check a downloaded one with
  `apksigner verify --print-certs HubidubiTask-3.1.0.apk`.
- **From Google Play:** signed by Google (Play App Signing), which every app
  uploaded to Play as a bundle uses. Only the maintainer can upload a
  version; Play checks the upload key.
