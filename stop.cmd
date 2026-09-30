@echo off
setlocal
pushd "%~dp0"
where py >nul 2>&1
if not errorlevel 1 (
    py -3 -B "%~dp0stop.py" %*
) else (
    python -B "%~dp0stop.py" %*
)
set "result=%errorlevel%"
popd
if not "%result%"=="0" pause
exit /b %result%
