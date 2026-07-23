package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	modassets "app/internal/assets"
	"app/internal/instance"
	"app/internal/mcassets"
	"app/internal/serverproc"

	wailsruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

// waitForHTTPReady polls url until it returns any HTTP response (not just a
// successful status - even a 4xx means the server is genuinely answering) or
// the timeout elapses. Used to bridge the gap between "socket is bound" and
// "a real client request round-trips successfully".
func waitForHTTPReady(ctx context.Context, url string, timeout time.Duration) bool {
	client := &http.Client{Timeout: 2 * time.Second}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err == nil {
			resp, err := client.Do(req)
			if err == nil {
				resp.Body.Close()
				return true
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	return false
}

// Phase is the high-level state the frontend renders against.
type Phase string

const (
	PhaseNeedsSetup Phase = "needs_setup"
	PhaseSettingUp  Phase = "setting_up"
	PhaseStopped    Phase = "stopped" // set up, server not currently running
	PhaseStarting   Phase = "starting"
	PhaseRunning    Phase = "running" // server up, web editor confirmed live
	PhaseError      Phase = "error"
)

// StatusPayload is emitted to the frontend on every state change.
type StatusPayload struct {
	Phase   Phase  `json:"phase"`
	Message string `json:"message"`
	Port    int    `json:"port"`
	Token   string `json:"token"`
}

// App is the Wails-bound backend. All exported methods are callable from the
// frontend; state changes are pushed via wailsruntime.EventsEmit rather than
// polled, so the UI stays in sync with setup progress and server log output.
type App struct {
	ctx    context.Context
	layout instance.Layout

	mu       sync.Mutex
	state    *instance.State
	settings *instance.Settings
	proc     *serverproc.Process
	phase    Phase
}

func NewApp() *App {
	layout, err := instance.DefaultLayout()
	if err != nil {
		// Extremely unlikely (UserCacheDir failing) - fall back to a zero
		// Layout so the app still starts and can surface the error in the UI.
		layout = instance.Layout{}
	}
	return &App{layout: layout, phase: PhaseNeedsSetup}
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx

	state, err := instance.LoadState(a.layout.StateFile)
	if err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to read launcher state: %v", err))
		return
	}
	settings, err := instance.LoadSettings(a.layout.SettingsFile)
	if err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to read launcher settings: %v", err))
		return
	}

	a.mu.Lock()
	a.state = state
	a.settings = settings
	if state.SetupComplete {
		a.phase = PhaseStopped
	}
	a.mu.Unlock()

	if state.SetupComplete && settings.AutoStartOnLaunch {
		a.StartServer()
		return
	}

	a.emitCurrentStatus()
}

// GetSettings returns the current launcher settings for the frontend's
// Settings screen.
func (a *App) GetSettings() instance.Settings {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.settings == nil {
		return instance.Settings{}
	}
	return *a.settings
}

// SaveSettings persists updated launcher settings, taking effect immediately
// where applicable (e.g. auto-start is checked on the next launch).
func (a *App) SaveSettings(s instance.Settings) error {
	a.mu.Lock()
	a.settings = &s
	a.mu.Unlock()
	return s.Save(a.layout.SettingsFile)
}

// GetStatus returns the current phase/state for the frontend's initial render.
func (a *App) GetStatus() StatusPayload {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.statusPayloadLocked("")
}

func (a *App) statusPayloadLocked(message string) StatusPayload {
	p := StatusPayload{Phase: a.phase, Message: message}
	if a.state != nil {
		p.Port = a.state.Port
		p.Token = a.state.AuthToken
	}
	return p
}

func (a *App) emitStatus(phase Phase, message string) {
	a.mu.Lock()
	a.phase = phase
	payload := a.statusPayloadLocked(message)
	a.mu.Unlock()

	if a.ctx != nil {
		wailsruntime.EventsEmit(a.ctx, "status", payload)
	}
}

func (a *App) emitCurrentStatus() {
	a.mu.Lock()
	payload := a.statusPayloadLocked("")
	a.mu.Unlock()
	if a.ctx != nil {
		wailsruntime.EventsEmit(a.ctx, "status", payload)
	}
}

