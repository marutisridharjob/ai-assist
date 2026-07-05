@echo off
rem Windows double-click launcher for ai-assist.
rem Put this file next to the ai-assist jar (or in the project root) and
rem double-click it. The app opens http://localhost:8080, starts listening
rem to the selected audio device, and drafts notes automatically.

cd /d "%~dp0"

set JAR=
for %%f in (ai-assist-*.jar) do set JAR=%%f
if "%JAR%"=="" for %%f in (target\ai-assist-*.jar) do set JAR=target\%%f
if "%JAR%"=="" (
  echo ai-assist jar not found. Build it first with: mvn package -Pfetch-model
  pause
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo Java 21+ is required. Install it from https://adoptium.net
  pause
  exit /b 1
)

echo Starting ai-assist... (close this window to stop)
java -jar "%JAR%"
pause
