@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title HoloMapPerspective v0.6.0 - BUILD (stays open)

REM Optional: Specify the JDK 25 installation folder here if PATH uses Java 8.
REM Example: set "JDK25_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.1.8-hotspot"
set "JDK25_HOME="
if not defined JDK25_HOME if exist "C:\Program Files\Java\jdk-25\bin\javac.exe" set "JDK25_HOME=C:\Program Files\Java\jdk-25"
if defined JDK25_HOME (
  if not exist "!JDK25_HOME!\bin\javac.exe" (
    echo [ERROR] JDK25_HOME does not point to a JDK: !JDK25_HOME!
    goto :fail
  )
  set "JAVA_HOME=!JDK25_HOME!"
  set "PATH=!JAVA_HOME!\bin;!PATH!"
) else (
  if defined JAVA_HOME (
    if exist "!JAVA_HOME!\bin\javac.exe" set "PATH=!JAVA_HOME!\bin;!PATH!"
  )
)

set "BUILD_LOG=%~dp0build_log_FIX2.txt"
set "REASON=Unknown failure"

>"%BUILD_LOG%" echo HoloMapPerspective v0.6.0 build log
>>"%BUILD_LOG%" echo Folder: %CD%
>>"%BUILD_LOG%" echo JAVA_HOME: !JAVA_HOME!

echo ============================================================
echo  HoloMapPerspective BUILD FIX 2
 echo  This window will NOT close automatically.
echo ============================================================
echo Folder: %CD%
echo Log: %BUILD_LOG%
echo.

if not exist "gradlew.bat" (
  set "REASON=gradlew.bat not found. Place this BAT INSIDE HoloMapPerspective, next to gradlew.bat."
  goto :fail
)
if not exist "build.gradle.kts" (
  set "REASON=build.gradle.kts not found. Wrong directory."
  goto :fail
)
if not exist "gradle\wrapper\gradle-wrapper.jar" (
  set "REASON=Gradle wrapper JAR missing. Re-extract the full source ZIP."
  goto :fail
)

where java >nul 2>&1
if errorlevel 1 (
  set "REASON=java.exe not found. Install JDK 25."
  goto :fail
)
where javac >nul 2>&1
if errorlevel 1 (
  set "REASON=javac.exe not found. Install JDK 25, not only a JRE."
  goto :fail
)

echo ---- Java locations ----
where java
where javac
where java >>"%BUILD_LOG%" 2>&1
where javac >>"%BUILD_LOG%" 2>&1
echo.
echo ---- Java versions ----
java -version 2>&1
javac -version 2>&1
java -version >>"%BUILD_LOG%" 2>&1
javac -version >>"%BUILD_LOG%" 2>&1

set "JAVAC_VERSION="
for /f "tokens=2" %%V in ('javac -version 2^>^&1') do if not defined JAVAC_VERSION set "JAVAC_VERSION=%%V"
if not defined JAVAC_VERSION (
  set "REASON=Could not determine javac version."
  goto :fail
)
set "JAVAC_MAJOR="
for /f "tokens=1 delims=." %%M in ("!JAVAC_VERSION!") do set "JAVAC_MAJOR=%%M"
if "!JAVAC_MAJOR!"=="1" for /f "tokens=2 delims=." %%M in ("!JAVAC_VERSION!") do set "JAVAC_MAJOR=%%M"
set /a CHECK_MAJOR=!JAVAC_MAJOR! >nul 2>&1
if errorlevel 1 (
  set "REASON=Could not parse javac version: !JAVAC_VERSION!"
  goto :fail
)
if !CHECK_MAJOR! LSS 25 (
  set "REASON=JDK !JAVAC_VERSION! is too old; JDK 25 is required. Set JDK25_HOME near the top of this BAT if already installed."
  goto :fail
)

echo.
echo [OK] JDK !JAVAC_VERSION! detected.
echo Running Gradle now. Output is saved to the log while building.
echo The log will be printed in this window when Gradle finishes.
echo.
>>"%BUILD_LOG%" echo ---- Gradle output ----
call ".\gradlew.bat" --no-daemon clean build --stacktrace >>"%BUILD_LOG%" 2>&1
set "GRADLE_RESULT=!ERRORLEVEL!"

echo ---- Gradle output ----
type "%BUILD_LOG%"
echo.
if not "!GRADLE_RESULT!"=="0" (
  set "REASON=Gradle exited with code !GRADLE_RESULT!. See build_log_FIX2.txt."
  goto :fail
)
if not exist "build\libs\HoloMapPerspective-0.6.0.jar" (
  set "REASON=Gradle returned 0 but build\libs\HoloMapPerspective-0.6.0.jar was not found."
  goto :fail
)

echo ============================================================
echo [SUCCESS] JAR was verified:
echo %CD%\build\libs\HoloMapPerspective-0.6.0.jar
echo ============================================================
>>"%BUILD_LOG%" echo [SUCCESS] JAR exists.
goto :finish

:fail
echo.
echo ============================================================
echo [FAILED] !REASON!
echo ============================================================
if defined BUILD_LOG >>"%BUILD_LOG%" echo [FAILED] !REASON!

:finish
echo.
echo Log: %BUILD_LOG%
echo Please send build_log_FIX2.txt if the build fails.
echo Press any key to close this window.
pause >nul
endlocal
