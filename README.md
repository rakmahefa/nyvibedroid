# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid aims to turn an Android phone into a portable development workstation, with a native editor, project and Git management, on-device AI assistance, and remote builds.

> **Status:** Early development. The current app provides an in-app Android remote-build dashboard. The code editor, workspace manager, Git UI, and local AI integration remain planned features.

## In-app remote builds

The **Remote Builder** screen can trigger the companion Build Engine, track the workflow, show build logs, and download the debug APK directly to the phone.

1. Create a fine-grained GitHub personal access token for the rakmahefa/build repository. Grant **Actions: Read and write**. Keep the token private.
2. Enter the token in NyVibeDroid and select **Connecter**. The app validates the token and encrypts it with Android Keystore on the device.
3. Enter a target repository in owner/name format and a branch, tag, or commit (the default is main).
4. Tap **Lancer la compilation**. The app triggers the Remote Build Engine workflow and starts tracking the returned run.
5. Once the build finishes, open its logs or download the debug APK to Downloads/NyVibeDroid.

The screen refreshes an active build every eight seconds while open and remembers the last run across app restarts. It also offers a manual refresh and a link to the corresponding GitHub Actions run.

**Current limit:** Android Gradle projects only. Linux and Windows are deliberately unavailable until their builders produce and verify actual binaries.

### Access and private target repositories

The PAT is used by the app to call the GitHub Actions API on rakmahefa/build; it is not sent to the target project's build process. Public target repositories can be checked out using the workflow's default token. To build a private target repository, configure a read-only BUILD_TARGET_TOKEN Actions secret in the rakmahefa/build repository, with Contents: read access to that target.

The app downloads the build-android Actions artifact. The debug APK is chosen because it is signed with the Android debug key and is suitable for testing; a release APK is also produced by the workflow but may be unsigned.

## Continuous integration

The **Android CI** workflow runs on pushes to main and agent, pull requests targeting main, and manual dispatches. It runs unit tests, compiles debug and release APKs, verifies that APK files exist, and uploads the APKs plus the build log. Missing APKs fail the workflow instead of being treated as success.

## Release builds

Push a version tag to build and publish a GitHub Release:

~~~bash
git tag v0.1.0
git push origin v0.1.0
~~~

The release includes an installable debug APK, a release APK, the build log, and SHA-256 checksums. Without signing secrets, the release APK is explicitly labelled **unsigned**; the debug APK is signed with Android's debug key and is intended for testing.

For signed release APKs, configure all four repository Actions secrets:

- ANDROID_KEYSTORE_BASE64: base64-encoded release keystore
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Never commit a keystore or signing passwords to the repository. Running the release workflow manually builds and uploads artifacts but only publishes a GitHub Release when the selected ref is a tag starting with v.

## Build locally

Requirements: JDK 17, Android SDK Platform 35, and Gradle 8.9. This repository does not currently include the Gradle Wrapper, so install Gradle 8.9 or open the project in Android Studio.

~~~bash
gradle --no-daemon test assembleDebug assembleRelease
~~~

The APKs are written to app/build/outputs/apk/debug/ and app/build/outputs/apk/release/.

## Technology

- Kotlin and Jetpack Compose
- Android Keystore for encrypted local credential storage
- GitHub Actions API and the remote Build Engine
- Gradle
- Cactus AI (planned local integration)

## Repository separation

- **Android application:** https://github.com/rakmahefa/nyvibedroid
- **Build infrastructure:** https://github.com/rakmahefa/build
