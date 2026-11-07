@echo off
chcp 65001 >nul
where py >nul 2>&1
if errorlevel 1 (
  echo Python launcher [py] not found. Install Python 3 and add it to PATH.
  exit /b 1
)
py -3 -m pip install -r gpu-requirements.txt
if errorlevel 1 exit /b 1
echo.
echo GPU requirements installed. Build the Java plugin next using JDK 25.
