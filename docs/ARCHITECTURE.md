# NyVibeDroid Architecture

## Components

- **Presentation:** Jetpack Compose remote-build dashboard.
- **GitHub client:** HTTPS calls to the GitHub REST API; triggers and polls the Remote Build Engine workflow in rakmahefa/build.
- **Credential storage:** Android Keystore AES-GCM key encrypts the fine-grained personal access token before it is stored in app-private preferences.
- **Artifact handling:** Downloads the build-android workflow artifact, displays bounded log excerpts, and copies the debug APK into Downloads.
- **Planned workspace/editor layer:** project and file management, code editing, and Git operations.
- **Planned AI layer:** local model integration.

## Remote build flow

1. User supplies target repository (owner/name) and branch/tag/commit.
2. App validates the token and sends workflow_dispatch to rakmahefa/build with the Android platform input.
3. App records the returned run ID and queries the GitHub Actions API for status.
4. When the run completes, app discovers the build-android artifact.
5. User opens logs or downloads the signed debug APK.

The app never needs to store the token in source code or send it to the target build. Private target repositories require a separate read-only BUILD_TARGET_TOKEN Actions secret configured in the Build Engine repository.

## Development loop

1. Edit code on device (planned).
2. Ask local AI for changes (planned).
3. Commit and synchronize through Git (planned).
4. Start a remote Android build.
5. Inspect logs, download the debug APK, and install/test it.

## Supported build targets

Android Gradle is currently supported. Linux and Windows remain disabled until their build scripts are implemented and verified.
