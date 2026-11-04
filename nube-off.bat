@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy\nube-off.ps1" %*
if errorlevel 1 pause
