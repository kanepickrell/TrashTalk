# Builds the complete offline basemap package into app/src/main/assets/map/.
#
# Produces, alongside the terrell_heights.pmtiles archive that
# generate_street_network.py's sibling step extracts:
#
#   style.json           the generated style (tools/build_basemap_style.mjs)
#   fonts/<stack>/*.pbf  glyph ranges for the three stacks the style names
#   sprites/basemap.*    road shields, one-way arrows, town spots
#
# Everything here is pinned and reproducible. Run it when the Protomaps
# version moves or the flavour changes; the outputs are committed, so a normal
# build never needs it.
#
#   pwsh tools/build_basemap.ps1

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"  # Write-Progress costs more than the downloads

$tools = $PSScriptRoot
$assets = Join-Path $tools "../app/src/main/assets/map" | Resolve-Path
# The public site renders the same basemap with MapLibre GL JS, from the same
# generated style. One flavour, two targets -- see build_basemap_style.mjs.
$web = Join-Path $tools "../web/public/map"
New-Item -ItemType Directory -Force -Path $web | Out-Null

# protomaps/basemaps-assets is versioned by git ref, not by release. Pin the
# commit so a font file cannot change underneath a build that is otherwise
# byte-identical.
$assetsRef = "main"
$base = "https://raw.githubusercontent.com/protomaps/basemaps-assets/$assetsRef"

# ---------------------------------------------------------------- the style
Write-Host "building style..." -ForegroundColor Cyan
Push-Location $tools
try {
    if (-not (Test-Path (Join-Path $tools "node_modules"))) { npm install --silent }
    node build_basemap_style.mjs
    if ($LASTEXITCODE -ne 0) { throw "build_basemap_style.mjs failed" }
}
finally { Pop-Location }

# ---------------------------------------------------------------- the fonts
# Only the Latin ranges. The full set is 256 ranges per stack covering every
# script Noto has; Terrell Heights street names need Basic Latin, Latin-1 for
# the odd accent, and Latin Extended-A. Bundling the rest would add ~20 MB to
# an APK that is already carrying a 14 MB renderer.
#
# The three stacks are exactly the ones the generated style asks for -- see the
# assertion at the end, which fails the build if that stops being true.
$stacks = @("Noto Sans Regular", "Noto Sans Medium", "Noto Sans Italic")
$ranges = @("0-255", "256-511")

Write-Host "fetching fonts..." -ForegroundColor Cyan
foreach ($stack in $stacks) {
    $dir = Join-Path $assets "fonts/$stack"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    foreach ($range in $ranges) {
        $url = "$base/fonts/$([uri]::EscapeDataString($stack))/$range.pbf"
        $dest = Join-Path $dir "$range.pbf"
        Invoke-WebRequest -Uri $url -OutFile $dest
        Write-Host ("  {0}/{1}.pbf  {2:N0} bytes" -f $stack, $range, (Get-Item $dest).Length)
    }
}

# -------------------------------------------------------------- the sprites
# The `light` sprite, not `grayscale`: it carries the full road-shield set
# (grayscale ships a reduced one), and its icons are drawn for a pale ground,
# which is what our flavour is.
Write-Host "fetching sprites..." -ForegroundColor Cyan
$spriteDir = Join-Path $assets "sprites"
New-Item -ItemType Directory -Force -Path $spriteDir | Out-Null
foreach ($variant in @(@{ src = "light"; dst = "basemap" }, @{ src = "light@2x"; dst = "basemap@2x" })) {
    foreach ($ext in @("json", "png")) {
        $url = "$base/sprites/v4/$($variant.src).$ext"
        $dest = Join-Path $spriteDir "$($variant.dst).$ext"
        Invoke-WebRequest -Uri $url -OutFile $dest
        Write-Host ("  {0}.{1}  {2:N0} bytes" -f $variant.dst, $ext, (Get-Item $dest).Length)
    }
}

# ------------------------------------------------------------- verification
# A style naming a font stack or an icon we did not fetch fails at runtime as a
# missing label, silently. Check it here instead.
$style = Get-Content (Join-Path $assets "style.json") -Raw | ConvertFrom-Json
$named = [System.Collections.Generic.HashSet[string]]::new()
foreach ($layer in $style.layers) {
    $font = $layer.layout.'text-font'
    if ($null -ne $font) {
        # Either a literal stack, or a `case` expression wrapping literal ones.
        foreach ($token in ($font | ConvertTo-Json -Depth 20 -Compress | Select-String -Pattern '"Noto Sans [^"]+"' -AllMatches).Matches) {
            [void]$named.Add($token.Value.Trim('"'))
        }
    }
}
$unfetched = @($named | Where-Object { $stacks -notcontains $_ })
if ($unfetched.Count -gt 0) {
    throw "style.json names font stacks that were not fetched: $($unfetched -join ', ')"
}

# ------------------------------------------------------- the web copies
# Same bytes, served over HTTP instead of read out of the APK. Copied rather
# than symlinked so a Vercel build needs nothing but the repository.
Write-Host "staging web copies..." -ForegroundColor Cyan
foreach ($item in @("fonts", "sprites")) {
    $dest = Join-Path $web $item
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    Copy-Item (Join-Path $assets $item) $dest -Recurse
}
Copy-Item (Join-Path $assets "terrell_heights.pmtiles") (Join-Path $web "terrell_heights.pmtiles") -Force
Write-Host ("  {0:N1} MB staged to web/public/map" -f (
    (Get-ChildItem $web -Recurse -File | Measure-Object -Property Length -Sum).Sum / 1MB))

$total = (Get-ChildItem $assets -Recurse -File | Measure-Object -Property Length -Sum).Sum
Write-Host ""
Write-Host ("basemap package complete: {0:N1} MB in assets/map/" -f ($total / 1MB)) -ForegroundColor Green
Write-Host ("  font stacks verified: {0}" -f ($named -join ', '))
