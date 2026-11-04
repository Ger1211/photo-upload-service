#requires -Version 5.1
<#
.SYNOPSIS
    Creates the Hetzner VM that hosts the photo upload service (cloud primary).

.DESCRIPTION
    Reads .env, makes sure the firewall exists, creates the VM with cloud-init
    (Docker + Tailscale + Funnel + the service), and waits until the public
    health endpoint answers.

    Safe to re-run: it refuses to create a second VM with the same name.

.EXAMPLE
    .\deploy\nube-on.ps1
#>
[CmdletBinding()]
param()

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

$envMap = Get-EnvMap (Join-Path $RepoRoot '.env')

$required = @('HCLOUD_TOKEN', 'HCLOUD_SERVER_NAME', 'HCLOUD_SERVER_TYPE', 'HCLOUD_LOCATION',
    'HCLOUD_IMAGE', 'HCLOUD_SSH_KEY', 'TS_AUTHKEY', 'TS_HOSTNAME', 'FUNNEL_HOST',
    'REPLICATION_TOKEN', 'PC_PEER_URL', 'REPO_URL')
$missing = @()
foreach ($key in $required) {
    if (-not $envMap.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($envMap[$key])) { $missing += $key }
}
if ($missing.Count -gt 0) {
    Write-Host "Faltan valores en .env: $($missing -join ', ')" -ForegroundColor Red
    if ($missing -contains 'TS_AUTHKEY') {
        Write-Host "  TS_AUTHKEY: https://login.tailscale.com/admin/settings/keys (reusable + ephemeral + tag:nube)" -ForegroundColor Yellow
    }
    exit 1
}

$script:Headers = @{ Authorization = "Bearer $($envMap['HCLOUD_TOKEN'])"; 'Content-Type' = 'application/json' }
$serverName = $envMap['HCLOUD_SERVER_NAME']
$funnelUrl = "https://$($envMap['FUNNEL_HOST'])"

Write-Host "== Photo Upload Service - encendiendo la nube ==" -ForegroundColor Cyan

# --- 1. Existing server? -----------------------------------------------------
$existing = (Invoke-Hetzner -Method GET -Path "/servers?name=$serverName").servers
if ($existing.Count -gt 0) {
    Write-Host "Ya existe una VM '$serverName' (id $($existing[0].id), IP $($existing[0].public_net.ipv4.ip))." -ForegroundColor Yellow
    Write-Host "Si querés recrearla: .\deploy\nube-off.ps1 -Force y volvé a correr este script." -ForegroundColor Yellow
    exit 1
}

# --- 2. Firewall (SSH + ICMP only; 8080 stays off the public internet) -------
$fwName = "$serverName-fw"
$firewalls = (Invoke-Hetzner -Method GET -Path "/firewalls?name=$fwName").firewalls
if ($firewalls.Count -gt 0) {
    $fwId = $firewalls[0].id
    Write-Host "Firewall existente: $fwName (id $fwId)"
}
else {
    $rules = @(
        @{ direction = 'in'; protocol = 'tcp'; port = '22'; source_ips = @('0.0.0.0/0', '::/0'); description = 'SSH' },
        @{ direction = 'in'; protocol = 'icmp'; source_ips = @('0.0.0.0/0', '::/0'); description = 'ICMP' }
    )
    $createdFw = Invoke-Hetzner -Method POST -Path '/firewalls' -Body @{ name = $fwName; rules = $rules }
    $fwId = $createdFw.firewall.id
    Write-Host "Firewall creado: $fwName (id $fwId)"
}

# --- 3. cloud-init -----------------------------------------------------------
$cloudInit = Get-Content -Path (Join-Path $PSScriptRoot 'cloud-init.yaml') -Raw
$cloudInit = $cloudInit.Replace('__TS_AUTHKEY__', $envMap['TS_AUTHKEY'])
$cloudInit = $cloudInit.Replace('__TS_HOSTNAME__', $envMap['TS_HOSTNAME'])
$cloudInit = $cloudInit.Replace('__PEER_URL__', $envMap['PC_PEER_URL'])
$cloudInit = $cloudInit.Replace('__REPLICATION_TOKEN__', $envMap['REPLICATION_TOKEN'])
$cloudInit = $cloudInit.Replace('__REPO_URL__', $envMap['REPO_URL'])

# --- 4. Create the server ----------------------------------------------------
$body = @{
    name               = $serverName
    server_type        = $envMap['HCLOUD_SERVER_TYPE']
    image              = $envMap['HCLOUD_IMAGE']
    location           = $envMap['HCLOUD_LOCATION']
    start_after_create = $true
    ssh_keys           = @($envMap['HCLOUD_SSH_KEY'])
    firewalls          = @(@{ firewall = $fwId })
    labels             = @{ app = 'photo-upload-service'; role = 'primary' }
    user_data          = $cloudInit
}

Write-Host "Creando VM $serverName ($($envMap['HCLOUD_SERVER_TYPE']) en $($envMap['HCLOUD_LOCATION']))..." -ForegroundColor Cyan
$created = Invoke-Hetzner -Method POST -Path '/servers' -Body $body
$serverId = $created.server.id
$ip = $created.server.public_net.ipv4.ip
Write-Host "VM creada: id $serverId, IP $ip"

# --- 5. Wait for the service -------------------------------------------------
Write-Host "Esperando el build + arranque (3-6 min). URL: $funnelUrl" -ForegroundColor Cyan
$deadline = (Get-Date).AddMinutes(10)
$startedAt = Get-Date
$ready = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 15
    try {
        $health = Invoke-WebRequest -Uri "$funnelUrl/api/health" -UseBasicParsing -TimeoutSec 10
        if ($health.StatusCode -eq 200) { $ready = $true; break }
    }
    catch {
        Write-Host "  ...todavía no ($([int]((Get-Date) - $startedAt).TotalSeconds)s)" -ForegroundColor DarkGray
    }
}

Write-Host ""
if ($ready) {
    Write-Host "NUBE LISTA" -ForegroundColor Green
    Write-Host "  URL pública : $funnelUrl"
    Write-Host "  IP          : $ip"
    Write-Host "  SSH         : ssh root@$ip"
    Write-Host ""
    try {
        $status = Invoke-RestMethod -Uri "$funnelUrl/api/internal/status" `
            -Headers @{ 'X-Replication-Token' = $envMap['REPLICATION_TOKEN'] } -TimeoutSec 20
        Write-Host "  Replicación : $($status | ConvertTo-Json -Compress)"
    }
    catch {
        Write-Host "  Replicación : todavía no responde (normal durante los primeros segundos)" -ForegroundColor Yellow
    }
    Write-Host ""
    Write-Host "Apagado seguro al terminar el evento: .\deploy\nube-off.ps1" -ForegroundColor Cyan
}
else {
    Write-Host "La VM existe (id $serverId, IP $ip) pero el health check no respondió." -ForegroundColor Red
    Write-Host "Revisá: ssh root@$ip 'tail -50 /var/log/cloud-init-output.log'" -ForegroundColor Yellow
    Write-Host "Para borrarla: .\deploy\nube-off.ps1 -Force" -ForegroundColor Yellow
    exit 1
}
