# Wear OS Messaging App OSS Handoff

Date: 2026-03-22
Source: Lessons from building and releasing Weavelet (Wear OS app)

This handout is meant to be dropped into a new repo so the next developer can move fast without digging through Wear Compose and Wear Material 3 docs.

## 1) What Worked Here (Keep These Defaults)

- Use Wear-specific UI libraries, not phone-first Material components.
- Build every screen round-first (small watches first, then scale up).
- Keep navigation simple with swipe-to-dismiss patterns.
- Make release automation tag-driven from day one.
- Generate release notes from commit history (Conventional Commits + git-cliff).

## 2) Baseline Tech Setup We Used

From this repo (`gradle/libs.versions.toml` + `app/build.gradle.kts`):

- Kotlin `2.2.10`
- AGP `9.1.0`
- Wear Compose Foundation `1.5.6`
- Wear Compose Material 3 `1.5.6`
- Compose UI `1.7.8`
- Java/Kotlin target `11`
- Compile SDK `36`, min SDK `29`

Core wear dependencies to keep in a messaging app too:

- `androidx.wear.compose:compose-foundation`
- `androidx.wear.compose:compose-material3`
- `androidx.activity:activity-compose`
- `androidx.lifecycle:lifecycle-runtime-compose`
- `androidx.lifecycle:lifecycle-viewmodel-compose`

## 3) Wear Compose + Wear M3 Practices (Most Important)

## 3.1 Use Wear Components Only

Preferred imports:

- `androidx.wear.compose.material3.*`
- `androidx.wear.compose.foundation.*`

Avoid mixing mobile Material 3 widgets unless you have a specific reason.

## 3.2 App/Screen Scaffolding Pattern

Use this shell as default:

```kotlin
@Composable
fun WearAppRoot() {
    AppScaffold {
        ScreenScaffold(scrollState = listState) {
            ScalingLazyColumn(state = listState, contentPadding = it) {
                // content
            }
        }
    }
}
```

Why this helped:

- Works with Wear layout expectations.
- Keeps list behavior and edge insets sane on round screens.
- Consistent structure across screens.

## 3.3 Navigation and Back Gesture

For a small app, this was enough and fast:

- `BasicSwipeToDismissBox`
- One top-level sealed `Screen` type
- Single source of truth: `currentScreen`

This gave predictable back behavior and avoided over-engineering with a nav graph early.

## 3.4 List and Form Patterns That Worked

- Lists: `ScalingLazyColumn`
- Section titles: `ListHeader`, `ListSubHeader`
- Actions: `Button`, `FilledTonalIconButton`, `IconButton`
- Toggles: `SwitchButton`, `RadioButton`
- Rows/cards: `Card`
- Progress/loading: `CircularProgressIndicator`

## 3.5 State and Lifecycle Rules

- Use `collectAsStateWithLifecycle()` for all `Flow`/`StateFlow` from ViewModels.
- Keep one source of truth in ViewModel for screen state.
- Use explicit UI states for loading/empty/error/success to avoid ambiguous UI branches.

## 3.6 Round-Screen Responsive Rules

Simple rule we used repeatedly:

- Compute `minScreenDp = min(screenWidthDp, screenHeightDp)`
- Treat `<= 220dp` as compact
- Reduce padding, spacing, and control sizes in compact mode

This prevented clipping and over-dense layouts on smaller watch faces.

## 3.7 Input Patterns Worth Reusing

- Swipe-to-dismiss for back navigation.
- Rotary crown with `onPreRotaryScrollEvent` when interaction needs it.
- Request focus when rotary is needed (`FocusRequester` + `focusable()`).
- Haptics on meaningful toggles only (`LocalHapticFeedback`).

## 3.8 Theme Pattern

Theme strategy that worked:

- Start from `dynamicColorScheme(context)`
- Override brand tokens (`primary`, `primaryContainer`, etc.)
- Keep contrast strong for tiny displays

## 3.9 Messaging App Mapping (Use This Translation)

Use the same architecture with different data:

- Home screen -> "Conversations", "Contacts", "Settings"
- Library list -> conversation list (search + pagination still applies)
- Player controls -> quick reply / archive / mute actions
- Settings screen pattern unchanged

## 3.10 Phone + Watch Architecture (Required for Clean Sync)

For messaging apps, plan for **both**:

- a phone app (source of telephony/account context)
- a watch app (Wear-native UI and quick actions)

Why:

- Wear OS does not provide a universal native cross-app message-history sync layer for all third-party apps.
- Notification bridging alone is not enough for full conversation sync.

Recommendation:

- Treat phone + watch as a first-class product pair from day one.
- Use your own sync channel (backend and/or companion transport) for message state.

## 3.11 Minimum Sync Blueprint (Use This in v1)

Data model (shared contract):

- `Conversation(id, participants, lastMessage, lastUpdatedAt, unreadCount)`
- `Message(id, conversationId, senderId, body, timestamp, status, localVersion)`
- `status` examples: `pending`, `sent`, `delivered`, `failed`, `read`

Sync flow:

1. Phone receives/sends messages (authoritative ingest path).
2. Phone normalizes to shared model and publishes deltas.
3. Watch syncs deltas, stores local cache, updates UI immediately.
4. Watch actions (reply/archive/mute/read) are queued locally, then acknowledged by phone/backend.

Conflict strategy:

- Last-write-wins on metadata fields.
- Message status transitions are monotonic (`pending -> sent -> delivered -> read`).
- Use idempotent mutation IDs so retries are safe.

Offline behavior:

- Keep a durable outbox on watch.
- Render cached conversations instantly.
- Retry with backoff when connectivity returns.

