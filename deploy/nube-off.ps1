#requires -Version 5.1
<#
.SYNOPSIS
    Destroys the cloud VM, but only after proving the home PC has every photo.

.DESCRIPTION
    The cloud VM is disposable: the archive lives on the PC. Before deleting it,
    this script asks both machines for their replication status and refuses to
    continue unless the counters show they are identical.

.PARAMETER Force
    Skips the safety check. Use only when you are sure the photos are safe.

.PARAMETER SkipBackup
    Skips copying the server copy to Pictures\fotos-nube before deleting the VM.

.EXAMPLE
    .\deploy\nube-off.ps1
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [switch]$SkipBackup
)

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$Api = 'https://api.hetzner.cloud/v1'

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

function Invoke-Hetzner {
    param([string]$Method, [string]$Path, $Body)
    $uri = "$Api$Path"
    try {
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Depth 10 -Compress
            return Invoke-RestMethod -Method $Method -Uri $uri -Headers $script:Headers -Body $json -TimeoutSec 60
        }
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $script:Headers -TimeoutSec 60
    }
    catch {
        $detail = $_.Exception.Message
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $detail = $_.ErrorDetails.Message }
        throw "Hetzner API $Method $Path failed: $detail"
    }
}

function Get-ReplicationStatus {
    param([string]$BaseUrl, [string]$Token)
    try {
        return Invoke-RestMethod -Uri "$BaseUrl/api/internal/status" `
            -Headers @{ 'X-Replication-Token' = $Token } -TimeoutSec 20
    }
    catch {
        return $null
    }
}

$envMap = Get-EnvMap (Join-Path $RepoRoot '.env')
$script:Headers = @{ Authorization = "Bearer $($envMap['HCLOUD_TOKEN'])"; 'Content-Type' = 'application/json' }

$serverName = $envMap['HCLOUD_SERVER_NAME']
$token = $envMap['REPLICATION_TOKEN']
$cloudUrl = "https://$($envMap['FUNNEL_HOST'])"

Write-Host "== Photo Upload Service - apagando la nube ==" -ForegroundColor Cyan

$servers = (Invoke-Hetzner -Method GET -Path "/servers?name=$serverName").servers
if ($servers.Count -eq 0) {
    Write-Host "No hay ninguna VM '$serverName'. Nada para borrar (costo: 0)." -ForegroundColor Green
    exit 0
}

# --- Safety gate -------------------------------------------------------------
$problems = @()

$cloud = Get-ReplicationStatus -BaseUrl $cloudUrl -Token $token
$pc = Get-ReplicationStatus -BaseUrl $envMap['PC_STATUS_URL'] -Token $token

if ($null -eq $cloud) {
    $problems += "La nube no responde en $cloudUrl/api/internal/status"
}
if ($null -eq $pc) {
    $problems += "La PC no responde en $($envMap['PC_STATUS_URL'])/api/internal/status (¿está corriendo el Docker con replicación activa?)"
}

if ($null -ne $cloud) {
    if (-not $cloud.peerReachable) { $problems += "La nube no ve a la PC (peerReachable=false)" }
    if ($cloud.outboxDepth -ne 0) { $problems += "La nube tiene $($cloud.outboxDepth) fotos en cola sin enviar" }
    if ($cloud.missingLocally -ne 0) { $problems += "A la nube le faltan $($cloud.missingLocally) fotos de la PC" }
    if ($cloud.missingOnPeer -ne 0) { $problems += "A la PC le faltan $($cloud.missingOnPeer) fotos de la nube" }
    if ($cloud.checksumMismatches -ne 0) { $problems += "Hay $($cloud.checksumMismatches) archivos con checksum distinto" }
}
if ($null -ne $pc -and $null -ne $cloud -and $pc.localCount -ne $cloud.localCount) {
    $problems += "Conteos distintos: PC=$($pc.localCount) vs nube=$($cloud.localCount)"
}

Write-Host ""
Write-Host ("  Nube : " + $(if ($null -ne $cloud) { "$($cloud.localCount) fotos, outbox=$($cloud.outboxDepth), faltan=$($cloud.missingOnPeer)" } else { "sin respuesta" }))
Write-Host ("  PC   : " + $(if ($null -ne $pc) { "$($pc.localCount) fotos, outbox=$($pc.outboxDepth), faltan=$($pc.missingOnPeer)" } else { "sin respuesta" }))
Write-Host ""

if ($problems.Count -gt 0) {
    Write-Host "NO se borra la VM. Problemas detectados:" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Esperá un ciclo de reconciliación (1 min) y volvé a intentar." -ForegroundColor Yellow
    Write-Host "Si estás seguro de que las fotos están en la PC: .\deploy\nube-off.ps1 -Force" -ForegroundColor Yellow
    exit 1
}

Write-Host "Las dos copias son idénticas ($($cloud.localCount) fotos)." -ForegroundColor Green

if ($Force) {
    Write-Host "Modo -Force: se saltea la verificación de arriba." -ForegroundColor Yellow
}

# --- Server copy (belt and braces before the VM disappears) ------------------
if ($SkipBackup) {
    Write-Host "Se saltea la copia del servidor (-SkipBackup)." -ForegroundColor Yellow
}
else {
    Write-Host ""
    Write-Host "Bajando la copia del servidor antes de borrar la VM..." -ForegroundColor Cyan
    try {
        & (Join-Path $PSScriptRoot 'descargar-fotos.ps1')
    }
    catch {
        Write-Host "No se pudo bajar la copia del servidor: $($_.Exception.Message)" -ForegroundColor Red
        if (-not $Force) {
            Write-Host "NO se borra la VM. La nube sigue prendida." -ForegroundColor Red
            exit 1
        }
        Write-Host "Continuando de todos modos por -Force." -ForegroundColor Yellow
    }
}

# --- Delete ------------------------------------------------------------------
foreach ($server in $servers) {
    Write-Host "Borrando VM $($server.name) (id $($server.id))..." -ForegroundColor Cyan
    Invoke-Hetzner -Method DELETE -Path "/servers/$($server.id)" | Out-Null
}

Write-Host "VM borrada. El firewall '$serverName-fw' queda (es gratis y se reutiliza)." -ForegroundColor Green
Write-Host "Costo a partir de ahora: 0. Las fotos siguen en la PC." -ForegroundColor Green
