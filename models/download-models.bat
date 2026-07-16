@echo off
rem Downloads the Whisper speech models ai-assist uses for the accurate
rem complete-conversation transcript. Run it once; the models land in this
rem "models" folder, which the app searches automatically (next to the jar).
rem No Hugging Face account needed - these come from a GitHub mirror.
rem
rem   download-models.bat          fast + accurate (base + small)
rem   download-models.bat all      also grab medium (1.5 GB, most accurate)
rem   download-models.bat base     just one model by name
rem
rem The models are GGML files (whisper.cpp, MIT). They are multilingual and
rem transcribe English perfectly - the app picks up any ggml-*.bin it finds.

setlocal enabledelayedexpansion
set "BASE_URL=https://github.com/NoMercy-Entertainment/nomercy-whisper-models/releases/latest/download"
set "DIR=%~dp0"

set "MODELS=ggml-base.bin ggml-small.bin"
if /I "%~1"=="all"    set "MODELS=ggml-base.bin ggml-small.bin ggml-medium.bin"
if /I "%~1"=="base"   set "MODELS=ggml-base.bin"
if /I "%~1"=="small"  set "MODELS=ggml-small.bin"
if /I "%~1"=="medium" set "MODELS=ggml-medium.bin"

for %%m in (%MODELS%) do (
  if exist "%DIR%%%m" (
    echo [ok] %%m already present, skipping
  ) else (
    echo [..] downloading %%m ...
    curl -L --fail --progress-bar -o "%DIR%%%m.partial" "%BASE_URL%/%%m"
    move /Y "%DIR%%%m.partial" "%DIR%%%m" >nul
    echo [ok] saved %DIR%%%m
  )
)

echo.
echo Done. Models are in: %DIR%
echo Keep this "models" folder next to ai-assist-^<version^>.jar and the app
echo will use the model automatically on Stop.
endlocal
