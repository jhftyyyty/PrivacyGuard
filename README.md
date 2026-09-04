# Privacy Guard

Modern LibXposed/LSPosed privacy-filtering baseline for selected application scopes.

## Build

GitHub Actions uses JDK 17 and Gradle 8.8. The Android Gradle Plugin is pinned to 8.5.2 and Kotlin to 2.0.21 to avoid the JVM/Gradle compatibility mismatch seen with newer runner defaults.

## LSPosed

Enable the module and select the target applications in LSPosed Manager. The module does not inject into Android/provider processes listed in `XposedEntry.kt`.

The module uses the modern LibXposed API 102 metadata under `META-INF/xposed`.
