# Wessage

Wear OS messaging app focused on fast glance-and-reply workflows with a modern glass style.

## Stack

- Kotlin `2.2.10`
- AGP `9.1.0`
- Wear Compose Foundation `1.5.6`
- Wear Compose Material 3 `1.5.6`
- Compile SDK `36`, min SDK `29`

## Architecture Notes

- Wear-native scaffolding: `AppScaffold -> ScreenScaffold -> ScalingLazyColumn`
- Swipe-back navigation with `BasicSwipeToDismissBox`
- Single source of truth in `MessagingViewModel` with `StateFlow`
- Lifecycle-aware state collection via `collectAsStateWithLifecycle()`

## Releases (Maintainers)

Releases are generated from tags matching `v*` (for example `v1.1.0`) via GitHub Actions.

Typical assets:

- `app-debug.apk`
- `app-release-signed.apk` (when signing secrets are configured)
- `app-release-unsigned.apk` (fallback)
- `SHA256SUMS.txt`

Release flow:

1. Bump version in `app/build.gradle.kts`.
2. Push changes to `main`.
3. Tag and push:

```bash
git tag v1.1.0
git push origin v1.1.0
```

4. Workflow creates a GitHub Release and uploads APK assets.

### Optional Signing Secrets

- `ANDROID_SIGNING_KEY_BASE64`
- `ANDROID_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`
