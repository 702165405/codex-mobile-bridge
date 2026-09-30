@echo off
setlocal
pushd "%~dp0"
where py >nul 2>&1
if not errorlevel 1 (
    py -3 -B "%~dp0run.py" --lan %*
) else (
    python -B "%~dp0run.py" --lan %*
)
set "result=%errorlevel%"
popd
if not "%result%"=="0" pause
exit /b %result%
