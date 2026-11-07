@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0ServerControl.ps1" -Command "stop"
pause
