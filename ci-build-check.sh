#!/usr/bin/env bash
set -uo pipefail

PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MVN_BIN="${MVN_BIN:-mvn}"
COVERAGE_THRESHOLD="${COVERAGE_THRESHOLD:-80}"
TMP_LOG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ci-build-check.XXXXXX")"
BUILD_LOG="${TMP_LOG_DIR}/maven-build.log"
REPORT_LOG="${TMP_LOG_DIR}/jacoco-report.log"
TARGET_LOG_DIR="${PROJECT_DIR}/target/ci-build-check"
COVERAGE_XML="${PROJECT_DIR}/target/site/jacoco/jacoco.xml"
COVERAGE_HTML="${PROJECT_DIR}/target/site/jacoco/index.html"

print_section() {
  printf '\n[%s] %s\n' "$1" "$2"
}

print_kv() {
  printf '  %-18s %s\n' "$1" "$2"
}

fail() {
  printf '\n[失败] %s\n' "$1" >&2
  exit "${2:-1}"
}

persist_logs() {
  mkdir -p "$TARGET_LOG_DIR" 2>/dev/null || true
  [[ -f "$BUILD_LOG" ]] && cp -f "$BUILD_LOG" "$TARGET_LOG_DIR/maven-build.log" 2>/dev/null || true
  [[ -f "$REPORT_LOG" ]] && cp -f "$REPORT_LOG" "$TARGET_LOG_DIR/jacoco-report.log" 2>/dev/null || true
}

cleanup() {
  persist_logs
  rm -rf "$TMP_LOG_DIR"
}

trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "缺少命令: $1" 127
}

detect_java_major() {
  local version_line
  local version_token
  local major

  version_line="$(java -version 2>&1 | head -n 1)"
  version_token="$(printf '%s\n' "$version_line" | awk -F '"' '/version/ {print $2}')"

  if [[ -z "$version_token" ]]; then
    printf '0'
    return
  fi

  if [[ "$version_token" == 1.* ]]; then
    major="${version_token#1.}"
    major="${major%%.*}"
  else
    major="${version_token%%.*}"
  fi

  printf '%s' "$major"
}

detect_failure_stage() {
  local log_file="$1"

  if grep -Eq 'COMPILATION ERROR|Failed to execute goal .*:(compile|testCompile)' "$log_file"; then
    printf '编译'
  elif grep -Eq 'There are test failures|Failed tests:|<<< FAILURE!|SurefireBooterForkException|Failed to execute goal .*:(test|integration-test)' "$log_file"; then
    printf '测试'
  elif grep -Eqi 'checkstyle|spotbugs|pmd|japicmp|jacoco|animal-sniffer|enforcer|apache-rat|forbiddenapis' "$log_file"; then
    printf '检查'
  else
    printf '未知'
  fi
}

print_log_excerpt() {
  local log_file="$1"
  local first_line
  local start_line
  local end_line

  first_line="$(grep -n -m 1 -E 'COMPILATION ERROR|There are test failures|<<< FAILURE!|\[ERROR\]|BUILD FAILURE|Failed to execute goal|Caused by:' "$log_file" | cut -d ':' -f 1 || true)"

  if [[ -z "$first_line" ]]; then
    return
  fi

  start_line=$(( first_line > 20 ? first_line - 20 : 1 ))
  end_line=$(( first_line + 60 ))

  print_section "摘要" "Maven 失败片段"
  sed -n "${start_line},${end_line}p" "$log_file" || true
}

summarize_target_errors() {
  local file
  local matched=0

  print_section "摘要" "target/ 错误日志"

  if [[ ! -d "${PROJECT_DIR}/target" ]]; then
    printf '  target/ 目录不存在，暂无可汇总日志。\n'
    return
  fi

  while IFS= read -r file; do
    if grep -Eiq 'fail|error|exception|violation|<<< FAILURE!|Caused by:|Tests run: .*Failures: [1-9]|Tests run: .*Errors: [1-9]' "$file"; then
      matched=1
      printf '\n--- %s ---\n' "${file#${PROJECT_DIR}/}"
      grep -Ein 'fail|error|exception|violation|<<< FAILURE!|Caused by:|Tests run: .*Failures: [1-9]|Tests run: .*Errors: [1-9]' "$file" | head -n 40 || true
    fi
  done < <(
    find "${PROJECT_DIR}/target" -type f \( \
      -path '*/surefire-reports/*' -o \
      -path '*/failsafe-reports/*' -o \
      -name '*.log' -o \
      -name '*.txt' -o \
      -name '*.xml' \
    \) | sort | head -n 30
  )

  if [[ "$matched" -eq 0 ]]; then
    printf '  未捕获到明确错误关键字，列出最近的 target/ 报告文件：\n'
    find "${PROJECT_DIR}/target" -type f | sort | tail -n 20 || true
  fi
}

