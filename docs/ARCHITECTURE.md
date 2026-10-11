# NyVibeDroid Architecture

## Goals

Keep local file access, editor state, Git repository operations, GitHub Actions communication, and credential storage separated. Build Engine owns compilation. The app manages projects, version-control actions, the editor, and requests remote builds.

## Components

- **Navigation:** Jetpack Compose sections for Workspace, Git, and Remote Build.
- **WorkspaceScreen:** scoped folder browsing, file list, text editor, create/save/discard actions, and error states.
- **WorkspaceRepository:** uses Android's Storage Access Framework for arbitrary user-selected folders, and a guarded file-backed path for repositories managed under app-private storage.
- **GitRepositoryScreen:** repository clone/select controls, status, local branches, diff view, commit form, fetch/pull/push actions, and an explicit action to set the selected repository as the active Workspace.
- **GitRepositoryManager:** JGit operations for repositories stored below files/projects/. Restricts remote clone URLs to HTTPS on github.com and validates managed working-tree paths.
- **GitCredentialStore:** AES-GCM encryption backed by Android Keystore for the Git credential, stored separately from the Remote Build token. The Git screen passes a newly entered token to Fetch/Pull/Push and persists it encrypted before the remote operation.
- **GitHubBuildClient:** GitHub REST API calls to trigger and poll the Remote Build Engine workflow, discover the build-android artifact, download APKs and read logs.
- **GitHubTokenStore:** independent AES-GCM storage for the Remote Build token.
- **Build Engine (rakmahefa/build):** owns Gradle invocation, APK collection, metadata, logs, and artifact publication.
- **Planned AI layer:** local model integration with explicit access to selected files.

## Workspace access and editor

The Workspace screen can import an Android SAF-selected folder into app-private `files/projects/workspaces/` storage and initialize it as a local JGit repository. The selected source folder is copied, not modified. Once imported, Workspace switches to the managed copy; Git and Workspace then operate on the same files.

1. User may select an arbitrary folder with Android's document-tree picker; NyVibeDroid keeps the granted URI.
2. For app-managed Git clones, the workspace root is a file URI created by the Git manager and checked to be inside files/projects/.
3. File listing is limited to that root; the Git metadata directory (.git) is hidden from the editor.
4. Recognized text files are opened as UTF-8 and edited in the text editor.
5. Save writes to the existing document and the Git status can then report the change.
6. Text editing and creating text files are limited to 1 MiB and supported text extensions/MIME types. Unknown and binary formats are not opened.

Git clones use private app storage rather than arbitrary SAF trees because JGit needs filesystem access to both the working files and .git metadata. The arbitrary-folder workspace is still useful for browsing and editing, but it is not treated as a Git working tree automatically.

## Git flow

1. User enters a GitHub repository as owner/repository or a GitHub HTTPS URL.
2. Git manager validates the host and destination, then clones the repository under files/projects/owner/repository.
3. User opens the managed clone in Workspace to edit files.
4. JGit reports staged, unstaged, missing, deleted, untracked, and conflicting paths and supplies diff output.
5. User may create/switch local branches and create a commit containing all current changes.
6. Fetch, pull, and push use the remote URL and a separate encrypted Git token when required.

The first implementation intentionally does not implement SSH, alternate Git hosts, interactive conflict resolution, rebase workflows, partial staging, or worktree management. Users should commit or back up important changes before performing a pull or switching branches.

## Remote build flow

1. User chooses **Workspace ZIP** to build the active local Workspace, or **Branche GitHub** to build a remote repository/ref.
2. For ZIP mode, the app streams workspace files into a bounded archive, excluding Git metadata, common generated caches, and known local secret/config files. It uploads the archive as an asset on a temporary private draft release in `rakmahefa/build`.
3. App dispatches `remote-build.yml` with the source mode and, for ZIP mode, the temporary release ID. The Build Engine safely validates and extracts the archive into `target/` before using the same Android Gradle builder.
4. App locates the new run, records its ID, and polls GitHub Actions.
5. When the run completes, the app discovers the build-android artifact, removes the temporary release and tag, and makes logs/APKs available for download.
6. A scheduled Build Engine workflow deletes abandoned workspace upload releases older than 24 hours.

The Remote Build token is only for the GitHub Actions API and is not forwarded to the target build process. Private build targets require a separate read-only BUILD_TARGET_TOKEN Actions secret in the Build Engine repository.

## Development sequence

1. Workspace selection, explorer, and basic text editing.
2. Git clone/status/diff/branch/commit/fetch/pull/push for app-managed clones.
3. Local AI assistance that can read selected files and propose changes under explicit user control.
4. Improve editor ergonomics (tabs, search, undo/redo, syntax highlighting) according to actual needs.

## Supported build targets

Android Gradle is currently supported. Linux and Windows remain disabled until their build scripts are implemented and verified.
