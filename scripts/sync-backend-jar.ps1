# Builds the Tree Engine backend mod and copies the resulting jar plus a small
# metadata manifest into app/internal/assets/, where the Go app embeds them via
# //go:embed. Run this whenever the backend changes, before building the app.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$backendDir = Join-Path $root "backend"
$assetsDir = Join-Path $root "app\internal\assets"

Write-Host "Building backend..."
Push-Location $backendDir
try {
    & .\gradlew.bat build --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "gradlew build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$properties = @{}
Get-Content (Join-Path $backendDir "gradle.properties") | ForEach-Object {
    if ($_ -match '^\s*([^#=\s][^=]*)\s*=\s*(.*)$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$modVersion = $properties["mod_version"]
$minecraftVersion = $properties["minecraft_version"]
$fabricApiVersion = $properties["fabric_api_version"]
$loaderVersion = $properties["loader_version"]

# The jar name comes from settings.gradle's rootProject.name, which the build
# does not echo anywhere else - keep this in step with it.
$jarName = "tree-engine-backend-$modVersion.jar"
$jarPath = Join-Path $backendDir "build\libs\$jarName"

if (-not (Test-Path $jarPath)) {
    throw "Expected built jar not found at $jarPath"
}

New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null

# Only rewrite the embedded jar when its contents actually differ. This runs as
# a Wails pre-build hook on every build and every `wails dev` hot restart, and
# the jar is byte-reproducible, so an unchanged backend should leave the file -
# and the working tree - completely alone.
$targetJar = Join-Path $assetsDir "tree-engine.jar"
$jarChanged = $true
if (Test-Path $targetJar) {
    $jarChanged = (Get-FileHash $jarPath).Hash -ne (Get-FileHash $targetJar).Hash
}
if ($jarChanged) {
    Copy-Item $jarPath $targetJar -Force
}

$manifest = @{
    minecraftVersion = $minecraftVersion
    fabricApiVersion = $fabricApiVersion
    loaderVersion    = $loaderVersion
    modVersion       = $modVersion
} | ConvertTo-Json

$manifestPath = Join-Path $assetsDir "mod-manifest.json"
[System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))

if ($jarChanged) {
    Write-Host "Synced $jarName (mc=$minecraftVersion, fabric-api=$fabricApiVersion) into app/internal/assets/"
} else {
    Write-Host "Embedded backend jar already up to date ($jarName)"
}
