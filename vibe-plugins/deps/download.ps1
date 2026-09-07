# Windows-версия download.sh: качает закреплённые внешние артефакты дистрибутива.
#
# Отдельный скрипт, а не «поставьте git-bash»: сборка под Windows должна начинаться на чистой
# машине с PowerShell, который там есть всегда, а не с уговоров поставить POSIX-окружение ради
# двадцати строк.
#
# Версии и хеши НЕ дублируются — читаются из pins.env, того же файла, что и у download.sh.
# Копия версии расходится не «если», а «когда», и расходится молча: обе сборки успешны,
# содержимое разное.
#
# Запуск:  powershell -ExecutionPolicy Bypass -File vibe-plugins\deps\download.ps1

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

function Read-Pins([string] $path) {
    $pins = @{}
    foreach ($line in Get-Content -Path $path) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $key, $value = $trimmed -split '=', 2
        $pins[$key] = $value
    }
    return $pins
}

# Проверка хеша — не формальность: без неё «скачалось» и «скачалось то самое» неразличимы, а
# отличаются они ровно в тот день, когда это важно.
function Assert-Sha256([string] $file, [string] $expected) {
    $actual = (Get-FileHash -Path $file -Algorithm SHA256).Hash.ToLower()
    if ($actual -ne $expected.ToLower()) {
        throw "$file : ожидался sha256 $expected, получен $actual"
    }
    Write-Host "  $file : sha256 совпал"
}

function Get-File([string] $url, [string] $file) {
    if (Test-Path -Path $file) { return }
    Write-Host "  качаю $file"
    # -UseBasicParsing: без него на серверных Windows без Internet Explorer команда падает.
    Invoke-WebRequest -Uri $url -OutFile $file -UseBasicParsing
}

$pins = Read-Pins -path (Join-Path $PSScriptRoot 'pins.env')

# --- LSP4IJ: клиент LSP и DAP, ставится готовым плагином ---
$lsp4ijZip = "lsp4ij-$($pins.LSP4IJ_V).zip"
Get-File "https://github.com/redhat-developer/lsp4ij/releases/download/$($pins.LSP4IJ_V)/$lsp4ijZip" $lsp4ijZip
Assert-Sha256 $lsp4ijZip $pins.LSP4IJ_SHA
if (Test-Path 'extracted') { Remove-Item -Recurse -Force 'extracted' }
New-Item -ItemType Directory -Path 'extracted' | Out-Null
Expand-Archive -Path $lsp4ijZip -DestinationPath 'extracted' -Force

# --- Phpactor: языковой сервер PHP, один phar на машинном PHP ---
$phar = "phpactor-$($pins.PHPACTOR_V).phar"
Get-File "https://github.com/phpactor/phpactor/releases/download/$($pins.PHPACTOR_V)/phpactor.phar" $phar
Assert-Sha256 $phar $pins.PHPACTOR_SHA
New-Item -ItemType Directory -Path 'extracted\servers' -Force | Out-Null
Copy-Item $phar 'extracted\servers\phpactor.phar' -Force
# MIT требует, чтобы текст лицензии ехал вместе с копией.
Get-File "https://raw.githubusercontent.com/phpactor/phpactor/$($pins.PHPACTOR_V)/LICENSE" 'phpactor-LICENSE'
Copy-Item 'phpactor-LICENSE' 'extracted\servers\phpactor-LICENSE' -Force
# Windows: the phar's own entry point exits without ext-posix, which no Windows PHP has — this
# launcher boots the phar's autoloader past that check and changes nothing else.
Copy-Item 'phpactorLaunch.php' 'extracted\servers\phpactorLaunch.php' -Force
"Phpactor $($pins.PHPACTOR_V) (MIT), bundled from the project release; see phpactor-LICENSE." |
    Set-Content -Path 'extracted\servers\README.txt' -Encoding UTF8

