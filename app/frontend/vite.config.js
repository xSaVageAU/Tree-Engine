import { svelte } from '@sveltejs/vite-plugin-svelte'
import { defineConfig } from 'vite'
import { viteStaticCopy } from 'vite-plugin-static-copy'

export default defineConfig({
  plugins: [
    svelte(),
    // Serves Monaco's prebuilt AMD bundle (loader + workers) as static files
    // at /monaco-vs/, so the JSON editor loads locally instead of from a CDN -
    // this is a desktop app that should work without internet access once set up.
    viteStaticCopy({
      targets: [{ src: 'node_modules/monaco-editor/min/vs/*', dest: 'monaco-vs' }],
    }),
  ],
  // In `wails dev`, Wails proxies every request to this Vite server first and
  // only falls back to its own (or our custom) asset handler on a genuine 404
  // (see wails/v2 pkg/assetserver/assethandler_external.go ModifyResponse).
  // Vite's default appType 'spa' installs an HTML-fallback middleware that
  // serves index.html (200 OK) for any unmatched GET instead of a real 404 -
  // which silently defeats that fallback for routes like /mcassets/* that our
  // Go backend serves, not Vite. 'mpa' disables that fallback so unmatched
  // requests correctly 404 and reach our Go handler.
  appType: 'mpa',
})
