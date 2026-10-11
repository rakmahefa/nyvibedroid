# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid is an Android development workspace built around local project files, code editing, Git version control, and remote Android builds.

> **Status:** The app includes a local workspace/file explorer and basic text editor, a Git repository manager powered by JGit, and a remote-build dashboard backed by the companion Build Engine. Local AI assistance is not implemented yet.

## What works today

### Workspace and text editor

- Choose a folder with Android's system folder picker and retain read/write access.
- Browse folders and files, create text files, edit common source/project files, save changes, or discard them.
- Edit UTF-8 text up to 1 MiB. Binary or unrecognized file types are not opened.
- Open a repository cloned through the Git section in the same Workspace editor.

This is a lightweight first editor, not yet a full IDE. Syntax highlighting, tabs, search, undo/redo, and project-wide indexing remain future improvements.

### Git repository management

Open the **Git** section to clone and manage a repository hosted on GitHub using HTTPS.

- Clone a public or private repository into NyVibeDroid's app-private project storage.
- Browse cloned repositories and select the active repository.
- View the current branch, latest commit, local changes, and a best-effort ahead/behind count when a remote tracking branch exists.
- Inspect staged and unstaged diff output.
- Create or switch local branches.
- Commit all current changes.
- Fetch, pull, and push the current branch.

To clone a repository, enter owner/repository or an HTTPS GitHub URL. The first version intentionally limits Git remotes to github.com over HTTPS; SSH and other Git hosting providers are not supported.

**Storage model:** repositories cloned in the Git screen live in the app-private files/projects/ directory so JGit can safely access the working tree and .git metadata. Open the selected repository from the Git screen into Workspace to edit it. A directory separately opened through Android's folder picker remains a general local workspace and is not automatically treated as a Git repository.

#### GitHub permissions and credentials

The Git section stores its credential separately from the Remote Build token and encrypts it with Android Keystore. Public repositories can be cloned without a token. Private clone/fetch requires a token with repository Contents: read access; pushing to a repository requires Contents: read and write. If a token is typed into the Git screen, the next Fetch/Pull/Push action uses it and saves it encrypted on-device. A Push without a saved or entered token is stopped with a clear authentication message. Use a fine-grained personal access token limited to the repositories you trust and never share it.

The Remote Build screen uses a separate token for Actions access to rakmahefa/build. These credentials have distinct storage and purposes.

Git protects against invalid repository URLs and only clones from https://github.com/. Local Git commands run against the selected app-managed clone; there is no automatic commit or push.

### In-app remote Android builds

The **Remote Build** screen triggers the companion Build Engine, tracks a workflow, displays build logs, and downloads the debug APK directly to the phone.

1. Create a fine-grained GitHub personal access token for the rakmahefa/build repository. Grant Actions: Read and write.
2. Enter the token in NyVibeDroid and select **Connecter**. The app validates it and encrypts it with Android Keystore.
3. In **Git**, select a cloned repository and tap **Set as active Workspace and open**. In **Remote Build**, **Use current workspace / active Git repository** fills its GitHub repository and current branch. If Workspace points to an unrelated Android folder, the explicitly selected Git repository is used instead; an arbitrary SAF folder is not uploaded automatically. You may still enter a target repository in owner/name format and a branch, tag, or commit manually.
4. Commit and Push local changes first. Remote Build checks out the branch from GitHub; it does not transmit uncommitted local files from the phone.
5. Start the compilation, then open its logs or download the debug APK to Downloads/NyVibeDroid.

The active build refreshes while the screen is open and the last run is remembered across app restarts. The owner has manually verified the end-to-end flow: connection, project compilation, and APK download.

**Current build limit:** Android Gradle projects only. Linux and Windows remain disabled until their builders produce and verify real binaries.

#### Access to private build targets

The Remote Build token calls the GitHub Actions API on rakmahefa/build; it is not passed to the target build process. Public target repositories can be checked out using the workflow's default token. For a private target repository, configure a read-only BUILD_TARGET_TOKEN Actions secret in the Build Engine repository with Contents: read access to the target.

The downloaded debug APK is signed with Android's debug key and is intended for testing. The workflow also produces a release APK, which may be unsigned.

## Architecture and development direction

Responsibilities remain intentionally separated:

- **WorkspaceRepository / WorkspaceScreen:** scoped file access and basic text editing.
- **GitRepositoryManager / GitRepositoryScreen:** JGit operations for repositories cloned into app-private storage.
- **GitCredentialStore:** separately encrypted Git credentials using Android Keystore.
- **GitHubBuildClient / GitHubTokenStore:** workflow requests, status, build artifacts, logs, and the separate Remote Build credential.
- **Build Engine:** compiles the target project in GitHub Actions and supplies APKs, logs, and metadata.

The next major feature is local AI assistance that can read and propose edits to selected project files with explicit user control. Editor improvements can follow actual usage rather than introducing premature complexity.

See docs/ARCHITECTURE.md for component boundaries and data flow.

## Continuous integration

The **Android CI** workflow runs on pushes to main and agent, pull requests targeting main, and manual dispatches. It runs unit tests, compiles debug and release APKs, verifies outputs, and uploads the APKs plus build log.

## Release builds

Push a version tag to build and publish a GitHub Release:

~~~bash
git tag v0.1.0
git push origin v0.1.0
~~~

The release includes a debug APK, a release APK, build log, and SHA-256 checksums. Without signing secrets, the release APK is explicitly labelled **unsigned**; the debug APK is signed with Android's debug key.

For a signed release APK, configure these repository Actions secrets:

- ANDROID_KEYSTORE_BASE64: base64-encoded release keystore
- ANDROID_KEYSTORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

Never commit a keystore or signing passwords. A manually triggered release workflow uploads artifacts but only publishes a GitHub Release when the selected ref is a version tag beginning with v.

## Build locally

Requirements: JDK 17, Android SDK Platform 35, and Gradle 8.9. This repository does not currently include the Gradle Wrapper, so install Gradle 8.9 or open the project in Android Studio.

~~~bash
gradle --no-daemon clean test assembleDebug assembleRelease
~~~

The APKs are written to app/build/outputs/apk/debug/ and app/build/outputs/apk/release/.

## Technology

- Kotlin and Jetpack Compose
- Android Storage Access Framework and DocumentFile
- Eclipse JGit for in-app Git operations
- Android Keystore for encrypted credential storage
- GitHub Actions API and the remote Build Engine
- Gradle
- Cactus AI (planned local integration)

## Repository separation

- **Android application:** https://github.com/rakmahefa/nyvibedroid
- **Build infrastructure:** https://github.com/rakmahefa/build
