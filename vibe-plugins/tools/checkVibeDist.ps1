# Гейт дистрибутива для Windows: проверяет СОБРАННЫЙ образ, а не исходники.
#
# Windows-близнец checkVibeDist.sh, и он нужен по той же причине, по которой появился оригинал: за
# один день 31.08.2026 четыре дефекта подряд нашлись только установкой — библиотека не доехала в
# дистрибутив, готовый плагин не грузился мимо индекса, встроенный сервер искался в чужой папке.
# Юнит-тесты слепы к упаковке по построению, и на Windows они слепы ровно так же.
#
# Запуск:
#   powershell -ExecutionPolicy Bypass -File vibe-plugins\tools\checkVibeDist.ps1 [путь к .zip или к папке]
# Без аргумента берётся свежий win.zip из out\vibeidea\artifacts.

param([string] $Target = '')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$script:fail = 0
function Say([string] $text) { Write-Host $text }
function Fail([string] $text) { Write-Host $text; $script:fail = 1 }

if ($Target -eq '') {
    $zip = Get-ChildItem -Path 'out\vibeidea\artifacts' -Filter '*.win.zip' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -eq $zip) {
        $zip = Get-ChildItem -Path 'out\vibeidea\artifacts' -Filter '*.zip' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    if ($null -eq $zip) {
        Write-Host '✖ нет собранного zip в out\vibeidea\artifacts — сначала соберите инсталлятор (vibeidea-build.bat)'
        exit 1
    }
    $Target = $zip.FullName
}

