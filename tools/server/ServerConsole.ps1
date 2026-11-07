$ErrorActionPreference = 'Stop'
Write-Output 'HoloMapPerspective console. Type a server command without /; exit closes this console.'
while ($true) {
    $serverCommand = Read-Host 'server'
    if ($serverCommand -eq 'exit') { break }
    if ([string]::IsNullOrWhiteSpace($serverCommand)) { continue }
    try { & (Join-Path $PSScriptRoot 'ServerControl.ps1') -Command $serverCommand }
    catch { Write-Host $_.Exception.Message -ForegroundColor Red }
    if ($serverCommand -eq 'stop') { break }
}
