# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid is an Android development workspace being built around three focused areas: local project files, a text editor, and remote Android builds.

> **Status:** The app currently includes a local workspace/file explorer with a basic text editor and save flow, plus a remote-build dashboard backed by the companion Build Engine. Git operations and local AI assistance are not implemented yet.

## What works today

### Workspace and text editor

- Choose a project folder with Android's system folder picker.
- Keep persistent read/write access to that selected folder.
- Browse folders and files; folders appear before files and can be navigated with breadcrumbs and a back control.
- Create new text files in the current folder.
- Open and edit common source and project files (Kotlin, Java, Gradle, XML, JSON, Markdown, YAML, text, scripts, and more).
- Save edits back to the original file, or discard unsaved changes.
- Keep files on the user's device; access is limited to the folder explicitly granted through the system picker.

For safety and responsiveness, the initial editor accepts UTF-8 text files up to 1 MiB. Binary or unrecognized file types are not opened in the editor. This is a lightweight first editor, not yet a full IDE: syntax highlighting, tabs, search, undo/redo, and project-wide indexing remain future improvements.

### In-app remote Android builds

The **Remote Build** screen triggers the companion Build Engine, tracks a workflow, displays build logs, and downloads the debug APK directly to the phone.

1. Create a fine-grained GitHub personal access token for the rakmahefa/build repository. Grant **Actions: Read and write**. Keep the token private.
2. Enter the token in NyVibeDroid and select **Connecter**. The app validates it and encrypts it with Android Keystore on the device.
3. Enter a target repository in owner/name format and a branch, tag, or commit (the default is main).
4. Tap **Lancer la compilation**. The app triggers the Remote Build Engine workflow and tracks its execution.
5. Once the build finishes, open its logs or download the debug APK to Downloads/NyVibeDroid.

The screen refreshes an active build while open and remembers the last run across app restarts. It also offers a manual refresh and a link to the matching GitHub Actions run. The owner has manually verified the end-to-end path: connection, successful project compilation, and APK download.

**Current build limit:** Android Gradle projects only. Linux and Windows remain disabled until their builders produce and verify real binaries.

### Access and private target repositories

The personal access token is used by the app to call the GitHub Actions API on rakmahefa/build; it is not passed into the target build process. Public target repositories can be checked out with the workflow's default token. For private target repositories, configure a read-only BUILD_TARGET_TOKEN Actions secret in the Build Engine repository, with Contents: read access to the target.

The app downloads the build-android Actions artifact and chooses the debug APK, which is signed with Android's debug key and intended for testing. A release APK is also produced by the workflow but may be unsigned.

## Architecture and development direction

The app is being kept deliberately small and separated by responsibility:

- **WorkspaceRepository:** scoped folder/file access and basic text-file operations.
- **WorkspaceScreen:** file explorer and editor interface.
- **GitHubBuildClient:** calls the GitHub Actions API and downloads the build artifact.
- **GitHubTokenStore:** encrypts the GitHub token with an Android Keystore AES-GCM key.
- **Build Engine:** builds the target project in GitHub Actions and supplies APKs, logs, and metadata.

The next planned layers are Git status/branch/commit/sync for a selected workspace, then local AI assistance. They should be added without mixing project-file access into the build client or duplicating build logic already owned by the Build Engine.

See the architecture notes in docs/ARCHITECTURE.md for the component boundaries and flow.

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
gradle --no-daemon clean test assembleDebug assembleRelease
~~~

The APKs are written to app/build/outputs/apk/debug/ and app/build/outputs/apk/release/.

## Technology

- Kotlin and Jetpack Compose
- Android Storage Access Framework and DocumentFile for scoped workspace access
- Android Keystore for encrypted local credential storage
- GitHub Actions API and the remote Build Engine
- Gradle
- Cactus AI (planned local integration)

## Repository separation

- **Android application:** https://github.com/rakmahefa/nyvibedroid
- **Build infrastructure:** https://github.com/rakmahefa/build
