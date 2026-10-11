# 4. Privacy policy

Play needs a privacy policy at a public web address, in **Policy and
programs › App content › Privacy policy**:

```
<SITE_URL>/privacy.html
```

`SITE_URL` is the one in `deploy/website/2-site/site.env`. The page itself is
[`site/privacy.html`](../../../site/privacy.html), published with the rest of
the website by `deploy/website`. It must be live before you submit.

What it says, so you can check it against the app content answers in step 5:

- The developer runs no server and receives no data.
- What is stored where: an encrypted database on the phone, the app's folder
  on a computer, API keys in the system's secure storage.
- Every way data leaves a device, each started by the user: sync between the
  user's own devices; a review by Claude (the item, to Anthropic with the
  user's key), by SelfHostedAI or an OpenAI-compatible server (the item, to the
  user's server), or by the user's own agent at home (the item and a text
  copy of the items, to the agent at the address they entered); feeds (the
  page, then Claude); one-time imports from Todoist and iCal.
- Permissions: microphone for voice memos; network, Wi-Fi state and
  multicast for finding and syncing with paired devices. Photos are taken by
  the phone's camera app, so the app asks for no camera permission.
- Deletion, children, this website, changes, contact.
