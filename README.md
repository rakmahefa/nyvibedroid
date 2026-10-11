# nyvibedroid
# NyVibeDroid

**A mobile Vibe Coding environment for Android. Code, build, and create anywhere.**

NyVibeDroid aims to bring a modern development environment to Android smartphones, combining local AI-assisted coding, project management, Git integration, and cloud-powered builds.

> **Project status:** In development. Features described below represent the project vision and may not all be implemented yet.

## Vision

Turn an Android smartphone into a portable development workstation. Write and modify code locally, use on-device AI assistance, and delegate heavy compilation tasks to GitHub Actions.

## Core Features

- **Code Editor** — Browse and edit project files directly on Android.
- **Local AI** — Integrate Cactus AI for private, on-device code generation and assistance.
- **Project Manager** — Organize workspaces and navigate project structures.
- **Git Integration** — Manage repositories, branches, commits, and synchronization.
- **Integrated Terminal** — Run development commands from the mobile environment.
- **Cloud Build Engine** — Trigger remote builds and retrieve artifacts and logs.
- **Multi-platform Build Vision** — Support Android APKs, Linux applications, and Windows executables through compatible build workflows.

## Architecture

```text
┌─────────────────────────────────┐
│       NyVibeDroid (Android)     │
│                                 │
│  Editor · Projects · Git        │
│  Local AI · Build Dashboard     │
└───────────────┬─────────────────┘
                │
       ┌────────┴────────┐
       │                 │
       ▼                 ▼
┌──────────────┐  ┌─────────────────┐
│  Cactus AI   │  │  GitHub Actions │
│              │  │  Build Engine   │
│ Local coding │  │  Remote builds  │
└──────────────┘  └────────┬────────┘
                           │
                           ▼
                  ┌─────────────────┐
                  │ Artifacts & Logs│
                  │ APK / Linux /   │
                  │ Windows outputs │
                  └─────────────────┘
```

Local AI inference is intended to keep code processing on-device. Source files sent to remote repositories or build runners are subject to the corresponding synchronization and cloud-build operations.

## Repositories

- **NyVibeDroid — Android application:** https://github.com/rakmahefa/nyvibedroid
- **Build Engine — CI/CD infrastructure:** https://github.com/rakmahefa/build

The Build Engine repository is intended to contain reusable GitHub Actions workflows, build scripts, and build-related configuration rather than the Android application's source code.

## Technology Stack

- Kotlin
- Android SDK
- Jetpack Compose
- Gradle
- Git
- GitHub Actions
- Cactus AI (planned local AI integration)

## Getting Started

Clone the repository:

```bash
git clone https://github.com/rakmahefa/nyvibedroid.git
cd nyvibedroid
```

Open the project in Android Studio and allow Gradle synchronization to complete.

When the Gradle Wrapper is configured, build the application:

```bash
./gradlew assembleDebug
```

For a release build:

```bash
./gradlew assembleRelease
```

The expected APK output directory is:

```text
app/build/outputs/apk/
```

A successful build also depends on the project's current Gradle configuration, SDK requirements, and dependencies.

## Cloud Build Workflow

The intended workflow is:

1. Select a project and target platform.
2. Submit a build request to GitHub Actions.
3. Check out the requested source revision.
4. Run the appropriate build pipeline.
5. Collect build logs and generated artifacts.
6. Download the result to the Android device.

## Development Roadmap

- [ ] Establish a reliable Android release pipeline.
- [ ] Deliver an installable Android APK.
- [ ] Build the mobile project explorer and code editor.
- [ ] Add workspace and Git management.
- [ ] Integrate local Cactus AI.
- [ ] Connect NyVibeDroid to the remote Build Engine.
- [ ] Retrieve build status, logs, and artifacts.
- [ ] Expand support for compatible Linux and Windows build targets.

## Project Goal

**A portable development environment where your ideas become working software, directly from your Android device.**

