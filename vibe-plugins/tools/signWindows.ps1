# Подпись Windows-сборки — ПОСЛЕ сборки, отдельным шагом (близнец signMac.sh).
#
# Зачем: неподписанный idea64.exe с нулевой репутацией Касперский удалил по поведению
# (PDM:Trojan.Win32.Generic, 03.09.2026), а SmartScreen на каждом запуске инсталлятора показывает
# «Windows защитила ваш компьютер». Подпись лечит первое сразу, второе — по мере набора репутации
# (EV или Azure Trusted Signing — с первого файла).
#
# Что нужно от владельца (один раз):
#   вариант А — сертификат подписи кода (OV/EV) в хранилище пользователя: отпечаток в VIBE_WIN_SIGN_THUMBPRINT;
#   вариант Б — Azure Trusted Signing: dlib и metadata.json по документации Microsoft, путь к dlib в
#              VIBE_WIN_SIGN_DLIB, путь к metadata.json в VIBE_WIN_SIGN_METADATA.
#   signtool.exe из Windows SDK в PATH (или путь в VIBE_SIGNTOOL).
#
# Использование: .\vibe-plugins\tools\signWindows.ps1 [-DryRun] [-Zip <путь.win.zip>] [-Installer <путь.exe>]
param(
  [switch]$DryRun,
  [string]$Zip = '',
  [string]$Installer = ''
)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..\..')

function Say([string]$text) { Write-Host $text }

$artifacts = 'out\vibeidea\artifacts'
if (-not $Zip)       { $Zip       = Get-ChildItem "$artifacts\*.win.zip" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName }
if (-not $Installer) { $Installer = Get-ChildItem "$artifacts\*.exe"     -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName }

$signtool = if ($env:VIBE_SIGNTOOL) { $env:VIBE_SIGNTOOL } else { (Get-Command signtool.exe -ErrorAction SilentlyContinue).Source }
if (-not $signtool) { Say '✖ нет signtool.exe — поставьте Windows SDK (компонент «Windows SDK Signing Tools») или укажите путь в VIBE_SIGNTOOL'; exit 1 }

# Метка времени обязательна: без неё подпись умирает вместе с сертификатом, а сборка, скачанная
# через год, начинает ругаться так, будто её никто не подписывал.
$timestamp = if ($env:VIBE_SIGN_TIMESTAMP_URL) { $env:VIBE_SIGN_TIMESTAMP_URL } else { 'http://timestamp.digicert.com' }
$args = @('sign', '/fd', 'SHA256', '/tr', $timestamp, '/td', 'SHA256')
if ($env:VIBE_WIN_SIGN_DLIB -and $env:VIBE_WIN_SIGN_METADATA) {
  $args += @('/dlib', $env:VIBE_WIN_SIGN_DLIB, '/dmdf', $env:VIBE_WIN_SIGN_METADATA)
  $how = 'Azure Trusted Signing'
}
elseif ($env:VIBE_WIN_SIGN_THUMBPRINT) {
  $args += @('/sha1', $env:VIBE_WIN_SIGN_THUMBPRINT)
  $how = "сертификат $($env:VIBE_WIN_SIGN_THUMBPRINT)"
}
else {
  Say '✖ не задан ни VIBE_WIN_SIGN_THUMBPRINT (сертификат OV/EV), ни пара VIBE_WIN_SIGN_DLIB + VIBE_WIN_SIGN_METADATA (Azure Trusted Signing).'
  Say '  Сертификата подписи кода у сборки нет: пока его нет, бинарь выходит неподписанным, и заметки релиза обязаны говорить это прямо.'
  exit 2
}
if (-not $Zip -or -not (Test-Path $Zip)) { Say "✖ нет архива .win.zip в $artifacts — сначала соберите инсталлятор"; exit 1 }

Say "== подпись Windows: $how"
$work = Join-Path $env:TEMP ("vibeidea-sign-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $work | Out-Null
try {
  Expand-Archive -Path $Zip -DestinationPath $work -Force
  # Подписываются исполняемые файлы дистрибутива: оба лаунчера, нативные библиотеки в bin и JBR.
  # Не всё подряд: jar — не PE-файл, signtool на нём падает, а подпись ему и не нужна.
  $targets = Get-ChildItem -Path $work -Recurse -Include '*.exe','*.dll' | Select-Object -ExpandProperty FullName
  Say "  файлов к подписи в архиве: $($targets.Count)"
  if ($DryRun) {
    $targets | Select-Object -First 12 | ForEach-Object { Say "  [dry-run] $($_.Substring($work.Length + 1))" }
    if ($targets.Count -gt 12) { Say "  [dry-run] … и ещё $($targets.Count - 12)" }
    if ($Installer -and (Test-Path $Installer)) { Say "  [dry-run] инсталлятор: $Installer" }
    exit 0
  }
  foreach ($file in $targets) { & $signtool @args $file | Out-Null }
  $signedZip = $Zip -replace '\.win\.zip$', '-signed.win.zip'
  if (Test-Path $signedZip) { Remove-Item $signedZip -Force }
  Compress-Archive -Path (Join-Path $work '*') -DestinationPath $signedZip
  Say "✔ архив подписан: $signedZip"
  if ($Installer -and (Test-Path $Installer)) {
    # Инсталлятор подписывается как есть: его содержимое собрано до нас, но именно его хеш видит
    # SmartScreen и именно на него ругается «неизвестный издатель».
    & $signtool @args $Installer | Out-Null
    & $signtool verify /pa /v $Installer | Out-Null
    Say "✔ инсталлятор подписан: $Installer"
  }
  Say '  Публиковать подписанные файлы; неподписанные оригиналы оставлены рядом для сравнения хешей.'
}
finally {
  Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