compute_line_coverage() {
  local xml_file="$1"

  awk '
    /<counter type="LINE"/ {
      if (match($0, /missed="([0-9]+)"/, missedMatch) && match($0, /covered="([0-9]+)"/, coveredMatch)) {
        missed = missedMatch[1]
        covered = coveredMatch[1]
      }
    }
    END {
      total = missed + covered
      if (total == 0) {
        printf "0.00"
        exit 0
      }
      printf "%.2f", covered * 100 / total
    }
  ' "$xml_file"
}

cd "$PROJECT_DIR"

require_command java
require_command javac
require_command "$MVN_BIN"

JAVA_MAJOR="$(detect_java_major)"
if [[ "$JAVA_MAJOR" -lt 8 ]]; then
  fail "检测到 JDK ${JAVA_MAJOR}，需要 JDK 8 或更高版本。" 2
fi

print_section "阶段" "环境检查"
print_kv "项目目录" "$PROJECT_DIR"
print_kv "操作系统" "$(uname -s) $(uname -m)"
print_kv "JAVA_HOME" "${JAVA_HOME:-未设置}"
print_kv "JDK 主版本" "$JAVA_MAJOR"
print_kv "java -version" "$(java -version 2>&1 | head -n 1)"
print_kv "javac -version" "$(javac -version 2>&1)"
print_kv "Maven 命令" "$MVN_BIN"
print_kv "Maven 版本" "$("$MVN_BIN" -version 2>&1 | sed -n '1,3p' | tr '\n' ' ' | sed 's/[[:space:]]\+/ /g')"
print_kv "覆盖率阈值" "${COVERAGE_THRESHOLD}%"

print_section "阶段" "执行 mvn clean verify"
"$MVN_BIN" --errors --show-version --batch-mode --no-transfer-progress clean verify 2>&1 | tee "$BUILD_LOG"
BUILD_EXIT=${PIPESTATUS[0]}
persist_logs

if [[ "$BUILD_EXIT" -ne 0 ]]; then
  print_section "定位" "构建失败"
  print_kv "失败阶段" "$(detect_failure_stage "$BUILD_LOG")"
  print_kv "退出码" "$BUILD_EXIT"
  print_kv "构建日志" "$TARGET_LOG_DIR/maven-build.log"
  print_log_excerpt "$BUILD_LOG"
  summarize_target_errors
  exit "$BUILD_EXIT"
fi

print_section "阶段" "生成 JaCoCo 覆盖率报告"
if [[ -f "$COVERAGE_XML" ]]; then
  print_kv "覆盖率报告" "$COVERAGE_XML"
else
  "$MVN_BIN" --errors --batch-mode --no-transfer-progress org.jacoco:jacoco-maven-plugin:report 2>&1 | tee "$REPORT_LOG"
  REPORT_EXIT=${PIPESTATUS[0]}
  persist_logs

  if [[ "$REPORT_EXIT" -ne 0 ]]; then
    print_section "定位" "覆盖率报告生成失败"
    print_kv "失败阶段" "检查"
    print_kv "退出码" "$REPORT_EXIT"
    print_kv "报告日志" "$TARGET_LOG_DIR/jacoco-report.log"
    print_log_excerpt "$REPORT_LOG"
    summarize_target_errors
    exit "$REPORT_EXIT"
  fi
fi

[[ -f "$COVERAGE_XML" ]] || fail "未找到 JaCoCo XML 报告: $COVERAGE_XML" 3

LINE_COVERAGE="$(compute_line_coverage "$COVERAGE_XML")"

print_section "阶段" "覆盖率阈值校验"
print_kv "LINE 覆盖率" "${LINE_COVERAGE}%"
print_kv "阈值" "${COVERAGE_THRESHOLD}%"
print_kv "XML 报告" "$COVERAGE_XML"
print_kv "HTML 报告" "$COVERAGE_HTML"
print_kv "构建日志" "$TARGET_LOG_DIR/maven-build.log"
[[ -f "$TARGET_LOG_DIR/jacoco-report.log" ]] && print_kv "报告日志" "$TARGET_LOG_DIR/jacoco-report.log"

if ! awk -v actual="$LINE_COVERAGE" -v threshold="$COVERAGE_THRESHOLD" 'BEGIN { exit((actual + 0) >= (threshold + 0) ? 0 : 1) }'; then
  fail "覆盖率不足：当前 ${LINE_COVERAGE}%，阈值 ${COVERAGE_THRESHOLD}%。" 4
fi

print_section "结果" "构建、测试、检查与覆盖率阈值校验全部通过"
