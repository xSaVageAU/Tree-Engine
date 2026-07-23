# Builds the Tree Engine mod and copies the resulting jar + a small metadata
# manifest into app/internal/assets/, where the Go app embeds them via
# //go:embed. Run this whenever the mod changes before building the launcher.
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$modDir = Join-Path $root "mod"
$assetsDir = Join-Path $root "app\internal\assets"

Write-Host "Building mod..."
Push-Location $modDir
try {
    & .\gradlew.bat build --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "gradlew build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$properties = @{}
Get-Content (Join-Path $modDir "gradle.properties") | ForEach-Object {
    if ($_ -match '^\s*([^#=\s][^=]*)\s*=\s*(.*)$') {
        $properties[$matches[1].Trim()] = $matches[2].Trim()
    }
}

$modVersion = $properties["mod_version"]
$minecraftVersion = $properties["minecraft_version"]
$fabricApiVersion = $properties["fabric_version"]
$archivesBaseName = $properties["archives_base_name"]

$jarName = "$archivesBaseName-$modVersion.jar"
$jarPath = Join-Path $modDir "build\libs\$jarName"

if (-not (Test-Path $jarPath)) {
    throw "Expected built jar not found at $jarPath"
}

New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null
Copy-Item $jarPath (Join-Path $assetsDir "tree-engine.jar") -Force

$manifest = @{
    minecraftVersion = $minecraftVersion
    fabricApiVersion = $fabricApiVersion
    modVersion       = $modVersion
} | ConvertTo-Json

$manifestPath = Join-Path $assetsDir "mod-manifest.json"
[System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))

Write-Host "Synced $jarName (mc=$minecraftVersion, fabric-api=$fabricApiVersion) into app/internal/assets/"
