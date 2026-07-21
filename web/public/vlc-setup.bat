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

REM --- Write the handler as a standalone PowerShell script ---------------
REM  The handler must (a) strip the vlc:// scheme prefix, (b) repair a mangled
REM  scheme slash (browsers/Windows can collapse vlc://https://... to https:/...),
REM  and (c) launch VLC with the real URL. Doing that string surgery in pure
REM  batch — especially written from inside a ( ) block — is fragile (the v1
REM  "set u=vlc://=" / blank --open "" bug, plus !-token and ^-escaping traps).
REM  So the handler is a .ps1: PowerShell handles !, & and :// with no escaping
REM  pain, and each echoed line below is plain text (no cmd-hostile characters).
REM  Delayed expansion is OFF here so nothing in the body is reinterpreted.
set "HANDLERDIR=%LOCALAPPDATA%\ARVIO"
if not exist "%HANDLERDIR%" mkdir "%HANDLERDIR%"
set "HANDLER=%HANDLERDIR%\arvio-vlc.ps1"
setlocal DisableDelayedExpansion
> "%HANDLER%" echo param([string]$Url)
>>"%HANDLER%" echo $vlc = '%VLC%'
>>"%HANDLER%" echo $u = $Url -replace '^^vlc:/*', ''
REM  Browsers parse vlc://https://... as scheme+authority and normalize the
REM  inner URL: Chrome drops the colon (https//...), others may collapse the
REM  slashes (https:/...). Repair ANY mangled scheme separator back to ://.
>>"%HANDLER%" echo $u = $u -replace '^^(https?)[:/]+', '$1://'
>>"%HANDLER%" echo if ($u) { Start-Process -FilePath $vlc -ArgumentList $u }
endlocal

REM --- Also (re)write the legacy .bat handler path as a delegating shim ---
REM  v1 of this installer registered %LOCALAPPDATA%\ARVIO\arvio-vlc.bat with
REM  broken logic. Browsers (Chrome) cache the resolved protocol handler for
REM  the whole browser session, so an already-running browser keeps launching
REM  that old .bat path even after re-registration. Overwriting it in place
REM  with a shim that delegates to the .ps1 fixes those cached sessions too.
> "%HANDLERDIR%\arvio-vlc.bat" echo @echo off
>>"%HANDLERDIR%\arvio-vlc.bat" echo powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "%%LOCALAPPDATA%%\ARVIO\arvio-vlc.ps1" "%%~1"

REM --- Register vlc:// under the current user (no admin required) --------
reg add "HKCU\Software\Classes\vlc" /ve /t REG_SZ /d "URL:vlc Protocol" /f >nul
reg add "HKCU\Software\Classes\vlc" /v "URL Protocol" /t REG_SZ /d "" /f >nul
reg add "HKCU\Software\Classes\vlc\DefaultIcon" /ve /t REG_SZ /d "\"!VLC!\",0" /f >nul
reg add "HKCU\Software\Classes\vlc\shell\open\command" /ve /t REG_SZ /d "powershell -NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File \"%HANDLER%\" \"%%1\"" /f >nul

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
