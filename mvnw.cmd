@echo off
where mvn >nul 2>nul
if %ERRORLEVEL%==0 (
  mvn %*
  exit /b %ERRORLEVEL%
)

echo mvn was not found in PATH. 1>&2
echo Install Maven or update IntelliJ to use a bundled/local Maven distribution. 1>&2
exit /b 1
