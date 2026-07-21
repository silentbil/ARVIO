@echo off
setlocal EnableDelayedExpansion
title ARVIO - Enable "Open in VLC"
echo.
echo   ================================================
echo    ARVIO  -  Enable "Open in VLC" for your browser
echo   ================================================
echo.
echo   This registers the vlc:// link handler so the "Open in VLC"
echo   button on web.arvio.tv opens streams directly in VLC, with no
echo   file download. It only touches YOUR user account (no admin
echo   needed) and changes nothing else.
echo.

REM --- Locate vlc.exe -----------------------------------------------------
set "VLC="
for %%P in (
  "%ProgramFiles%\VideoLAN\VLC\vlc.exe"
  "%ProgramFiles(x86)%\VideoLAN\VLC\vlc.exe"
  "%LOCALAPPDATA%\Programs\VideoLAN\VLC\vlc.exe"
) do if exist "%%~P" set "VLC=%%~P"

if not defined VLC (
  for /f "usebackq tokens=2,*" %%A in (`reg query "HKLM\SOFTWARE\VideoLAN\VLC" /v InstallDir 2^>nul ^| find "InstallDir"`) do set "VLCDIR=%%B"
  if defined VLCDIR if exist "!VLCDIR!\vlc.exe" set "VLC=!VLCDIR!\vlc.exe"
)

if not defined VLC (
  echo   [!] Could not find VLC on this PC.
  echo       Install VLC from https://www.videolan.org/vlc/ and run this again.
  echo.
  pause
  exit /b 1
)
echo   Found VLC: !VLC!
echo.

REM --- Write a tiny handler script next to this file's data dir ----------
set "HANDLERDIR=%LOCALAPPDATA%\ARVIO"
if not exist "%HANDLERDIR%" mkdir "%HANDLERDIR%"
set "HANDLER=%HANDLERDIR%\arvio-vlc.bat"
(
  echo @echo off
  echo setlocal EnableDelayedExpansion
  echo set "u=%%~1"
  echo set "u=!u:vlc://=!"
  echo start "VLC" "!VLC!" --open "!u!"
) > "%HANDLER%"

REM --- Register vlc:// under the current user (no admin required) --------
reg add "HKCU\Software\Classes\vlc" /ve /t REG_SZ /d "URL:vlc Protocol" /f >nul
reg add "HKCU\Software\Classes\vlc" /v "URL Protocol" /t REG_SZ /d "" /f >nul
reg add "HKCU\Software\Classes\vlc\DefaultIcon" /ve /t REG_SZ /d "\"!VLC!\",0" /f >nul
reg add "HKCU\Software\Classes\vlc\shell\open\command" /ve /t REG_SZ /d "\"%HANDLER%\" \"%%1\"" /f >nul

if errorlevel 1 (
  echo   [!] Registration failed.
  echo.
  pause
  exit /b 1
)

echo   ================================================
echo    Done!  "Open in VLC" now works on web.arvio.tv.
echo   ================================================
echo.
echo   Go back to ARVIO and click "Open in VLC" on any source.
echo   (You can close this window.)
echo.
pause
