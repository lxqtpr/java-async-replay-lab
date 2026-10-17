#!/usr/bin/env bash
set -eu

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESULTS_FILE="$ROOT_DIR/artifacts/results.csv"
RUNS="${1:-100}"
REPORT_DIR="$ROOT_DIR/demo-order-service/target/surefire-reports"
TEST_PACKAGE="com.example.deterministicasync.demo"
SUMMARY_MAIN="com.example.deterministicasync.experiment.CsvSummaryMain"
EXPERIMENT_TESTS="NaiveFlakyTest,AwaitilityBaselineTest,DeterministicAsyncTest"

cd "$ROOT_DIR"

if ! [[ "$RUNS" =~ ^[0-9]+$ ]] || [ "$RUNS" -le 0 ]; then
  echo "RUNS must be a positive integer, got: $RUNS" >&2
  exit 1
fi

now_ms() {
  perl -MTime::HiRes=time -e 'printf("%.0f", time() * 1000)'
}

report_xml() {
  local test_class="$1"
  echo "$REPORT_DIR/TEST-$TEST_PACKAGE.$test_class.xml"
}

extract_testsuite_attr() {
  local file="$1"
  local attr="$2"
  if [ ! -f "$file" ]; then
    echo "0"
    return
  fi

  local value
  value="$(grep -o "${attr}=\"[0-9][0-9]*\"" "$file" | head -n 1 | cut -d '"' -f 2 || true)"
  echo "${value:-0}"
}

extract_duration_ms() {
  local file="$1"
  if [ ! -f "$file" ]; then
    echo "0"
    return
  fi

  local seconds
  seconds="$(grep -o 'time="[0-9][0-9.]*"' "$file" | head -n 1 | cut -d '"' -f 2 || true)"
  if [ -z "$seconds" ]; then
    echo "0"
    return
  fi

  awk -v seconds="$seconds" 'BEGIN { printf "%d", seconds * 1000 + 0.5 }'
}

classify_failure_kind() {
  local suite_report="$1"
  local status="$2"
  local tests_run="$3"

  if [ "$tests_run" -eq 0 ]; then
    echo "no_tests"
    return
  fi

  if [ "$status" = "success" ]; then
    echo "success"
    return
  fi

  if grep -Eqs 'AwaitTimeoutException|ConditionTimeoutException|Timeout' "$suite_report" 2>/dev/null; then
    echo "timeout"
  elif grep -Eqs 'AssertionFailedError|AssertionError' "$suite_report" 2>/dev/null; then
    echo "assertion"
  elif grep -Eqs 'TestAbortedException' "$suite_report" 2>/dev/null; then
    echo "aborted"
  else
    echo "error"
  fi
}

remove_suite_reports() {
  local test_class="$1"

  rm -f "$(report_xml "$test_class")"
}

record_suite_result() {
  local iteration="$1"
  local suite="$2"
  local test_class="$3"

  local duration status timestamp
  local suite_report tests_run failures errors skipped failure_kind

  suite_report="$(report_xml "$test_class")"

  duration="$(extract_duration_ms "$suite_report")"
  timestamp="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  tests_run="$(extract_testsuite_attr "$suite_report" tests)"
  failures="$(extract_testsuite_attr "$suite_report" failures)"
  errors="$(extract_testsuite_attr "$suite_report" errors)"
  skipped="$(extract_testsuite_attr "$suite_report" skipped)"

  if [ "$tests_run" -gt 0 ] && [ "$failures" -eq 0 ] && [ "$errors" -eq 0 ]; then
    status="success"
  elif [ "$tests_run" -eq 0 ]; then
    status="no_tests"
  else
    status="fail"
  fi

  failure_kind="$(classify_failure_kind "$suite_report" "$status" "$tests_run")"

  echo "$timestamp,$iteration,$suite,$test_class,$status,$failure_kind,$tests_run,$failures,$errors,$skipped,$duration" >> "$RESULTS_FILE"
  echo "[$iteration/$RUNS] $suite => $status/$failure_kind (${duration}ms, tests=$tests_run)"
}

run_iteration() {
  local iteration="$1"
  local started_at ended_at duration

  remove_suite_reports "NaiveFlakyTest"
  remove_suite_reports "AwaitilityBaselineTest"
  remove_suite_reports "DeterministicAsyncTest"

  started_at="$(now_ms)"
  set +e
  mvn -q -pl demo-order-service -am -Dtest="$EXPERIMENT_TESTS" -Dsurefire.failIfNoSpecifiedTests=false test >/dev/null 2>&1
  set -e
  ended_at="$(now_ms)"
  duration=$((ended_at - started_at))

  echo "[$iteration/$RUNS] Maven batch completed (${duration}ms)"
  record_suite_result "$iteration" "naive" "NaiveFlakyTest"
  record_suite_result "$iteration" "awaitility" "AwaitilityBaselineTest"
  record_suite_result "$iteration" "extension" "DeterministicAsyncTest"
}

run_maven_test() {
  local test_class="$1"

  set +e
  mvn -q -pl demo-order-service -am -Dtest="$test_class" -Dsurefire.failIfNoSpecifiedTests=false test >/dev/null 2>&1
  local exit_code=$?
  set -e

  return "$exit_code"
}

warm_up_environment() {
  echo "Preparing compiled test classes..."
  mvn -pl demo-order-service -am test-compile
  echo "Compiled test classes."

  echo "Running unmeasured warm-up suites..."
  echo "Warm-up: $EXPERIMENT_TESTS"
  run_maven_test "$EXPERIMENT_TESTS" || true
  echo "Warm-up completed."
}

print_summary() {
  echo
  echo "Aggregating experiment results..."
  echo "Compiling experiment summary tool..."
  mvn -q -pl experiment-runner -am -DskipTests package >/dev/null 2>&1
  java -cp "$ROOT_DIR/experiment-runner/target/classes" "$SUMMARY_MAIN" "$RESULTS_FILE"
}

mkdir -p "$ROOT_DIR/artifacts"
warm_up_environment

echo "Starting measured experiment: $RUNS iterations per suite, $((RUNS * 3)) test launches total."
echo "Fast mode: one Maven invocation per iteration, all three suites are executed in the same batch."
echo "run_timestamp,iteration,suite,test_class,status,failure_kind,tests_run,failures,errors,skipped,duration_ms" > "$RESULTS_FILE"

for ((i = 1; i <= RUNS; i++)); do
  run_iteration "$i"
done

echo "Experiment completed. Results saved to $RESULTS_FILE"
print_summary
