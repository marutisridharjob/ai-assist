# Builds a Windows installer (.msi by default; pass "exe" as the first arg for
# an .exe) with jpackage. Bundles its own Java runtime, installs per-user (no
# admin), and creates Start-menu and Desktop shortcuts.
# Requires: JDK 21+ on PATH and the WiX Toolset v3 (jpackage uses it for msi/exe).
#   choco install wixtoolset -y
[CmdletBinding()]
param([string]$Type = "msi")

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$AppName = "ai-assist"
$Vendor  = "ai-assist"
$Desc    = "Offline meeting-notes assistant"

Write-Host "==> Building the hardened application jar"
mvn -B -Pharden -DskipTests clean package
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }

$Jar = Get-ChildItem "target\$AppName-*.jar" | Where-Object { $_.Name -notlike "*original*" } | Select-Object -First 1
$Version = ($Jar.Name -replace "^$AppName-", "" -replace "\.jar$", "" -replace "-SNAPSHOT", "")
Write-Host "==> Packaging $AppName $Version as $Type"

$Input = New-Item -ItemType Directory -Force -Path (Join-Path $env:TEMP "aiassist-input")
Remove-Item "$Input\*" -Force -ErrorAction SilentlyContinue
Copy-Item $Jar.FullName $Input

# Drop the other platforms' native libraries to shrink the installer (uses Git
# Bash, present on GitHub windows runners; skipped if bash/zip are unavailable).
$SlimJar = Join-Path $Input $Jar.Name
if (Get-Command bash -ErrorAction SilentlyContinue) {
  bash packaging/slim-jar.sh "$SlimJar" windows
  if ($LASTEXITCODE -ne 0) { Write-Host "(jar slimming skipped)" }
} else {
  Write-Host "(jar slimming skipped: bash not found)"
}
$Out = "dist"; New-Item -ItemType Directory -Force -Path $Out | Out-Null

# Build a .ico from the PNG with ImageMagick if available; otherwise no icon.
$IconArgs = @()
$Png = "packaging\icons\ai-assist.png"
if ((Test-Path $Png) -and (Get-Command magick -ErrorAction SilentlyContinue)) {
  $Ico = Join-Path $env:TEMP "ai-assist.ico"
  magick $Png -define icon:auto-resize=256,128,64,48,32,16 $Ico
  if (Test-Path $Ico) { $IconArgs = @("--icon", $Ico) }
}

jpackage `
  --type $Type `
  --name $AppName `
  --app-version $Version `
  --vendor $Vendor `
  --description $Desc `
  --input $Input `
  --main-jar $Jar.Name `
  --main-class org.springframework.boot.loader.launch.JarLauncher `
  --java-options "-Djava.awt.headless=false" `
  @IconArgs `
  --win-shortcut `
  --win-menu `
  --win-menu-group $AppName `
  --win-dir-chooser `
  --win-per-user-install `
  --dest $Out
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

Write-Host "==> Done. Installer in .\$Out:"
Get-ChildItem $Out | Select-Object Name
