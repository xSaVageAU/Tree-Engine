# Design Doc: Tree Engine Standalone (Wails Wrapper)

## 1. Project Goal
Create a standalone desktop application ("The Launcher") that wraps the existing Tree Engine mod. This removes the need for users to manually install Fabric or manage mods. The app will manage the Java process and serve the web UI.

## 2. Core Philosophy: "Bring Your Own Server" (BYOS)
To avoid legal issues distributing Mojang assets, the user must provide a folder containing a standard Fabric Server. The Launcher acts as an automation tool for that server.

## 3. Repository Structure (Monorepo)
The project will be housed in a single repository with two main directories.

ROOT/
├── mod/                 [Java] The existing Fabric Mod source code.
│   ├── src/
│   └── build.gradle
│
├── app/                 [Go/Wails] The new Desktop Launcher.
│   ├── main.go          (Backend logic)
│   ├── frontend/        (Launcher UI: HTML/JS)
│   ├── wails.json
│   └── internal/        (Process management code)
│
└── README.md

## 4. User Workflow

### Step 1: Dashboard (Wails UI)
*   **Input:** User drags and drops a Fabric Server folder into the app window.
*   **Validation:** App checks for `fabric-server-launch.jar` (or equivalent).
*   **Auto-Inject:** 
    *   App checks the `mods/` folder inside the dropped directory.
    *   If `tree-engine.jar` is missing, the app automatically copies its bundled internal version into that folder.

### Step 2: Boot Sequence
*   **Launch:** User clicks "Start Engine".
*   **Process:** Go spawns the Java process in the background (`-Djava.awt.headless=true`).
*   **EULA Check:** 
    *   Go monitors the stdout logs.
    *   If "EULA not accepted" is detected, the UI prompts the user to agree.
    *   Go writes `eula=true` to the file and restarts.

### Step 3: The Editor
*   **Handover:** Go waits for the log message: `Done (x.xs)!`
*   **Redirect:** The Wails window navigates from the "Loading..." screen to `http://localhost:3000`.
*   **Result:** The user is now using the Tree Engine mod interface.

## 5. Technical Stack

### Backend (Go)
*   **Process Management:** `os/exec` to run Java.
*   **Stream Processing:** Pipe `stdout` to parse server logs for status updates (Loading %, Done, EULA).
*   **File I/O:** Copying the mod jar and editing `eula.txt`.

### Frontend (Wails Webview)
*   **State 1 (Launcher):** Simple HTML/CSS interface for folder selection.
*   **State 2 (Editor):** The actual Tree Engine Web UI (served by Java, viewed in Wails).

## 6. Development Strategy
1.  **Build Mod:** Compile `tree-engine.jar` from the `mod/` directory.
2.  **Embed:** Place that jar into the `app/` directory as an embedded asset.
3.  **Build App:** Compile the Wails app, which now contains the mod inside it.
4.  **Distribute:** User downloads one `.exe` or `.app` file.