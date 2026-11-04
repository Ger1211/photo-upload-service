#requires -Version 5.1
<#
.SYNOPSIS
    Schedules the cloud VM to come up and go down for the event.

.DESCRIPTION
    Registers two Windows Scheduled Tasks that run nube-on.ps1 and nube-off.ps1
    at the given local times. A scheduled task has no console, so the output is
    appended to deploy\logs\.

    The tasks run inside the current user's session on purpose: the server-copy
    download uses the SSH key in the user profile, and Docker Desktop runs there.

.PARAMETER On
    When to create the VM. Default: 2026-12-04 07:00 (Argentina time).

.PARAMETER Off
    When to destroy it. Default: 2026-12-05 18:00 (Argentina time).

.PARAMETER Remove
    Unregisters both tasks.

.PARAMETER Status
    Shows the registered tasks and their next run.

.PARAMETER Test
    Registers a throwaway task two minutes from now, waits for it, then removes
    it. Proves the scheduler works on this machine without touching the cloud.

.EXAMPLE
    .\deploy\programar.ps1 -Status
#>
[CmdletBinding()]
param(
    [datetime]$On = [datetime]'2026-12-04 07:00',
    [datetime]$Off = [datetime]'2026-12-05 18:00',
    [switch]$Remove,
    [switch]$Status,
    [switch]$Test
)

$ErrorActionPreference = 'Stop'

$OnTaskName = 'PhotoUpload-Nube-On'
$OffTaskName = 'PhotoUpload-Nube-Off'
$TestTaskName = 'PhotoUpload-Scheduler-Test'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$LogDir = Join-Path $PSScriptRoot 'logs'

function New-CloudAction {
    param([string]$ScriptPath, [string]$LogPath)
    $argument = '/c powershell -NoProfile -ExecutionPolicy Bypass -File "' + $ScriptPath + '" >> "' + $LogPath + '" 2>&1'
    return New-ScheduledTaskAction -Execute 'cmd.exe' -Argument $argument
}

function New-CloudSettings {
    return New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun `
        -ExecutionTimeLimit (New-TimeSpan -Minutes 30) -MultipleInstances IgnoreNew
}

function Get-CurrentPrincipal {
    return New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" `
        -LogonType Interactive -RunLevel Limited
}

function Show-TaskState {
    param([string]$TaskName)
    $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    if (-not $task) {
        Write-Host "  $TaskName : NO registrada" -ForegroundColor Yellow
        return
    }
    $info = Get-ScheduledTaskInfo -TaskName $TaskName
    Write-Host ("  {0} : {1} | proxima: {2} | ultima: {3}" -f `
            $TaskName, $task.State, $info.NextRunTime, $info.LastRunTime)
}

if ($Status) {
    Write-Host "== Tareas programadas ==" -ForegroundColor Cyan
    Show-TaskState -TaskName $OnTaskName
    Show-TaskState -TaskName $OffTaskName
    Show-TaskState -TaskName $TestTaskName
    exit 0
}

if ($Remove) {
    foreach ($name in @($OnTaskName, $OffTaskName, $TestTaskName)) {
        $task = Get-ScheduledTask -TaskName $name -ErrorAction SilentlyContinue
        if ($task) {
            Unregister-ScheduledTask -TaskName $name -Confirm:$false
            Write-Host "Eliminada: $name" -ForegroundColor Yellow
        }
    }
    exit 0
}

New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

if ($Test) {
    $testFile = Join-Path $LogDir 'scheduler-test.txt'
    if (Test-Path $testFile) { Remove-Item $testFile -Force }

    $when = (Get-Date).AddMinutes(2)
    $action = New-ScheduledTaskAction -Execute 'cmd.exe' `
        -Argument ('/c echo ok %DATE% %TIME% > "' + $testFile + '"')

    Write-Host "Registrando tarea de prueba para $($when.ToString('HH:mm:ss')) ..." -ForegroundColor Cyan
    Register-ScheduledTask -TaskName $TestTaskName -Action $action `
        -Trigger (New-ScheduledTaskTrigger -Once -At $when) `
        -Settings (New-ScheduledTaskSettingsSet -StartWhenAvailable) `
        -Principal (Get-CurrentPrincipal) -Force | Out-Null

    $deadline = (Get-Date).AddMinutes(5)
    while ((Get-Date) -lt $deadline -and -not (Test-Path $testFile)) {
        Start-Sleep -Seconds 5
    }

    $ok = Test-Path $testFile
    Unregister-ScheduledTask -TaskName $TestTaskName -Confirm:$false -ErrorAction SilentlyContinue

    if ($ok) {
        Write-Host "OK: el Programador de tareas funciona en esta maquina." -ForegroundColor Green
        Write-Host ("  Contenido: " + (Get-Content $testFile))
        Remove-Item $testFile -Force
    }
    else {
        Write-Host "FALLO: la tarea de prueba no se ejecuto." -ForegroundColor Red
        Write-Host "  Revisa que la PC no este suspendida y que la sesion este iniciada." -ForegroundColor Yellow
        exit 1
    }
    exit 0
}

# --- Register the two real tasks ---------------------------------------------
if ($On -le (Get-Date)) {
    Write-Host "La fecha de encendido ($On) ya paso." -ForegroundColor Red
    exit 1
}
if ($Off -le $On) {
    Write-Host "La fecha de apagado ($Off) debe ser posterior a la de encendido ($On)." -ForegroundColor Red
    exit 1
}

$onAction = New-CloudAction -ScriptPath (Join-Path $PSScriptRoot 'nube-on.ps1') `
    -LogPath (Join-Path $LogDir ('nube-on-' + $On.ToString('yyyyMMdd') + '.log'))
$offAction = New-CloudAction -ScriptPath (Join-Path $PSScriptRoot 'nube-off.ps1') `
    -LogPath (Join-Path $LogDir ('nube-off-' + $Off.ToString('yyyyMMdd') + '.log'))

Write-Host "Registrando tareas..." -ForegroundColor Cyan

Register-ScheduledTask -TaskName $OnTaskName -Action $onAction `
    -Trigger (New-ScheduledTaskTrigger -Once -At $On) `
    -Settings (New-CloudSettings) -Principal (Get-CurrentPrincipal) `
    -Description 'Crea la VM de fotos (nube primaria) para el evento' -Force | Out-Null

Register-ScheduledTask -TaskName $OffTaskName -Action $offAction `
    -Trigger (New-ScheduledTaskTrigger -Once -At $Off) `
    -Settings (New-CloudSettings) -Principal (Get-CurrentPrincipal) `
    -Description 'Verifica la replica, baja la copia del servidor y borra la VM' -Force | Out-Null

Write-Host ""
Write-Host "Listo" -ForegroundColor Green
Show-TaskState -TaskName $OnTaskName
Show-TaskState -TaskName $OffTaskName
Write-Host ""
Write-Host "  Encendido : $($On.ToString('dddd dd/MM/yyyy HH:mm')) (hora local)"
Write-Host "  Apagado   : $($Off.ToString('dddd dd/MM/yyyy HH:mm')) (hora local)"
Write-Host "  Logs      : $LogDir"
Write-Host ""
Write-Host "La PC tiene que estar prendida y con la sesion iniciada a esa hora." -ForegroundColor Yellow
Write-Host "Para deshacer: .\deploy\programar.ps1 -Remove" -ForegroundColor Cyan
