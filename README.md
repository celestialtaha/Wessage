# Wessage (Wear OS Companion)

Wessage is a modern, minimal Wear OS messaging companion focused on:

- fast glance + reply on watch
- clean glass-style UI
- local-first sync with your phone app

## For Most Users

Download from the **Releases** page:

- https://github.com/celestialtaha/Wessage/releases

Install the latest watch APK, open the app once, and keep Bluetooth enabled.

## What You Need

- Android phone with the companion phone app installed
- A Wear OS watch paired to that phone
- Bluetooth enabled on both devices

Internet is not required for normal phone-watch message sync once pairing is active.

## Setup in 2 Minutes

1. Install the latest phone app release (from `celestialtaha/Messages`).
2. Install the latest watch app release from this repo.
3. Open the phone app, then open Wessage on the watch.
4. Grant permissions on phone/watch when prompted.
5. Wait a few seconds for initial thread sync.

## Privacy

- No separate cloud account is required.
- Sync is phone <-> watch over Wear OS data layer.
- Data is not sent to third-party servers by this app for core sync.

## Troubleshooting

- No messages on watch:
  - Open both apps once (phone first, then watch).
  - Confirm watch is paired and connected.
  - Keep Bluetooth on.
- Slow first sync:
  - Initial sync can take longer than incremental updates.

## Maintainers: Release Process

Tag any commit with `v*` (example `v1.0.0`) to trigger release workflow:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Workflow outputs:

- `app-debug.apk`
- `app-release-signed.apk` (if secrets exist)
- `app-release-unsigned.apk` (fallback)
- `SHA256SUMS.txt`
