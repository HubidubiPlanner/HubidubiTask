# 6. Release

Release notes go in with each upload, per language:
[`en-US/changelogs/18.txt`](../../../fastlane/metadata/android/en-US/changelogs/18.txt) and
[`pl-PL/changelogs/18.txt`](../../../fastlane/metadata/android/pl-PL/changelogs/18.txt)
in `fastlane/metadata/android/` for this version (versionCode 18; 500
characters at most). F-Droid shows the same files. Older ones are kept
beside them.

1. **Testing › Internal testing**: create a release, upload
   `../1-app-bundle/out/hubidubitask-3.2.0-18.aab`, paste the release notes,
   add yourself as a tester, install from the opt-in link. From 3.2.0 Play
   asks for the foreground-service declaration at upload: the answer is in
   [`5-app-content/`](../5-app-content/README.md) (mediaProcessing, the
   voice note). Check: the app
   opens on Incoming, the + menu, Settings › Language, pairing with the computer, a review with AI,
   adding a feed.
2. **Testing › Closed testing**, if the account is a new personal one: at
   least 12 testers opted in for 14 days in a row before production unlocks.
3. Read the **Pre-launch report**: Play's robot taps through the app on real
   devices. Look for crashes on pairing, import and Settings.
4. **Production › Create release** from the same bundle, then **Send for
   review**. A first review usually takes a few days.
5. After it is live, replace nothing on the website: the Google Play links
   there already point at the package.

Next version: raise `hubidubitask.version` and `hubidubitask.versionCode` in
`src/apps/gradle.properties`, add
`fastlane/metadata/android/<language>/changelogs/<versionCode>.txt` for both
languages, tag the commit `v<version>` (F-Droid builds from the tag), and run
step 1 again.