func (a *App) emitLog(line string) {
	if a.ctx != nil {
		wailsruntime.EventsEmit(a.ctx, "server:log", line)
	}
}

// RunSetup performs first-run setup: downloading Java (if needed), the
// Fabric server, Fabric API, and injecting the bundled mod. eulaAccepted must
// be true - the frontend must show the EULA and get explicit agreement
// before calling this.
func (a *App) RunSetup(eulaAccepted bool) {
	a.emitStatus(PhaseSettingUp, "Starting setup...")

	state, err := instance.Run(a.ctx, a.layout, eulaAccepted, func(stage, message string) {
		a.emitStatus(PhaseSettingUp, message)
	})
	if err != nil {
		a.emitStatus(PhaseError, err.Error())
		return
	}

	a.mu.Lock()
	a.state = state
	a.mu.Unlock()

	a.emitStatus(PhaseStopped, "Setup complete.")
}

// StartServer boots the managed Minecraft server using the previously
// persisted setup state.
func (a *App) StartServer() {
	a.mu.Lock()
	state := a.state
	alreadyRunning := a.proc != nil && a.proc.Running()
	a.mu.Unlock()

	if state == nil || !state.SetupComplete {
		a.emitStatus(PhaseError, "Cannot start: setup has not completed yet.")
		return
	}
	if alreadyRunning {
		return
	}

	// Rewrite the mod jar and config.json from what's embedded in this build
	// on every start (not just at initial setup), so an existing instance
	// always picks up launcher updates - new config flags, mod fixes -
	// without requiring a full re-setup.
	if err := os.WriteFile(filepath.Join(a.layout.ModsDir(), "tree-engine.jar"), modassets.TreeEngineJar, 0o644); err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to update mod jar: %v", err))
		return
	}
	if err := instance.WriteModConfig(a.layout, state.Port, state.AuthToken); err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to write mod config: %v", err))
		return
	}

	// Backfill GamePort for instances set up before it existed.
	if state.GamePort == 0 {
		gamePort, err := instance.FindFreePort(25565)
		if err != nil {
			a.emitStatus(PhaseError, fmt.Sprintf("Failed to find a free game port: %v", err))
			return
		}
		state.GamePort = gamePort
		if err := state.Save(a.layout.StateFile); err != nil {
			a.emitStatus(PhaseError, fmt.Sprintf("Failed to save launcher state: %v", err))
			return
		}
	}
	// Rewritten every start (not just at initial setup) so an existing
	// instance always picks up the fast-boot world tuning without a full
	// re-setup - see WriteServerProperties.
	if err := instance.WriteServerProperties(a.layout, state.GamePort); err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to write server.properties: %v", err))
		return
	}

	a.emitStatus(PhaseStarting, "Starting server...")

	proc, err := serverproc.Launch(a.ctx, serverproc.Options{
		JavaPath: state.JavaPath,
		JarPath:  a.layout.ServerJarPath(),
		WorkDir:  a.layout.InstanceDir,
		OnLine:   a.emitLog,
		OnDone: func() {
			a.emitStatus(PhaseStarting, "Server loaded, waiting for web editor...")
		},
		OnWebReady: func() {
			// The mod's log line means the HTTP server has bound its socket,
			// but the very first real request from the embedded webview can
			// still occasionally race that (observed as a one-off "refused
			// to connect" in the iframe even though curl succeeds moments
			// later). Actively poll until a real HTTP round-trip succeeds
			// before telling the frontend to load the iframe, so it never
			// navigates before the server can truly answer.
			go func() {
				url := fmt.Sprintf("http://127.0.0.1:%d/", state.Port)
				if waitForHTTPReady(a.ctx, url, 10*time.Second) {
					a.emitStatus(PhaseRunning, "Web editor is live.")
				} else {
					a.emitStatus(PhaseError, "Web editor did not respond in time.")
				}
			}()
		},
	})
	if err != nil {
		a.emitStatus(PhaseError, fmt.Sprintf("Failed to start server: %v", err))
		return
	}

	a.mu.Lock()
	a.proc = proc
	a.mu.Unlock()

	go func() {
		_ = proc.Wait()
		a.mu.Lock()
		a.proc = nil
		a.mu.Unlock()
		a.emitStatus(PhaseStopped, "Server stopped.")
	}()
}

