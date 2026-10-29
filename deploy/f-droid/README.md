# F-Droid

[`com.saferendering.hubidubitask.yml`](com.saferendering.hubidubitask.yml) is
the build recipe for [fdroiddata](https://gitlab.com/fdroid/fdroiddata), the
repository F-Droid builds its apps from. It lives here until it is merged
there; after that, fdroiddata's copy is the one that counts.

F-Droid builds the app from the tagged source itself, but doesn't sign it:
the build is reproducible, so F-Droid compares its own build with the APK of
the GitHub release, `HubidubiTask-<version>.apk` (`Binaries`), and when the
two are identical apart from the signature it ships that APK, signed with the
project's APK key (`AllowedAPKSigningKeys`, see [SIGNING.md](../../SIGNING.md)).
Only the maintainer can sign a version for F-Droid users, and F-Droid
users get the same APK as the GitHub release. It reads the listing, the
graphics and the release notes from
[`fastlane/metadata/android/`](../../fastlane/metadata/android) in this
repository, so there is nothing else to send.

How the recipe builds: from the tag `v<version>`, in `src/apps/android`, with
`-Phubidubitask.androidOnly=true`, so Gradle leaves the desktop app out. The
release build comes out unsigned when no keystore is set up, which is what
F-Droid compares with the signed APK. New versions are found by their tags
(`v3.1.0`, …); the version name and code are read from
`src/apps/gradle.properties`.

## Before each release that F-Droid should pick up

1. Raise `hubidubitask.version` and `hubidubitask.versionCode` in
   `src/apps/gradle.properties`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and the
   `pl-PL` one (500 characters at most).
3. Tag the commit `v<version>` and push the tag.
4. Build the release files with
   [`deploy/website/1-downloads/build.sh`](../website/1-downloads/build.sh):
   it builds `HubidubiTask-<version>.apk` from a fresh clone of the tag and
   signs it with the APK key. Attach it to the GitHub release under that
   name, with the other files; F-Droid looks for it there.

From the first release with Whisper (after 3.1.2), the phone app builds
whisper.cpp with the NDK and makes the Whisper model during the build
([`src/apps/tools/whisper-model.sh`](../../src/apps/tools/whisper-model.sh):
a pinned download, quantized to 4 bits, checked against a pinned hash; the
same file on macOS and on Linux). The build entry in the recipe then needs:

```yaml
    submodules: true
    sudo:
      - sdkmanager 'cmake;3.31.6'
    ndk: 28.2.13676358
    scandelete:
      - src/apps/android/whisper.cpp/models
      - src/apps/android/whisper.cpp/examples/whisper.android.java
      - src/apps/android/whisper.cpp/bindings
```

The model is downloaded by Gradle, after F-Droid's scanner has run; say so
in the merge request. The native libraries are built without paths, build
ids or git hashes in them, so the Mac and F-Droid's Linux make the same
ones.

The APK must come from the tag, never from a checkout with local changes:
F-Droid's build has to match it byte for byte, or F-Droid skips the version.
Line endings count too: a file checked out with Windows line endings (CRLF)
makes the APKs differ.

## Test the build locally

The same container F-Droid's build servers use, with Docker. About 5 GB of
disk and 2 GB of downloads the first time. From this repository's root:

```sh
git clone --depth=1 https://gitlab.com/fdroid/fdroidserver ~/fdroidserver
git clone --depth=1 https://gitlab.com/fdroid/fdroiddata ~/fdroiddata
cp deploy/f-droid/com.saferendering.hubidubitask.yml ~/fdroiddata/metadata/

docker run --rm -itu vagrant --entrypoint /bin/bash \
  -v ~/fdroiddata:/build:z \
  -v ~/fdroidserver:/home/vagrant/fdroidserver:Z \
  -v "$PWD":/src:ro \
  registry.gitlab.com/fdroid/fdroidserver:buildserver
```

Inside the container:

```sh
. /etc/profile
export PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver"
export JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 > /dev/null | grep 'java.home' | awk -F'=' '{print $2}' | tr -d ' ')
cd /build
fdroid readmeta
fdroid rewritemeta com.saferendering.hubidubitask
fdroid lint com.saferendering.hubidubitask
fdroid build -v -l com.saferendering.hubidubitask
```

`rewritemeta` must leave the file as it is, and `lint` must print nothing.
`fdroid build` downloads the APK named in `Binaries` and compares it with its
own build; it fails if they differ.
To try a tag that isn't pushed yet, build from this checkout instead: in
the copy under `~/fdroiddata/metadata/`, set `Repo: /src`. The tag the recipe
names (`v3.1.0`) must exist in the checkout. The built APK lands in
`~/fdroiddata/unsigned/`.

## Submit it

F-Droid's [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
asks for three months of development, unless the app "has been proven to
have some users". The history starts on 31 August 2026, so the three months
are up on 1 December 2026. A merge request before then should say how many
testers the app has on Google Play's test track; the reviewers decide
whether that is enough.

1. Use the project's GitLab account; the recipe's `AuthorName` is
   HubidubiPlanner.
2. Fork <https://gitlab.com/fdroid/fdroiddata>, keep the fork public, and
   create a branch named `com.saferendering.hubidubitask`.
3. Add the recipe as `metadata/com.saferendering.hubidubitask.yml`, with
   `Repo` pointing at GitHub and `commit` the full hash of the tagged commit
   (`git rev-parse 'v3.1.0^{commit}'`), never the tag name. Commit it as
   `New app: HubidubiTask` and push.
4. Wait for the fork's CI pipeline to pass. If GitLab asks for a phone number
   or a card to run it, don't give one: say so in the merge request and
   F-Droid starts the pipeline.
5. Open a merge request to fdroiddata from that branch, titled
   `New app: HubidubiTask`, and fill in its template.
6. Answer the reviewers' questions. They may ask about the native libraries
   that come with SQLCipher and Conscrypt (both open source, from Maven
   Central) and about the anti-feature: the app can use Anthropic's Claude
   API, an OpenAI-compatible server, Todoist and Google Calendar, all
   optional and set up by the user.

Once it is merged, F-Droid's update checker adds each new `v…` tag by
itself.

## Google's developer verification

From 2027, phones with Google Play install an app only when its package name
and signing key are registered to a developer verified by Google. Play
registered the Play-signed app by itself. The APK key, which signs the
F-Droid and GitHub APKs, has to be added to the same package once, in Play
Console:

1. **Android developer verification → Package names**: add the APK key to
   `com.saferendering.hubidubitask`, SHA-256
   `9edba9349d2da2c0e032a855b09b2e8ca046d26dc7ed2a13257eb093a99d8184`.
2. To prove the key is yours, Play Console shows a snippet (**Upload APK**).
   Put it in `src/apps/android/src/main/assets/adi-registration.properties`,
   build a release APK signed with the APK key, and upload that APK there.

Until then the APK installs as before outside Brazil, Indonesia, Singapore
and Thailand, where checks started on 30 September 2026.
