# NyVibeDroid Architecture

## Goals

Keep local file access, editor state, GitHub Actions communication, and credential storage separated. The Build Engine is responsible for compiling projects; the app is responsible for selecting a workspace, requesting builds, showing results, and editing project text.

## Components

- **Navigation:** small Jetpack Compose section switcher for Workspace and Remote Build.
- **WorkspaceScreen:** folder browsing, file list, text editor, create/save/discard actions, and visible error states.
- **WorkspaceRepository:** uses Android's Storage Access Framework and DocumentFile for operations inside a user-selected folder.
- **GitHubBuildClient:** HTTPS calls to GitHub REST APIs; dispatches and polls the Remote Build Engine workflow in rakmahefa/build, discovers the build-android artifact, and reads build logs.
- **GitHubTokenStore:** encrypts the fine-grained personal access token using an AES-GCM key from Android Keystore and stores only ciphertext and IV in app-private preferences.
- **Build Engine (rakmahefa/build):** owns Gradle invocation, APK collection, build metadata, logs, and artifact publication.
- **Planned Git layer:** repository status, branches, commits, and synchronization for a selected workspace.
- **Planned AI layer:** local model integration and code assistance.

## Workspace access and editor

1. User chooses a folder using Android's system document-tree picker.
2. App takes persistable read/write permission for that tree URI and remembers the URI in app-private preferences.
3. File listing is performed only below the selected tree; directories are sorted before files.
4. Recognized text files are opened as UTF-8 and edited in the text editor.
5. Save writes the updated text to the original document. Unsaved changes can be discarded.
6. Text editing and creating text files are limited to 1 MiB content and supported text extensions/MIME types. Unknown and binary formats are not opened by the editor.

The initial editor deliberately does not copy projects into a private sandbox or crawl device storage. A later project-import or Git layer should preserve clear ownership of file paths and permissions.

## Remote build flow

1. User supplies target repository (owner/name) and branch/tag/commit.
2. App validates the token and sends workflow_dispatch to rakmahefa/build with the Android platform input.
3. App identifies the new run, records its ID, and queries GitHub Actions for the status.
4. On completion, app discovers the build-android artifact.
5. User opens bounded log excerpts or downloads the debug APK to Downloads.

The personal access token is used only for the GitHub API. It is not forwarded to the target build. Private target repositories require a separate read-only BUILD_TARGET_TOKEN Actions secret configured in the Build Engine repository.

## Development sequence

1. Local workspace selection, explorer, and basic text editing.
2. Git integration for the selected workspace: status, branches, diff, commit, fetch/pull/push.
3. Local AI assistance that can operate on selected files with explicit user control.
4. Improve editor ergonomics: tabs, search, undo/redo, and syntax highlighting as justified by use.

Remote builds remain an independent capability available through the existing Build Engine interface.

## Supported build targets

Android Gradle is currently supported. Linux and Windows remain disabled until their build scripts are implemented and verified.
