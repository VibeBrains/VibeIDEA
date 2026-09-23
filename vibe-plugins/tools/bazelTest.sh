#!/usr/bin/env bash
# One filtered `bazel test` run for a gate, sorted into three answers:
#
#   pass    test cases ran, and every one of them passed;
#   fail    test cases ran, and at least one failed: a verdict on the code under test;
#   notrun  no verdict at all: the build or the Bazel environment failed, the filter matched no test case, or the
#           run ended without a single failing case (a timeout, a crashed test JVM).
#
# The answer comes from Bazel's exit code (0 passed, 3 tests failed, anything else is the build or the environment)
# and from its own summary line "Test cases: finished with P passing, S skipped and F failing out of N test cases",
# never from words in the log: a build that did not complete prints "FAILED" as well, and a gate matching words
# reported a broken build as a failing test. A filter that matches nothing exits 0 with "1 test passes", so a pass
# also needs at least one passing case, or renaming the test would turn its gate green without measuring anything.
#
# Usage, from a gate running under `set -euo pipefail` (nothing here changes the caller's shell options):
#   . vibe-plugins/tools/bazelTest.sh
#   bazel_test_verdict //vibe-plugins/vibe-agent:vibe-agent_test SettingsHintWidth
#   case "$BAZEL_TEST_VERDICT" in pass) … ;; fail) … ;; *) echo "$BAZEL_TEST_REASON" ;; esac
#   bazel_test_evidence    # the lines of the Bazel output that back the verdict, indented

BAZEL_TEST_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

bazel_test_verdict() {
  local target="$1" filter="$2" summary total
  BAZEL_TEST_CODE=0
  BAZEL_TEST_OUT=$(cd "$BAZEL_TEST_ROOT" && ./bazel.cmd test "$target" --test_filter="$filter" 2>&1) || BAZEL_TEST_CODE=$?
  summary=$(printf '%s\n' "$BAZEL_TEST_OUT" | grep -E '^Test cases: ' | tail -1 || true)
  BAZEL_TEST_PASSED=$(printf '%s\n' "$summary" | sed -nE 's/.*[^0-9]([0-9]+) passing.*/\1/p')
  BAZEL_TEST_FAILED=$(printf '%s\n' "$summary" | sed -nE 's/.*[^0-9]([0-9]+) failing.*/\1/p')
  total=$(printf '%s\n' "$summary" | sed -nE 's/.* out of ([0-9]+) test case.*/\1/p')
  BAZEL_TEST_PASSED=${BAZEL_TEST_PASSED:-0}
  BAZEL_TEST_FAILED=${BAZEL_TEST_FAILED:-0}
  BAZEL_TEST_REASON=""

  if [ "$BAZEL_TEST_CODE" -eq 0 ] && [ "$BAZEL_TEST_PASSED" -gt 0 ] && [ "$BAZEL_TEST_FAILED" -eq 0 ]; then
    BAZEL_TEST_VERDICT=pass
  elif [ "$BAZEL_TEST_CODE" -eq 3 ] && [ "$BAZEL_TEST_FAILED" -gt 0 ]; then
    BAZEL_TEST_VERDICT=fail
  else
    BAZEL_TEST_VERDICT=notrun
    if [ "$BAZEL_TEST_CODE" -eq 0 ] && [ "${total:-}" = 0 ]; then
      BAZEL_TEST_REASON="фильтр «$filter» не нашёл ни одного теста, а Bazel считает такой прогон успешным"
    elif [ "$BAZEL_TEST_CODE" -eq 0 ] && [ -n "$summary" ]; then
      BAZEL_TEST_REASON="ни один случай не прошёл ($summary)"
    elif [ "$BAZEL_TEST_CODE" -eq 0 ]; then
      BAZEL_TEST_REASON="в выводе Bazel нет итога тестов"
    elif [ "$BAZEL_TEST_CODE" -eq 3 ]; then
      BAZEL_TEST_REASON="тесты оборвались, не дойдя до упавшего случая — таймаут или падение JVM"
    else
      BAZEL_TEST_REASON="сборка или окружение Bazel отказали (код $BAZEL_TEST_CODE)"
    fi
  fi
}

bazel_test_evidence() {
  local lines=""
  case "$BAZEL_TEST_VERDICT" in
    fail)
      lines=$(printf '%s\n' "$BAZEL_TEST_OUT" | grep -E '^[[:space:]]+FAILED[[:space:]]|AssertionFailedError|AssertionError' | head -8 || true) ;;
    notrun)
      # Bazel's own ERROR lines name the failed step, the compiler's "Error:" line names the cause, and the
      # tab-indented line under it is the file and position.
      lines=$(printf '%s\n' "$BAZEL_TEST_OUT" \
        | awk '/^(ERROR|FATAL):|[Ee]rror: / { print; under = 1; next } under && /^\t/ { print } { under = 0 }' \
        | head -8 || true)
      [ -n "$lines" ] || lines=$(printf '%s\n' "$BAZEL_TEST_OUT" | tail -5) ;;
  esac
  [ -z "$lines" ] || printf '%s\n' "$lines" | sed 's/^/    /'
}
