# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid is an Android development workspace built around local project files, code editing, Git version control, and remote Android builds.

> **Status:** The app includes a local workspace/file explorer and basic text editor, a Git repository manager powered by JGit, a remote Android build dashboard, and an initial ChatGPT Cloud assistant. The assistant proposes changes to the currently open file; changes are reviewed before they enter the editor and saved to disk only after an explicit Save action. A local-model provider and project-wide GitHub tools remain future work.

## What works today

### Workspace and text editor

- Choose a folder with Android's system folder picker and retain read/write access.
- Browse folders and files, create text files, edit common source/project files, save changes, or discard them.
- Edit UTF-8 text up to 1 MiB. Binary or unrecognized file types are not opened.
- Tap **Transform active Workspace into a managed repository** to import a SAF folder into app-private storage and initialize local Git. The original SAF folder stays untouched; after import, edits apply to the managed copy.
- Open a repository cloned or imported through the Git section in the same Workspace editor.

This is a lightweight first editor, not yet a full IDE. Syntax highlighting, tabs, search, undo/redo, and project-wide indexing remain future improvements.

### AI assistant (initial single-file workflow)

From an open text file, tap **✦ IA** to use the ChatGPT Cloud provider. Connect with **Continue with ChatGPT** and authorize ChatGPT plan usage in the browser. The open-source Sign in with ChatGPT flow uses OAuth/PKCE and does not require an OpenAI API key. Availability depends on the account and the explicit plan-usage consent.

- The model list is loaded for the connected account, and the user chooses a model.
- The assistant receives only the open file's current editor content and the written instruction, not the full workspace.
- The first version accepts files up to 64 KiB and 1,800 lines for AI requests.
- The answer is treated as a proposed full-file replacement and displayed as an added/removed-line preview. **Accept** inserts the proposal into the in-memory editor; **Refuse** leaves it unchanged. Saving to disk remains a separate, explicit action.
- OAuth credentials are encrypted with Android Keystore; access tokens are renewed when needed. Responses requests use streaming and `store: false`.
- The model provider is behind a small `AiProvider` interface. A local-model provider has not yet been implemented. Temporary GitHub repositories and project-wide tools are a separate follow-up; this version does not give ChatGPT general access to the user's GitHub account or the rest of the project.

The current ChatGPT plan-sharing integration uses the Responses API and the models made available to the connected account. It does not expose ChatGPT conversation history or automatically transfer the tools enabled in the ChatGPT website/app.

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

1. Create a fine-grained GitHub personal access token for `rakmahefa/build`. Grant **Actions: Read and write**. **Workspace ZIP** also needs **Contents: Read and write** on `rakmahefa/build` so the app can upload and delete a temporary private draft release.
2. Enter the token in NyVibeDroid and select **Connecter**. The app validates it and encrypts it with Android Keystore.
3. Choose **Workspace ZIP** to compile the active Workspace exactly as it exists on the phone, without committing or pushing it to a Git branch. Or choose **Branche GitHub** to build a remote repository/ref.
4. Start the compilation, then open its logs or download the debug APK to Downloads/NyVibeDroid.

The ZIP archive excludes `.git`, common build/cache directories, `local.properties`, local `.env` files (except examples), and common private-key file types. Source content is limited to 512 MiB and the compressed ZIP to 200 MiB. It is uploaded as a temporary private draft release asset in the Build Engine repository; the app removes that release after the workflow finishes, and a scheduled workflow cleans up abandoned uploads after 24 hours. Review your project files before uploading.

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