// StopServer gracefully shuts down the managed server, if running.
func (a *App) StopServer() {
	a.mu.Lock()
	proc := a.proc
	a.mu.Unlock()

	if proc == nil {
		return
	}
	a.emitStatus(a.currentPhase(), "Stopping server...")
	_ = proc.Stop(30 * time.Second)
}

func (a *App) currentPhase() Phase {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.phase
}

// gameVersion resolves the running/configured Minecraft version, preferring
// the persisted launcher state (set during first-run setup) and falling back
// to the embedded mod manifest so this works even before setup completes.
func (a *App) gameVersion() string {
	a.mu.Lock()
	state := a.state
	a.mu.Unlock()

	if state != nil && state.GameVersion != "" {
		return state.GameVersion
	}
	if m, err := modassets.LoadManifest(); err == nil {
		return m.MinecraftVersion
	}
	return ""
}

// AssetsPayload tells the frontend where the vanilla Minecraft client assets
// are served and whether they are ready for the renderer to consume.
type AssetsPayload struct {
	Version string `json:"version"`
	BaseURL string `json:"baseURL"` // e.g. /mcassets/1.21.10/
	Ready   bool   `json:"ready"`
	Error   string `json:"error"`
}

// EnsureAssets provisions the vanilla client assets (block models/blockstates/
// textures) for the current Minecraft version, downloading and extracting the
// client.jar from Mojang on first use. Idempotent and safe to call on every
// launch. Progress is pushed via the "assets:progress" event; the returned
// payload gives the frontend the base URL to hand to the deepslate renderer.
func (a *App) EnsureAssets() AssetsPayload {
	version := a.gameVersion()
	if version == "" {
		return AssetsPayload{Error: "unknown Minecraft version"}
	}

	cacheRoot := a.layout.McAssetsDir()
	baseURL := fmt.Sprintf("/mcassets/%s/", version)

	err := mcassets.EnsureAssets(a.ctx, cacheRoot, version, func(msg string) {
		if a.ctx != nil {
			wailsruntime.EventsEmit(a.ctx, "assets:progress", msg)
		}
	})
	if err != nil {
		return AssetsPayload{Version: version, BaseURL: baseURL, Ready: false, Error: err.Error()}
	}
	return AssetsPayload{Version: version, BaseURL: baseURL, Ready: true}
}

// OpenInstanceFolder opens the managed server's working directory in
// Explorer, for users who want to inspect it directly.
func (a *App) OpenInstanceFolder() {
	_ = exec.Command("explorer", a.layout.InstanceDir).Start()
}

// ImportDatapackZip lets the user pick a datapack .zip (e.g. downloaded from
// Modrinth) and installs it so its trees can be imported. Returns the
// installed pack's folder name, or "" if the user cancelled the picker.
// Takes effect on the next server restart, not immediately - see
// instance.InstallDatapackZip.
func (a *App) ImportDatapackZip() (string, error) {
	path, err := wailsruntime.OpenFileDialog(a.ctx, wailsruntime.OpenDialogOptions{
		Title:   "Select a datapack .zip",
		Filters: []wailsruntime.FileFilter{{DisplayName: "Datapack (*.zip)", Pattern: "*.zip"}},
	})
	if err != nil || path == "" {
		return "", err
	}
	return a.layout.InstallDatapackZip(path)
}

// ImportDatapackFolder is the same as ImportDatapackZip but for an
// already-unzipped datapack folder.
func (a *App) ImportDatapackFolder() (string, error) {
	path, err := wailsruntime.OpenDirectoryDialog(a.ctx, wailsruntime.OpenDialogOptions{
		Title: "Select a datapack folder",
	})
	if err != nil || path == "" {
		return "", err
	}
	return a.layout.InstallDatapackFolder(path)
}

// shutdown is called by Wails when the app is closing; make a best-effort
// attempt to stop the server cleanly rather than orphaning the java process.
func (a *App) shutdown(_ context.Context) {
	a.mu.Lock()
	proc := a.proc
	a.mu.Unlock()
	if proc != nil {
		_ = proc.Stop(10 * time.Second)
	}
}
