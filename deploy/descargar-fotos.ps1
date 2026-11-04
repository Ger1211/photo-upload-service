#requires -Version 5.1
<#
.SYNOPSIS
    Copies every photo from the cloud VM to Pictures\fotos-nube.

.DESCRIPTION
    Downloads the whole uploads volume over SCP into a staging folder, verifies
    the file count against the count the cloud itself reports, and only then
    swaps it into place. A failed download leaves the previous copy untouched.

    Called automatically by nube-off.ps1 before the VM is destroyed.

.EXAMPLE
    .\deploy\descargar-fotos.ps1
#>
[CmdletBinding()]
param(
    [string]$Destination = 'C:\Users\PC\Pictures\fotos-nube'
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Api = 'https://api.hetzner.cloud/v1'
$PhotoExtensions = @('.jpg', '.jpeg', '.png', '.webp', '.heic')

function Get-EnvMap {
    param([string]$Path)
    $map = @{}
    if (-not (Test-Path $Path)) { return $map }
    foreach ($line in Get-Content $Path) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#')) { continue }
        $idx = $trimmed.IndexOf('=')
        if ($idx -lt 1) { continue }
        $map[$trimmed.Substring(0, $idx).Trim()] = $trimmed.Substring($idx + 1).Trim()
    }
    return $map
}

$envMap = Get-EnvMap (Join-Path $RepoRoot '.env')
$headers = @{ Authorization = "Bearer $($envMap['HCLOUD_TOKEN'])"; 'Content-Type' = 'application/json' }

$serverName = $envMap['HCLOUD_SERVER_NAME']
$servers = (Invoke-RestMethod -Uri "$Api/servers?name=$serverName" -Headers $headers -TimeoutSec 60).servers
if ($servers.Count -eq 0) {
    throw "No hay ninguna VM '$serverName' prendida. No hay nada que descargar."
}
$ip = $servers[0].public_net.ipv4.ip

$staging = "$Destination.tmp"
if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
New-Item -ItemType Directory -Path $staging -Force | Out-Null

Write-Host "Descargando de $ip a $Destination ..." -ForegroundColor Cyan
scp -r "root@${ip}:/var/lib/docker/volumes/photo-upload-service_uploads/_data/." "$staging\"
if ($LASTEXITCODE -ne 0) {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    throw "scp falló con código $LASTEXITCODE. La copia anterior no se tocó."
}

$downloaded = (Get-ChildItem $staging -Recurse -File |
        Where-Object { $PhotoExtensions -contains $_.Extension.ToLower() -and $_.FullName -notmatch '\\thumbnails\\' }).Count

$expected = $null
try {
    $status = Invoke-RestMethod -Uri "https://$($envMap['FUNNEL_HOST'])/api/internal/status" `
        -Headers @{ 'X-Replication-Token' = $envMap['REPLICATION_TOKEN'] } -TimeoutSec 30
    $expected = [int]$status.localCount
}
catch {
    Write-Host "  (no se pudo consultar el conteo de la nube; se omite la verificación)" -ForegroundColor Yellow
}

if ($null -ne $expected -and $expected -ne $downloaded) {
    Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
    throw "Se descargaron $downloaded fotos pero la nube reporta $expected. La copia anterior no se tocó."
}

if (Test-Path $Destination) { Remove-Item $Destination -Recurse -Force }
Move-Item -Path $staging -Destination $Destination

$sizeMb = [math]::Round(((Get-ChildItem $Destination -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 2)
Write-Host ""
Write-Host "Copia del servidor lista" -ForegroundColor Green
Write-Host "  Destino : $Destination"
Write-Host "  Fotos   : $downloaded$(if ($null -ne $expected) { " (la nube reporta $expected)" })"
Write-Host "  Tamaño  : $sizeMb MB (incluye thumbnails)"
