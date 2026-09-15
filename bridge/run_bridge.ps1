# Run Widgen Bridge in background or foreground
param(
    [switch]$Background
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bridgeScript = Join-Path $scriptDir "widgen_bridge.py"

if ($Background) {
    Start-Process python -ArgumentList "`"$bridgeScript`"" -WindowStyle Hidden
    Write-Host "Widgen Bridge started in background on port 59123." -ForegroundColor Green
} else {
    python "$bridgeScript"
}