# --- Языковые серверы на Node: vtsls, CSS, ESLint ---
#
# `npm ci` по закреплённому package-lock.json, а не `npm install`: лок несёт integrity-хеши
# каждого пакета, то есть тот же уровень доверия, что sha256 у phar.
Push-Location 'servers-npm'
try {
    npm ci --omit=dev --no-audit --no-fund
    if ($LASTEXITCODE -ne 0) { throw "npm ci завершился с кодом $LASTEXITCODE" }
    # Вложенная копия TypeScript внутри vscode-langservers-extracted — 64 МБ дубликата, нужного
    # только серверам html и markdown, которых мы не подключаем.
    $nested = 'node_modules\vscode-langservers-extracted\node_modules\typescript'
    if (Test-Path $nested) { Remove-Item -Recurse -Force $nested }
}
finally {
    Pop-Location
}
if (Test-Path 'extracted\servers\node') { Remove-Item -Recurse -Force 'extracted\servers\node' }
New-Item -ItemType Directory -Path 'extracted\servers\node' -Force | Out-Null
Copy-Item 'servers-npm\node_modules' 'extracted\servers\node\node_modules' -Recurse -Force
'Language servers (MIT/Apache-2.0) installed from a pinned package-lock.json; licences travel inside each package.' |
    Set-Content -Path 'extracted\servers\node\README.txt' -Encoding UTF8

# --- Отладочные адаптеры ---
$jsTar = "js-debug-dap-v$($pins.JS_DEBUG_V).tar.gz"
Get-File "https://github.com/microsoft/vscode-js-debug/releases/download/v$($pins.JS_DEBUG_V)/$jsTar" $jsTar
Assert-Sha256 $jsTar $pins.JS_DEBUG_SHA
$jsDir = 'extracted\servers\dap\vibeJsDebug'
if (Test-Path $jsDir) { Remove-Item -Recurse -Force $jsDir }
New-Item -ItemType Directory -Path $jsDir -Force | Out-Null
# tar есть в Windows 10 1803 и новее; отдельного распаковщика tar.gz в PowerShell нет.
#
# Зовём ИМЕННО системный bsdtar по полному пути, а каталог передаём через «/». Просто `tar`
# берётся из PATH, и у всякого, кто поставил Git for Windows (то есть у любого, кто клонировал
# этот репозиторий), первым найдётся GNU tar из его usr/bin. Тот понимает обратный слэш как
# часть имени, а не как разделитель, и падает с «Cannot open: No such file or directory» на
# каталоге, созданном секундой раньше (поймано на живой Windows 04.09.2026).
$tarExe = Join-Path $env:SystemRoot 'system32\tar.exe'
if (-not (Test-Path $tarExe)) { $tarExe = 'tar' }
& $tarExe -xzf $jsTar -C ($jsDir.Replace('\', '/'))
if ($LASTEXITCODE -ne 0) { throw "tar не распаковал $jsTar (код $LASTEXITCODE)" }

$vsix = "php-debug-$($pins.PHP_DEBUG_V).vsix"
Get-File "https://github.com/xdebug/vscode-php-debug/releases/download/v$($pins.PHP_DEBUG_V)/$vsix" $vsix
Assert-Sha256 $vsix $pins.PHP_DEBUG_SHA
$phpDir = 'extracted\servers\dap\vibePhpDebug'
if (Test-Path $phpDir) { Remove-Item -Recurse -Force $phpDir }
New-Item -ItemType Directory -Path $phpDir -Force | Out-Null
# vsix — это zip; Expand-Archive не умеет распаковывать подкаталог, поэтому распаковываем целиком
# и удаляем всё, кроме extension\ — иконки маркетплейса и манифест расширения VS Code в IDE не
# нужны и весят.
$vsixTemp = Join-Path $phpDir '_vsix'
Copy-Item $vsix "$vsix.zip" -Force
Expand-Archive -Path "$vsix.zip" -DestinationPath $vsixTemp -Force
Remove-Item "$vsix.zip" -Force
Move-Item (Join-Path $vsixTemp 'extension') (Join-Path $phpDir 'extension') -Force
Remove-Item -Recurse -Force $vsixTemp

"vscode-js-debug $($pins.JS_DEBUG_V) (MIT) and vscode-php-debug $($pins.PHP_DEBUG_V) (MIT), bundled from project releases.`nLicences: vibeJsDebug\js-debug\LICENSE, vibePhpDebug\extension\LICENSE.txt" |
    Set-Content -Path 'extracted\servers\dap\README.txt' -Encoding UTF8

Write-Host ''
Write-Host 'Зависимости на месте:'
Write-Host "  LSP4IJ $($pins.LSP4IJ_V), Phpactor $($pins.PHPACTOR_V)"
Write-Host "  js-debug $($pins.JS_DEBUG_V), php-debug $($pins.PHP_DEBUG_V)"
