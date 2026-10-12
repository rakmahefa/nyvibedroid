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
- **GitHubBuildClient / GitHubTokenStore:** GitHub REST API calls for the Remote Build Engine and separate AES-GCM storage for its token.
- **AiProvider / ChatGptCloudProvider:** provider-neutral interface for listing models and proposing a replacement for one open file.
- **ChatGptPlanClient / ChatGptCredentialStore:** open-source Sign in with ChatGPT OAuth/PKCE, identity-token validation, model listing, streamed Responses API requests, token refresh/revocation, and AES-GCM credential storage backed by Android Keystore.
- **Build Engine (rakmahefa/build):** owns Gradle invocation, APK collection, metadata, logs, and artifact publication.
- **Future local-model provider and GitHub workspace tools:** planned behind explicit provider and permission boundaries; not part of the first assistant version.

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

## AI assistant flow

1. User opens a supported text file and taps the AI action in the editor.
2. The user connects ChatGPT through the open-source Sign in with ChatGPT OAuth flow, grants the plan-usage permission, and chooses a model returned for that account.
3. The app sends the current in-memory contents of that one file and the user's instruction through the Responses API (`store: false`, streaming enabled). It does not send the whole workspace.
4. The model must return JSON containing the complete proposed file content and a summary. Invalid or oversized responses are rejected.
5. The app displays a bounded, linear-time single-hunk preview and asks the user to accept or refuse. Accept only replaces editor memory; the existing Save action is still required to write the file.
6. ChatGPT OAuth state/nonce and PKCE values are checked, the ID-token signature and claims are verified, and the required plan-usage scope is checked. OAuth credentials are encrypted using Android Keystore.

This provider uses the user's authorized ChatGPT plan, not an OpenAI API key. It does not inherit ChatGPT conversation history, website tools, or GitHub connector permissions. Project-wide context through a temporary GitHub repository requires a separate GitHub authorization and is not implemented in this first version.

## Development sequence

1. Keep Workspace, Git, and Remote Build stable.
2. Ship and manually validate the single-file ChatGPT proposal/review workflow.
3. Add a separately authorized temporary GitHub repository and least-privilege project tools so the assistant can inspect additional files; keep writes behind user approval and implement explicit cleanup.
4. Add or reconnect the intended local-model provider behind `AiProvider`.
5. Improve editor ergonomics (tabs, search, undo/redo, syntax highlighting) according to actual usage.

## Supported build targets

Android Gradle is currently supported. Linux and Windows remain disabled until their build scripts are implemented and verified.
