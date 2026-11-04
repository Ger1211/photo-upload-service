@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\descargar-fotos.ps1" %*
if errorlevel 1 pause
