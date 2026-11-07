param([switch]$CheckOnly, [switch]$Foreground)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$javaExe = 'C:\Program Files\Java\jdk-25\bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    if ($env:JAVA_HOME) { $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    else { $javaExe = (Get-Command java.exe -ErrorAction Stop).Source }
}
$javaVersion = (& $javaExe --version | Out-String)
if ($javaVersion -notmatch '(?m)^(?:openjdk|java)\s+(\d+)' -or [int]$Matches[1] -lt 25) {
    throw 'Minecraft 26.3 requires Java 25 or newer.'
}
$serverJar = 'paper-26.3-243.jar'
if (-not (Test-Path -LiteralPath $serverJar)) { throw "Missing $serverJar" }
$pluginJars = @(Get-ChildItem -LiteralPath 'plugins' -Filter 'HoloMapPerspective-*.jar' -File)
if ($pluginJars.Count -ne 1) { throw 'Place exactly one HoloMapPerspective plugin JAR in plugins.' }
$pluginVersion = $pluginJars[0].BaseName -replace '^HoloMapPerspective-',''
if ((Get-Content -LiteralPath 'eula.txt' -Raw) -notmatch '(?m)^eula=true\s*$') { throw 'eula.txt must contain the server owner''s EULA acceptance.' }
if ($CheckOnly) { Write-Output "LAUNCHER_CHECK_OK: $javaExe / Paper 26.3 build 243 / HoloMapPerspective $pluginVersion"; exit 0 }

$listener = Get-NetTCPConnection -LocalPort 25565 -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    $existing = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener[0].OwningProcess)"
    $pidPath = Join-Path $PSScriptRoot '.server.pid'
    if ((Test-Path -LiteralPath $pidPath) -and ([int](Get-Content -LiteralPath $pidPath)) -eq $existing.ProcessId -and $existing.CommandLine -like "*$serverJar*") {
        Write-Output "This server is already running (PID $($existing.ProcessId)). Join localhost:25565."
        exit 0
    }
    throw "Port 25565 is already in use by PID $($listener[0].OwningProcess). Stop that server first."
}
$javaArgs = @('-Xms512M','-Xmx2G','-Dterminal.jline=false','-Dterminal.ansi=false',
    '-Dio.netty.eventLoopThreads=2','--enable-native-access=ALL-UNNAMED','-jar',$serverJar,'--nogui')
if ($Foreground) { & $javaExe @javaArgs; exit $LASTEXITCODE }
New-Item -ItemType Directory -Force -Path 'logs' | Out-Null
$serverProcess = Start-Process -FilePath $javaExe -ArgumentList $javaArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput (Join-Path $PSScriptRoot 'logs\console.out.log') -RedirectStandardError (Join-Path $PSScriptRoot 'logs\console.err.log')
$serverProcess.Id | Set-Content -LiteralPath '.server.pid' -Encoding ascii
for ($attempt=0; $attempt -lt 90; $attempt++) {
    Start-Sleep -Milliseconds 500
    $serverProcess.Refresh()
    if ($serverProcess.HasExited) { throw 'Server exited. Check logs\console.out.log and logs\console.err.log.' }
    $startupLog = Get-Content -LiteralPath 'logs\console.out.log' -Raw -ErrorAction SilentlyContinue
    if ($startupLog -match 'Done \(') {
        Write-Output "Server started (PID $($serverProcess.Id)). Minecraft Java 26.3: localhost:25565"
        Write-Output 'Use ServerConsole.bat for commands, or StopServer.bat to save and stop.'
        exit 0
    }
}
throw 'Server is still starting. Check logs\latest.log before starting again.'
