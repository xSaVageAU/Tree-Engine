package instance

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"

	"app/internal/assets"
	"app/internal/fabricmeta"
	"app/internal/javamgmt"
	"app/internal/modrinth"
)

// ProgressFunc reports human-readable setup progress to the UI.
type ProgressFunc func(stage, message string)

// Run performs the full first-run setup sequence: locate/download Java,
// download the Fabric server + Fabric API, inject the bundled mod, write the
// EULA acceptance and mod config, and persist the resulting state.
//
// eulaAccepted must be true - this is enforced here (not just trusted to the
// frontend) so eula.txt is never written without explicit user agreement.
func Run(ctx context.Context, l Layout, eulaAccepted bool, progress ProgressFunc) (*State, error) {
	if !eulaAccepted {
		return nil, errors.New("cannot set up server: EULA was not accepted")
	}
	if progress == nil {
		progress = func(string, string) {}
	}

	manifest, err := assets.LoadManifest()
	if err != nil {
		return nil, err
	}

	if err := l.EnsureDirs(); err != nil {
		return nil, fmt.Errorf("failed to create instance directories: %w", err)
	}

	progress("java", "Checking for a compatible Java installation...")
	javaPath, err := resolveJava(ctx, l, progress)
	if err != nil {
		return nil, err
	}

	progress("fabric", "Resolving Fabric loader/installer versions...")
	loaderVersion, err := fabricmeta.LatestStableLoader(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve fabric loader version: %w", err)
	}
	installerVersion, err := fabricmeta.LatestStableInstaller(ctx)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve fabric installer version: %w", err)
	}

	progress("fabric", fmt.Sprintf("Downloading Fabric server (Minecraft %s, loader %s)...", manifest.MinecraftVersion, loaderVersion))
	if err := fabricmeta.DownloadServerJar(ctx, manifest.MinecraftVersion, loaderVersion, installerVersion, l.ServerJarPath()); err != nil {
		return nil, fmt.Errorf("failed to download fabric server: %w", err)
	}

	progress("fabric-api", "Downloading Fabric API...")
	apiURL, resolvedApiVersion, err := modrinth.FindFabricApiJar(ctx, manifest.MinecraftVersion, manifest.FabricApiVersion)
	if err != nil {
		return nil, fmt.Errorf("failed to resolve fabric api: %w", err)
	}
	if err := modrinth.Download(ctx, apiURL, filepath.Join(l.ModsDir(), "fabric-api.jar")); err != nil {
		return nil, fmt.Errorf("failed to download fabric api: %w", err)
	}

	progress("mod", "Installing Tree Engine mod...")
	if err := os.WriteFile(filepath.Join(l.ModsDir(), "tree-engine.jar"), assets.TreeEngineJar, 0o644); err != nil {
		return nil, fmt.Errorf("failed to write tree-engine.jar: %w", err)
	}

	progress("config", "Writing configuration...")
	if err := WriteEula(l); err != nil {
		return nil, fmt.Errorf("failed to write eula.txt: %w", err)
	}

	port, err := FindFreePort(3000)
	if err != nil {
		return nil, err
	}
	authToken, err := GenerateAuthToken()
	if err != nil {
		return nil, err
	}
	if err := WriteModConfig(l, port, authToken); err != nil {
		return nil, fmt.Errorf("failed to write mod config: %w", err)
	}

	state := &State{
		SetupComplete:    true,
		JavaPath:         javaPath,
		GameVersion:      manifest.MinecraftVersion,
		LoaderVersion:    loaderVersion,
		InstallerVersion: installerVersion,
		FabricApiVersion: resolvedApiVersion,
		ModVersion:       manifest.ModVersion,
		Port:             port,
		AuthToken:        authToken,
	}
	if err := state.Save(l.StateFile); err != nil {
		return nil, fmt.Errorf("failed to save launcher state: %w", err)
	}

	progress("done", "Setup complete.")
	return state, nil
}

// resolveJava returns a system Java 21+ if one is found, otherwise downloads
// a private Temurin JRE into the runtime dir.
func resolveJava(ctx context.Context, l Layout, progress ProgressFunc) (string, error) {
	if info, err := javamgmt.FindSystemJava(); err == nil {
		progress("java", fmt.Sprintf("Using system Java %d.", info.Major))
		return info.Path, nil
	}

	progress("java", "No compatible system Java found - downloading a private Java runtime...")
	javaPath, err := javamgmt.DownloadJRE(ctx, l.RuntimeDir, func(downloaded, total int64) {
		if total > 0 {
			progress("java", fmt.Sprintf("Downloading Java runtime... %d%%", downloaded*100/total))
		}
	})
	if err != nil {
		return "", fmt.Errorf("failed to download Java runtime: %w", err)
	}
	progress("java", "Java runtime downloaded.")
	return javaPath, nil
}
