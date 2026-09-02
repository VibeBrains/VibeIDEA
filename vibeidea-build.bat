@echo off
setlocal

rem Одна команда, собирающая артефакты VibeIDEA на Windows.
rem
rem Скажите на машине с Windows: vibeidea-build.bat — и получите инсталлятор в out\vibeidea\artifacts.
rem Скрипт делает три шага в правильном порядке: зависимости по закреплённым версиям, сборка,
rem проверка. Порядок, который надо помнить, однажды выполнят наполовину.
rem
rem Аргументы уходят сборщику как есть (например --debug).
rem
rem Что нужно на машине заранее:
rem   * Git и клон android\ (getPlugins.bat) — без него инсталлятор не собирается;
rem   * Node.js — им ставятся языковые серверы;
rem   * около 100 ГБ свободного места; JDK и Bazel скачаются сами.

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"
set "PS=%SystemRoot%\system32\WindowsPowerShell\v1.0\powershell.exe"

if not exist "%ROOT%\android\" (
  echo.
  echo [!] Нет клона android\ — инсталлятор без него не соберётся.
  echo     Сначала выполните: getPlugins.bat
  exit /b 1
)

echo == 1/3  зависимости по закреплённым версиям
"%PS%" -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%ROOT%\vibe-plugins\deps\download.ps1"
if errorlevel 1 (
  echo.
  echo [!] Зависимости не скачались — сборка остановлена.
  echo     Собирать без них значит собрать не то: языковые серверы и отладчики просто не поедут.
  exit /b 1
)

echo.
echo == 2/3  сборка инсталляторов под текущую ОС
call "%ROOT%\vibeidea-installers.cmd" -Dintellij.build.target.os=current -Dintellij.build.target.arch=current %*
if errorlevel 1 (
  echo.
  echo [!] Сборка не прошла. Полный вывод выше; чаще всего это нехватка места на диске.
  exit /b 1
)

echo.
echo == 3/3  готово, артефакты:
dir /b "%ROOT%\out\vibeidea\artifacts\*.exe" 2>nul
dir /b "%ROOT%\out\vibeidea\artifacts\*.zip" 2>nul
echo.
echo Проверить собранное:
echo   powershell -ExecutionPolicy Bypass -File "%ROOT%\vibe-plugins\tools\checkVibeDist.ps1"
endlocal
