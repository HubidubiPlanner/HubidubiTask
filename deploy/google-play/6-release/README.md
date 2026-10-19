# 6. Release

Release notes go in with each upload, per language:
[`release-notes/en-US/11.txt`](release-notes/en-US/11.txt) and
[`release-notes/pl-PL/11.txt`](release-notes/pl-PL/11.txt) for this version
(versionCode 11; 500 characters at most). Older ones are kept beside them.

1. **Testing › Internal testing**: create a release, upload
   `../1-app-bundle/out/hubidubitask-2.4.0-11.aab`, paste the release notes,
   add yourself as a tester, install from the opt-in link. Check: the app
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
`src/apps/gradle.properties`, run step 1 again, and add
`release-notes/<language>/<versionCode>.txt`.
