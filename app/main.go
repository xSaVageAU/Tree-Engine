package main

import (
	"embed"
	"net/http"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	// Create an instance of the app structure
	app := NewApp()

	// Serve the extracted vanilla Minecraft client assets (block models,
	// blockstates, textures) at /mcassets/<version>/... so the deepslate
	// renderer in the webview can fetch them. This is a fallback handler:
	// Wails invokes it only for requests the embedded frontend does not serve.
	mcAssetsHandler := http.StripPrefix("/mcassets/",
		http.FileServer(http.Dir(app.layout.McAssetsDir())))

	// Create application with options
	err := wails.Run(&options.App{
		Title:  "Tree Engine",
		Width:  1200,
		Height: 800,
		AssetServer: &assetserver.Options{
			Assets:  assets,
			Handler: mcAssetsHandler,
		},
		BackgroundColour: &options.RGBA{R: 27, G: 38, B: 54, A: 1},
		OnStartup:        app.startup,
		OnShutdown:       app.shutdown,
		Bind: []any{
			app,
		},
	})

	if err != nil {
		println("Error:", err.Error())
	}
}
