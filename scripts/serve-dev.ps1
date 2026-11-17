# Serves the built plugin over the LAN and prints a QR code the Even App can scan.
# Keeps running until you press Ctrl+C; the glasses talk to the phone, not to this PC,
# so the phone only needs to be able to reach this machine's LAN address once.
param(
    [int]$Port = 8099,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $root

if (-not $SkipBuild) {
    & node scripts\build.mjs
    if ($LASTEXITCODE -ne 0) { throw 'plugin build failed' }
}

# Pick the LAN address the phone can reach (skip loopback / APIPA / virtual adapters).
$address = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
        $_.IPAddress -notlike '127.*' -and
        $_.IPAddress -notlike '169.254.*' -and
        $_.PrefixOrigin -ne 'WellKnown'
    } |
    Sort-Object -Property InterfaceMetric |
    Select-Object -First 1 -ExpandProperty IPAddress

if (-not $address) {
    throw 'No LAN IPv4 address found; pass -Port only after connecting this PC to the same Wi-Fi as the phone.'
}

Write-Output "Plugin will be served from http://${address}:$Port (keep this window open)."
& "$root\node_modules\.bin\evenhub.cmd" qr -i $address -p $Port --http -e
if ($LASTEXITCODE -ne 0) { throw 'evenhub qr failed' }

# python ships on this machine and needs no extra install; .js gets the right MIME type.
& python -m http.server $Port --bind 0.0.0.0 --directory "$root\dist"
