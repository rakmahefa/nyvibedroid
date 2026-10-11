# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid aims to turn an Android phone into a portable development workstation, with a native editor, project and Git management, on-device AI assistance, and remote builds.

> **Status:** Early development. The current app is a minimal Jetpack Compose MVP; the editor, workspace manager, Git UI, local AI, and in-app build dashboard are not implemented yet.

## Current build workflow

The Android build is the only target currently enabled in the remote Build Engine. Linux and Windows support remains planned and is deliberately disabled until those builders can produce and verify real binaries.

### Continuous integration

The **Android CI** workflow runs on pushes to `main` and `agent`, pull requests targeting `main`, and manual dispatches. It runs unit tests, compiles debug and release APKs, verifies that APK files exist, and uploads the APKs plus the build log. Missing APKs fail the workflow instead of being treated as success.

### Release builds

Push a version tag to build and publish a GitHub Release:

```bash
git tag v0.1.0
git push origin v0.1.0
```

The release includes an installable debug APK, a release APK, the build log, and SHA-256 checksums. Without signing secrets, the release APK is explicitly labelled **unsigned**; the debug APK is signed with Android's debug key and is intended for testing.

For signed release APKs, configure all four repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit a keystore or signing passwords to the repository. Running the release workflow manually builds and uploads artifacts but only publishes a GitHub Release when the selected ref is a tag starting with `v`.

### Build locally

Requirements: JDK 17, Android SDK Platform 35, and Gradle 8.9. This repository does not currently include the Gradle Wrapper, so install Gradle 8.9 or open the project in Android Studio.

```bash
gradle --no-daemon test assembleDebug assembleRelease
```

The APKs are written to `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

## Remote Build Engine

The companion repository is [rakmahefa/build](https://github.com/rakmahefa/build). Its **Remote Build Engine** workflow can check out a repository and branch, build an Android Gradle project, then upload APKs, build metadata, and logs as a GitHub Actions artifact.

Public repositories can be built directly. To check out private target repositories, configure a read-only `BUILD_TARGET_TOKEN` Actions secret in the Build Engine repository.

## Planned features

- Code editor and file explorer for Android
- Workspace and project management
- Git operations: status, branches, commits, and synchronization
- Cactus AI integration for local code assistance
- Remote build request UI with status, logs, and artifact downloads
- Additional build targets once the corresponding builders are implemented

## Technology

- Kotlin
- Android SDK and Jetpack Compose
- Gradle
- Git and GitHub Actions
- Cactus AI (planned local integration)

## Repository separation

- **Android application:** https://github.com/rakmahefa/nyvibedroid
- **Build infrastructure:** https://github.com/rakmahefa/build
