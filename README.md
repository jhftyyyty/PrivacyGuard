# PrivacyGuard

Kotlin + Jetpack Compose Android application with a modern LibXposed API 102 module entry point.

The previous build failed because `de.robv.android.xposed:api:82` was not available
from the configured Maven repositories. This revision uses
`io.github.libxposed:api:102.0.0`, which is published to Maven Central.

The modern API uses `META-INF/xposed/java_init.list` rather than the legacy
`assets/xposed_init`.

## Build

`gradle :app:assembleDebug`

## GitHub Actions

Push the repository to GitHub. The workflow builds the debug APK and uploads it
as an artifact.

## Note

This is a baseline privacy module. It currently demonstrates a safe empty-cursor
response for sensitive ContentResolver queries. Additional access paths can be
added after confirming the build and LSPosed loading on the target device.
