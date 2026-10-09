# 1. The app bundle

```sh
sh deploy/google-play/1-app-bundle/build.sh
```

Builds the release App Bundle and checks it as Play will: the signature, the
version, the permissions it ends up with, and the 16 KB alignment of each
native library. The file to upload lands in `out/` (not committed):
`out/hubidubitask-<version>-<versionCode>.aab`.

## In Play Console

1. **Create app**: name `HubidubiTask: Tasks & Calendar`, default language
   English (United States), App, Free. Free cannot be changed to paid later.
2. **App signing**: accept Google's app signing when you upload the first
   bundle. The PRIVATE key that signed it then acts as the upload key:
   `~/.keystores/hubidubitask-upload.jks`, alias `private`, password
   in the Keychain item `android-keystore-hubidubitask-upload`. Keep both backed up: a
   lost upload key can only be reset through Play support.
3. Upload the `.aab` in step 6, on a testing track.

## Rules that do not change

- The package name `com.saferendering.hubidubitask` is permanent once
  uploaded.
- Every upload needs a higher `hubidubitask.versionCode` in
  `src/apps/gradle.properties`, and release notes under
  `../6-release/release-notes/<language>/<versionCode>.txt`.
