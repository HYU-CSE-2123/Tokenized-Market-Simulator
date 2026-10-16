@echo off
python "%~dp0deployment\playground.py" %*
if errorlevel 1 pause