# Распакованная папка проверяется как есть; архив — во временную. Проверять установленную копию
# тоже можно: аргументом.
$temp = $null
if ($Target.ToLower().EndsWith('.zip')) {
    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("vibeidea-dist-" + [System.Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $temp | Out-Null
    Expand-Archive -Path $Target -DestinationPath $temp -Force
    $app = $temp
}
else {
    $app = $Target
}

Say "  проверяю $Target"

try {
    # --- 1. Наши плагины на месте ---
    foreach ($plugin in @('vibe-agent', 'vibe-lsp', 'vibe-server', 'vibe-theme', 'vibe-http', 'vibe-db')) {
        if (-not (Test-Path (Join-Path $app "plugins\$plugin"))) { Fail "✖ нет плагина $plugin" }
    }

    # --- 2. Готовые плагины ВПИСАНЫ В ИНДЕКС, а не просто скопированы ---
    # Каталог в plugins\ ничего не значит: платформа грузит встроенные плагины только по
    # plugin-classpath.txt (разбор — knowledge/build/bundledPluginIndex.md).
    $index = Join-Path $app 'plugins\plugin-classpath.txt'
    if (-not (Test-Path $index)) {
        Fail "✖ нет индекса встроенных плагинов $index"
    }
    else {
        $bytes = [System.IO.File]::ReadAllBytes($index)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $hits = ([regex]::Matches($text, 'lsp4ij', 'IgnoreCase')).Count
        # Двух-трёх упоминаний недостаточно: столько даёт само имя каталога в путях.
        if ($hits -lt 20) {
            Fail "✖ LSP4IJ не вписан в индекс встроенных плагинов (упоминаний: $hits) — плагин не загрузится,"
            Say  "  и вместе с ним молча исчезнут TypeScript, PHP, CSS и ESLint."
        }
    }

    # --- 3. Библиотеки, которые едут внутри наших плагинов ---
    if (-not (Test-Path (Join-Path $app 'plugins\vibe-agent\lib\zxing-core.jar'))) {
        Fail '✖ нет zxing-core.jar в vibe-agent\lib — QR-код адреса превью не заработает.'
        Say  '  Зависимость в BUILD.bazel на упаковку не влияет: её решает раскладка плагина.'
    }

    # --- 3a. Задачи и трекеры как в PhpStorm: приезжают плагином intellij.tasks.core ---
    if (-not (Test-Path (Join-Path $app 'plugins\tasks'))) {
        Fail '✖ нет плагина задач (plugins\tasks) — Open Task и трекеры не появятся'
    }
    foreach ($p in @('dotenv', 'xpath', 'jsonpath')) {
        if (-not (Test-Path (Join-Path $app "plugins\$p"))) { Fail "✖ нет плагина $p — он есть в дереве, но не попал в дистрибутив" }
    }

    # --- 4. Языковые серверы в комплекте ---
    $servers = Join-Path $app 'plugins\vibe-lsp\servers'
    foreach ($entry in @(
        'phpactor.phar',
        'phpactor-LICENSE',
        'phpactorLaunch.php',
        'node\node_modules\@vtsls\language-server\bin\vtsls.js',
        'node\node_modules\vscode-langservers-extracted\bin\vscode-css-language-server',
        'node\node_modules\vscode-langservers-extracted\bin\vscode-eslint-language-server')) {
        if (-not (Test-Path (Join-Path $servers $entry))) { Fail "✖ нет встроенного сервера: $entry" }
    }

    # --- 4a. Phpactor на Windows: phar стартует только через лаунчер ---
    # Сам себя phar на Windows не запускает никогда: его первая строка — extension_loaded('posix')
    # и exit(255), а этого расширения нет ни в одной сборке PHP под Windows. Поэтому проверяется
    # именно боевой путь — запуск через лаунчер, а не через phar с какими-либо опциями.
    $php = Get-Command php -ErrorAction SilentlyContinue
    $launcher = Join-Path $servers 'phpactorLaunch.php'
    if ($null -eq $php) {
        Say '  php не найден — запуск встроенного Phpactor не проверялся'
    }
    elseif (Test-Path $launcher) {
        $out = & $php.Source $launcher --version 2>&1 | Out-String
        if ($out -match 'Phpactor') { Say "  phpactor.phar стартует через лаунчер: $($out.Trim())" }
        else { Fail "✖ phpactor.phar не стартует через лаунчер — PHP на Windows не заработает: $($out.Trim())" }
    }

    # --- 5. Файл на месте — это ещё не работающий сервер ---
    $node = Get-Command node -ErrorAction SilentlyContinue
    $cssEntry = Join-Path $servers 'node\node_modules\vscode-langservers-extracted\bin\vscode-css-language-server'
    if ($null -eq $node) {
        Say '  node не найден — запуск встроенных серверов и адаптеров не проверялся'
    }
    elseif (Test-Path $cssEntry) {
        $body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
        $request = "Content-Length: $($body.Length)`r`n`r`n$body"
        $answer = ($request | & node $cssEntry --stdio 2>$null | Select-Object -First 1)
        if ($answer -notmatch 'definitionProvider') {
            Fail '✖ встроенный CSS-сервер не ответил на initialize'
        }
    }

    # --- 5б. Отладочные адаптеры: файлы на месте И запускаются ---
    $jsDap = Join-Path $servers 'dap\vibeJsDebug\js-debug\src\dapDebugServer.js'
    $phpDap = Join-Path $servers 'dap\vibePhpDebug\extension\out\phpDebug.js'
    if (-not (Test-Path $jsDap)) { Fail '✖ нет встроенного адаптера vscode-js-debug' }
    if (-not (Test-Path $phpDap)) { Fail '✖ нет встроенного адаптера vscode-php-debug' }
    if (-not (Test-Path (Join-Path $servers 'dap\vibeJsDebug\js-debug\LICENSE'))) { Fail '✖ нет лицензии vscode-js-debug' }
    if (-not (Test-Path (Join-Path $servers 'dap\vibePhpDebug\extension\LICENSE.txt'))) { Fail '✖ нет лицензии vscode-php-debug' }

    if ($null -ne $node -and (Test-Path $jsDap)) {
        # Порт 0 — ОС выбирает свободный: зашитый номер однажды окажется занят процессом от
        # прошлого прогона, и гейт обвинит дистрибутив в том, чего в нём нет.
        $proc = Start-Process -FilePath 'node' -ArgumentList @($jsDap, '0', '127.0.0.1') `
            -RedirectStandardOutput "$env:TEMP\vibe-jsdap.log" -NoNewWindow -PassThru
        Start-Sleep -Seconds 4
        if (-not $proc.HasExited) { $proc.Kill() }
        $out = Get-Content "$env:TEMP\vibe-jsdap.log" -Raw -ErrorAction SilentlyContinue
        if ($out -notmatch 'Debug server listening at') {
            Fail '✖ встроенный адаптер vscode-js-debug не поднял сервер отладки'
        }
        Remove-Item "$env:TEMP\vibe-jsdap.log" -ErrorAction SilentlyContinue
    }

    if ($null -ne $node -and (Test-Path $phpDap)) {
        # pathFormat обязателен: без него адаптер отвечает отказом «only supports native paths», и
        # проверка «ответил ли» приняла бы отказ за успех.
        $body = '{"seq":1,"type":"request","command":"initialize","arguments":{"adapterID":"php","clientID":"vibe","pathFormat":"path"}}'
        $request = "Content-Length: $($body.Length)`r`n`r`n$body"
        $answer = ($request | & node $phpDap 2>$null | Select-Object -First 5) -join ''
        if ($answer -notmatch '"success":true') {
            Fail '✖ встроенный адаптер vscode-php-debug не ответил успехом на initialize'
        }
    }

    # --- 6. Лицензии поставляемых серверов названы, и версии не разъехались ---
    # Отчёт о третьих лицах генерируется из ЗАВИСИМОСТЕЙ модулей: phar и npm-дерево ему не видны,
    # их приходится объявлять руками — а значит версия объявленного однажды разойдётся с закреплённой.
    $report = Get-ChildItem -Path (Join-Path $app 'license') -Filter 'third-party-libraries.json' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $report) {
        Fail '✖ в дистрибутиве нет отчёта о третьих лицах (license\third-party-libraries.json)'
    }
    else {
        $reportText = Get-Content $report.FullName -Raw
        $pins = @{}
        foreach ($line in Get-Content 'vibe-plugins\deps\pins.env') {
            $trimmed = $line.Trim()
            if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
            $key, $value = $trimmed -split '=', 2
            $pins[$key] = $value
        }
        foreach ($key in @('PHPACTOR_V', 'JS_DEBUG_V', 'PHP_DEBUG_V')) {
            if ($reportText -notmatch [regex]::Escape('"' + $pins[$key] + '"')) {
                Fail "✖ версия из $key ($($pins[$key])) не совпадает с отчётом о лицензиях"
            }
        }
    }
}
finally {
    if ($null -ne $temp -and (Test-Path $temp)) { Remove-Item -Recurse -Force $temp }
}

if ($script:fail -ne 0) {
    Say 'Гейт дистрибутива: ПРОВАЛЕН'
    exit 1
}
Say 'Гейт дистрибутива: плагины в индексе, библиотеки и серверы на месте и запускаются'