Reliability rules:

- Every mutation has `clientMutationId`.
- Every sync response returns `serverVersion` or `cursor`.
- Track sync watermark per device to support incremental sync.

## 4) OSS Repo Practices We Set Up (Copy These)

## 4.1 README Release Section (Template)

````md
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
````

## 4.2 Commit/Changelog Discipline

We used `git-cliff` with Conventional Commits (`cliff.toml`):

- `feat` -> Features
- `fix` -> Bug Fixes
- `perf` -> Performance
- `refactor` -> Refactoring
- `docs` -> Documentation
- `test` -> Tests
- `build` -> Build System
- `ci` -> CI
- `chore` -> Chores

Release notes are generated from tag history (`v*`).

## 5) GitHub CI Workflow Pattern (Push + PR)

Create `.github/workflows/ci.yml`:

```yaml
name: Android CI

on:
  push:
    branches: [main]
  pull_request:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Validate Gradle Wrapper
        uses: gradle/actions/wrapper-validation@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --licenses || true
          sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"

      - name: Build debug APK
        run: ./gradlew :app:assembleDebug
```

Why this is solid:

- Fast signal on every PR.
- Wrapper validation catches supply-chain issues.
- Uses deterministic JDK/SDK setup.

## 6) Tag-Driven Release Workflow Pattern

Create `.github/workflows/release.yml`:

```yaml
name: Release APK

on:
  push:
    tags:
      - "v*"

permissions:
  contents: write

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Validate Gradle Wrapper
        uses: gradle/actions/wrapper-validation@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Android SDK packages
        run: |
          yes | sdkmanager --licenses || true
          sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "build-tools;29.0.3"

      - name: Build APKs
        run: ./gradlew :app:assembleDebug :app:assembleRelease

      - name: Check signing secrets availability
        id: signing_ready
        env:
          KEY_B64: ${{ secrets.ANDROID_SIGNING_KEY_BASE64 }}
          KEY_ALIAS: ${{ secrets.ANDROID_ALIAS }}
          STORE_PASS: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          KEY_PASS: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: |
          if [ -n "$KEY_B64" ] && [ -n "$KEY_ALIAS" ] && [ -n "$STORE_PASS" ] && [ -n "$KEY_PASS" ]; then
            echo "enabled=true" >> "$GITHUB_OUTPUT"
          else
            echo "enabled=false" >> "$GITHUB_OUTPUT"
          fi

      - name: Sign release APK (optional)
        id: sign_release
        if: steps.signing_ready.outputs.enabled == 'true'
        uses: r0adkll/sign-android-release@v1
        with:
          releaseDirectory: app/build/outputs/apk/release
          signingKeyBase64: ${{ secrets.ANDROID_SIGNING_KEY_BASE64 }}
          alias: ${{ secrets.ANDROID_ALIAS }}
          keyStorePassword: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          keyPassword: ${{ secrets.ANDROID_KEY_PASSWORD }}
        env:
          BUILD_TOOLS_VERSION: "36.0.0"

      - name: Prepare release assets
        run: |
          set -euo pipefail
          mkdir -p dist
          cp app/build/outputs/apk/debug/app-debug.apk dist/app-debug.apk
          if [ -n "${{ steps.sign_release.outputs.signedReleaseFile }}" ] && [ -f "${{ steps.sign_release.outputs.signedReleaseFile }}" ]; then
            cp "${{ steps.sign_release.outputs.signedReleaseFile }}" dist/app-release-signed.apk
          elif [ -f "app/build/outputs/apk/release/app-release-unsigned.apk" ]; then
            cp app/build/outputs/apk/release/app-release-unsigned.apk dist/app-release-unsigned.apk
          fi
          (cd dist && sha256sum *.apk > SHA256SUMS.txt)

      - name: Generate release notes (git-cliff)
        uses: orhun/git-cliff-action@v4
        with:
          config: cliff.toml
          args: -vv --latest --strip header
        env:
          OUTPUT: CHANGES.md
          GITHUB_REPO: ${{ github.repository }}

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: ${{ github.ref_name }}
          generate_release_notes: false
          body_path: CHANGES.md
          files: |
            dist/*.apk
            dist/SHA256SUMS.txt
```

Why this is reliable:

- Tag event controls releases.
- Signed and unsigned fallback both supported.
- Checksums published for trust.
- Release notes generated automatically from commit history.

## 7) Secrets Needed for Optional Signing

Repository secrets used:

- `ANDROID_SIGNING_KEY_BASE64`
- `ANDROID_ALIAS`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_PASSWORD`

If missing, pipeline should still publish debug + unsigned assets.

## 8) Fast Start Checklist For New Messaging Repo

1. Copy this file into the new repo.
2. Add Wear Compose Foundation + Wear Material 3 dependencies.
3. Implement `AppScaffold -> ScreenScaffold -> ScalingLazyColumn` baseline.
4. Add `BasicSwipeToDismissBox` navigation shell.
5. Add `ci.yml` and `release.yml` from this handout.
6. Add `cliff.toml` and enforce Conventional Commits.
7. Add README badges + Releases section.
8. Test on at least one compact round emulator profile.
9. Run one full dry run: `git tag v0.1.0 && git push origin v0.1.0`.

## 9) Known Gotchas We Hit

- Mixing mobile and Wear components causes visual and behavior mismatch.
- Ignoring compact screen thresholds leads to clipped buttons/text.
- Rotary handlers do nothing without explicit focus.
- Release workflows fail silently when signing secrets are partially configured; check all four.
- Tag naming must match `v*` or release workflow will not run.
