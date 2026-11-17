@echo off
rem Double-clic : prepare le poste (Git, Java 21) et lance le projet. Arguments transmis au script.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup-and-run.ps1" %*
echo.
pause
