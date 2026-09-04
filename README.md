# PrivacyGuard

Kotlin + Jetpack Compose Android project with a LibXposed API baseline.

## Fixed build issue

The Java compiler was targeting JVM 1.8 while Kotlin targeted JVM 17.
This revision explicitly sets both Java `sourceCompatibility`/`targetCompatibility`
and Kotlin `jvmTarget` to 17.

The old unavailable `de.robv.android.xposed:api:82` dependency was also replaced
with `io.github.libxposed:api:102.0.0`.

## Build

GitHub Actions:
`gradle :app:assembleDebug`

The ZIP is already structured as repository root: there is no enclosing folder.
