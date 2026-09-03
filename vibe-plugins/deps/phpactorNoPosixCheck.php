<?php
// Copyright 2026 VibeBrains. Use of this source code is governed by the Apache 2.0 license.
//
// Prepended to the bundled phpactor.phar on Windows (php -d auto_prepend_file=…).
//
// The phar is built with Box, whose requirement checker refuses to start without ext-posix — an
// extension that does not exist in any Windows build of PHP. The requirement is false: every
// posix_* call inside the phar (symfony/process, symfony/console, monolog, php-xdg-base-dir, Box's
// own IO) is guarded by function_exists(), Phpactor's own code has none, and amphp/process picks
// its Windows runner by itself. Verified 03.09.2026 by running the language server with all forty
// posix_* functions disabled: `initialize` answers with real capabilities.
//
// So the only thing standing between Windows and a working PHP server is the checker, and Box
// documents exactly this switch for it. Nothing else is changed: no polyfills, no patched phar.
$_SERVER['BOX_REQUIREMENT_CHECKER'] = '0';
