<?php
// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
//
// Starts the bundled Phpactor phar on Windows: `php phpactorLaunch.php language-server`.
//
// Why a launcher and not a flag. The phar's own entry point (bin/phpactor) begins with
//
//     $missing = array_filter(['mbstring', 'posix', 'tokenizer'], fn($e) => !extension_loaded($e));
//     if ($missing) { fwrite(STDERR, '…which are not loaded: …'); exit(255); }
//
// and `posix` does not exist in ANY Windows build of PHP, so that exit is unconditional there. It
// is not Box's requirement checker — switching that off (what we shipped in 0.4.0) never reaches
// this line. The check is also wrong for Phpactor itself: every posix_* call inside the phar is
// guarded by function_exists(), amphp picks its Windows process runner on its own, and the
// language server answers `initialize` with its full capabilities without either extension —
// verified 04.09.2026 on Windows 11 with PHP 8.4 and no php.ini at all.
//
// So this file does what bin/phpactor does, minus that one array: find the autoloader inside the
// phar, keep the PHP version floor, and hand control to Phpactor's own Application. Nothing is
// patched, nothing is polyfilled — the phar we ship stays byte-for-byte the pinned release.
//
// It lives next to the phar and finds it by __DIR__, so the command line stays one argument.

$phar = __DIR__ . DIRECTORY_SEPARATOR . 'phpactor.phar';
if (!is_file($phar)) {
    fwrite(STDERR, "phpactorLaunch.php: phpactor.phar is not next to it ($phar)\n");
    exit(255);
}

// The floor bin/phpactor enforces. Dropping it together with the extension check would turn a clear
// «requires PHP 8.2» into a stack trace from the middle of the autoloader.
$minVersion = '8.2.0';
if (version_compare(PHP_VERSION, $minVersion) < 0) {
    fwrite(STDERR, sprintf('Phpactor requires at least PHP %s, this is %s', $minVersion, PHP_VERSION) . "\n");
    exit(255);
}

$autoloadFile = 'phar://' . $phar . '/vendor/autoload.php';
if (!file_exists($autoloadFile)) {
    fwrite(STDERR, "phpactorLaunch.php: no autoloader inside the phar ($autoloadFile)\n");
    exit(255);
}

// Same noise suppression as bin/phpactor: deprecations from a pinned dependency are not the user's
// business, and on stdout they would corrupt the LSP stream.
if (!getenv('PHPACTOR_DEPRECATIONS')) {
    error_reporting(\E_ALL & ~\E_DEPRECATED & ~\E_NOTICE);
}
ini_set('display_errors', 'stderr');

// Symfony's console reads $_SERVER['argv'], not $argv: without this line it would take this
// launcher's own path as the command name and answer «no commands defined in the "D" namespace».
array_shift($argv);
array_unshift($argv, $phar);
$_SERVER['argv'] = $argv;
$_SERVER['argc'] = count($argv);

require_once $autoloadFile;

$application = new Phpactor\Application(dirname($autoloadFile), $argv[0]);
$output = new Symfony\Component\Console\Output\ConsoleOutput();
try {
    $application->run(null, $output);
} catch (Exception $e) {
    $application->renderThrowable($e, $output);
    exit(255);
}
