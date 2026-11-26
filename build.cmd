@echo off
setlocal
set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "TOOLS=A:\AndroidToolchain"
set "LINK=A:\byedpi-build"

rem AGP не принимает путь с кириллицей, поэтому собираем через ASCII-junction.
if not exist "%LINK%\settings.gradle.kts" (
  if exist "%LINK%" rmdir "%LINK%"
  mklink /J "%LINK%" "%ROOT%" >nul
)

set "JAVA_HOME=%TOOLS%\jdk"
set "ANDROID_HOME=%TOOLS%\sdk"
set "ANDROID_SDK_ROOT=%TOOLS%\sdk"
set "PATH=%JAVA_HOME%\bin;%PATH%"

call "%TOOLS%\gradle-8.7\bin\gradle.bat" -p "%LINK%" %*
if errorlevel 1 exit /b %errorlevel%

if exist "%LINK%\app\build\outputs\apk\release\app-release.apk" (
  copy /Y "%LINK%\app\build\outputs\apk\release\app-release.apk" "%ROOT%\dist\ByeDpiAutostart.apk" >nul
  echo APK: %ROOT%\dist\ByeDpiAutostart.apk
)
endlocal
