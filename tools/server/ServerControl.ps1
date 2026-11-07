param([Parameter(Mandatory=$true)][string]$Command)
$ErrorActionPreference = 'Stop'
$properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'server.properties')
$passwordLine = $properties | Where-Object { $_ -match '^rcon.password=' } | Select-Object -First 1
$portLine = $properties | Where-Object { $_ -match '^rcon.port=' } | Select-Object -First 1
if (-not $passwordLine) { throw 'Local console password is missing from server.properties.' }
$password = $passwordLine.Substring('rcon.password='.Length)
$rconPort = if ($portLine) { [int]$portLine.Substring('rcon.port='.Length) } else { 25575 }
$client = New-Object System.Net.Sockets.TcpClient
$client.ReceiveTimeout = 5000
$client.SendTimeout = 5000
function Read-Exact([System.IO.Stream]$Stream, [int]$Count) {
    $buffer = New-Object byte[] $Count
    $offset = 0
    while ($offset -lt $Count) {
        $received = $Stream.Read($buffer,$offset,$Count-$offset)
        if ($received -eq 0) { throw 'Server closed the console connection.' }
        $offset += $received
    }
    return ,$buffer
}
function Send-Packet([System.IO.Stream]$Stream,[int]$RequestId,[int]$Type,[string]$Text) {
    $payload = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $packet = New-Object byte[] ($payload.Length+14)
    [BitConverter]::GetBytes([int]($payload.Length+10)).CopyTo($packet,0)
    [BitConverter]::GetBytes($RequestId).CopyTo($packet,4)
    [BitConverter]::GetBytes($Type).CopyTo($packet,8)
    $payload.CopyTo($packet,12)
    $Stream.Write($packet,0,$packet.Length)
}
function Read-Packet([System.IO.Stream]$Stream) {
    $length = [BitConverter]::ToInt32((Read-Exact $Stream 4),0)
    if ($length -lt 10 -or $length -gt 1048576) { throw 'Invalid console packet length.' }
    $packet = Read-Exact $Stream $length
    return [PSCustomObject]@{ Id=[BitConverter]::ToInt32($packet,0); Type=[BitConverter]::ToInt32($packet,4);
        Text=[System.Text.Encoding]::UTF8.GetString($packet,8,$length-10) }
}
try {
    $client.Connect('127.0.0.1',$rconPort)
    $stream = $client.GetStream()
    Send-Packet $stream 1 3 $password
    $authenticated = $false
    for ($attempt=0; $attempt -lt 2; $attempt++) {
        $response = Read-Packet $stream
        if ($response.Id -eq -1) { throw 'Local console authentication failed.' }
        if ($response.Type -eq 2 -and $response.Id -eq 1) { $authenticated = $true; break }
    }
    if (-not $authenticated) { throw 'Local console authentication failed.' }
    Send-Packet $stream 2 2 $Command
    $response = Read-Packet $stream
    Write-Output ($response.Text -replace '\u00a7[0-9a-fk-or]','')
} finally { $client.Dispose() }
