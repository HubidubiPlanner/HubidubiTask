# F-Droid

[`com.saferendering.hubidubitask.yml`](com.saferendering.hubidubitask.yml) is
the build recipe for [fdroiddata](https://gitlab.com/fdroid/fdroiddata), the
repository F-Droid builds its apps from. It lives here until it is merged
there; after that, fdroiddata's copy is the one that counts.

F-Droid builds the app from the tagged source itself and signs it with its
own key. It reads the listing, the graphics and the release notes from
[`fastlane/metadata/android/`](../../fastlane/metadata/android) in this
repository, so there is nothing else to send.

How the recipe builds: from the tag `v<version>`, in `src/apps/android`, with
`-Phubidubitask.androidOnly=true`, so Gradle leaves the desktop app out. The
release build comes out unsigned when no upload keystore is set up, which is
what F-Droid wants. New versions are found by their tags (`v3.1.0`, …); the
version name and code are read from `src/apps/gradle.properties`.

## Before each release that F-Droid should pick up

1. Raise `hubidubitask.version` and `hubidubitask.versionCode` in
   `src/apps/gradle.properties`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and the
   `pl-PL` one (500 characters at most).
3. Tag the commit `v<version>` and push the tag.

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
While the GitHub repository is not public yet, build from this checkout
instead: in the copy under `~/fdroiddata/metadata/`, set `Repo: /src`. The
tag the recipe names (`v3.1.0`) must exist in the checkout. The built APK
lands in `~/fdroiddata/unsigned/`.

## Submit it

Not before **1 December 2026**. F-Droid wants about three months of public
development history, and this history starts on 31 August 2026.

1. Use the project's GitLab account; the recipe's `AuthorName` is HubidubiPlanner.
2. Fork <https://gitlab.com/fdroid/fdroiddata> and create a branch named
   `com.saferendering.hubidubitask`.
3. Add the recipe as `metadata/com.saferendering.hubidubitask.yml`, with
   `Repo` pointing at GitHub again. Commit it as
   `New App: com.saferendering.hubidubitask` and push.
4. Wait for the fork's CI pipeline to pass, then open a merge request to
   fdroiddata from that branch and fill in its template.
5. Answer the reviewers' questions. They may ask about the native libraries
   that come with SQLCipher and Conscrypt (both open source, from Maven
   Central) and about the anti-feature: the app can use Anthropic's Claude
   API, an OpenAI-compatible server, Todoist and Google Calendar, all
   optional and set up by the user.

Once it is merged, F-Droid's update checker adds each new `v…` tag by
itself.
