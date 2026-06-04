#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_NAME="$(basename "$0")"
readonly COVERAGE_THRESHOLD=80
readonly PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[PASS]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_fail()  { echo -e "${RED}[FAIL]${NC}  $*"; }

print_separator() {
  echo "============================================================"
}

check_jdk_version() {
  log_info "检测 JDK 版本..."

  if ! command -v java &>/dev/null; then
    log_fail "未找到 java 命令，请确保 JDK 已安装并在 PATH 中"
    exit 1
  fi

  local java_version_output
  java_version_output="$(java -version 2>&1)"

  local jdk_major
  jdk_major="$(echo "$java_version_output" | head -n1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"

  if [ -z "$jdk_major" ]; then
    jdk_major="$(echo "$java_version_output" | head -n1 | sed -n 's/.*version "1\.\([0-9]*\).*/\1/p')"
  fi

  if [ -z "$jdk_major" ]; then
    log_fail "无法解析 JDK 版本: $java_version_output"
    exit 1
  fi

  if [ "$jdk_major" -lt 8 ]; then
    log_fail "JDK 版本 ${jdk_major} 低于最低要求 8"
    echo "  当前版本: $java_version_output"
    exit 1
  fi

  log_ok "JDK 版本检测通过 (major=${jdk_major})"
  echo "  $(echo "$java_version_output" | head -n1)"
}

check_maven() {
  log_info "检测 Maven 环境..."

  if ! command -v mvn &>/dev/null; then
    log_fail "未找到 mvn 命令，请确保 Maven 已安装并在 PATH 中"
    exit 1
  fi

  local mvn_version
  mvn_version="$(mvn --version 2>&1 | head -n1)"
  log_ok "Maven 检测通过: $mvn_version"
}

print_environment_info() {
  print_separator
  log_info "构建环境信息"
  print_separator
  echo "  操作系统 : $(uname -s) $(uname -r)"
  echo "  架构     : $(uname -m)"
  echo "  工作目录 : ${PROJECT_DIR}"
  echo "  用户     : $(whoami)"
  echo "  时间     : $(date '+%Y-%m-%d %H:%M:%S %Z')"
  java -version 2>&1 | sed 's/^/  /'
  mvn --version 2>&1 | head -n3 | sed 's/^/  /'
  print_separator
}

run_build() {
  log_info "执行 mvn clean verify (含 JaCoCo 覆盖率)..."
  print_separator

  local mvn_exit_code=0
  mvn clean verify \
    --errors \
    --show-version \
    --batch-mode \
    --no-transfer-progress \
    -Djacoco.skip=false \
    2>&1 | tee "${PROJECT_DIR}/target/build-output.log" \
    || mvn_exit_code=$?

  print_separator

  if [ "$mvn_exit_code" -eq 0 ]; then
    log_ok "Maven 构建成功"
  else
    log_fail "Maven 构建失败 (退出码: ${mvn_exit_code})"
    diagnose_build_failure
    collect_error_logs
    exit "$mvn_exit_code"
  fi
}

diagnose_build_failure() {
  print_separator
  log_warn "构建失败诊断 — 定位失败阶段"
  print_separator

  local build_log="${PROJECT_DIR}/target/build-output.log"

  if [ ! -f "$build_log" ]; then
    log_warn "构建日志不存在: ${build_log}"
    return
  fi

  local failed_phases=()

  if grep -qE '\[ERROR\].*compilation error|maven-compiler-plugin.*FAIL|COMPILATION ERROR' "$build_log" 2>/dev/null; then
    failed_phases+=("编译阶段 (compile)")
    log_fail "  ✗ 编译阶段失败"
    echo ""
    echo "  编译错误摘要:"
    grep -E '\[ERROR\].*\.java:\[|cannot find symbol|package .* does not exist|incompatible types' \
      "$build_log" | head -20 | sed 's/^/    /'
  else
    log_ok "  ✓ 编译阶段通过"
  fi

  echo ""

  if grep -qE 'Tests run:.*Failures: [1-9]|Tests run:.*Errors: [1-9]|There are test failures' "$build_log" 2>/dev/null; then
    failed_phases+=("测试阶段 (test)")
    log_fail "  ✗ 测试阶段失败"
    echo ""
    echo "  失败测试摘要:"
    grep -E 'Tests run:|Failed tests:|Tests in error:' \
      "$build_log" | head -20 | sed 's/^/    /'
  else
    log_ok "  ✓ 测试阶段通过"
  fi

  echo ""

  if grep -qE '\[ERROR\].*checkstyle|checkstyle:check.*FAIL|pmd:check.*FAIL|spotbugs.*FAIL|rng:check.*FAIL' "$build_log" 2>/dev/null; then
    failed_phases+=("检查阶段 (verify/check)")
    log_fail "  ✗ 检查阶段失败 (checkstyle/pmd/spotbugs/rat 等)"
    echo ""
    echo "  检查错误摘要:"
    grep -E '\[ERROR\].*(checkstyle|pmd|spotbugs|rng|apache-rat)' \
      "$build_log" | head -20 | sed 's/^/    /'
  else
    log_ok "  ✓ 检查阶段通过"
  fi

  echo ""
  if [ ${#failed_phases[@]} -gt 0 ]; then
    log_warn "失败阶段: ${failed_phases[*]}"
  fi

  print_separator
}

collect_error_logs() {
  print_separator
  log_warn "收集 target/ 下的错误日志摘要"
  print_separator

  local found_logs=0

  local surefire_dir="${PROJECT_DIR}/target/surefire-reports"
  if [ -d "$surefire_dir" ]; then
    local failed_tests
    failed_tests="$(grep -rl '<failure' "$surefire_dir" --include='*.txt' 2>/dev/null || true)"
    if [ -n "$failed_tests" ]; then
      found_logs=1
      log_warn "Surefire 失败报告:"
      echo "$failed_tests" | head -10 | sed 's/^/  /'
      echo ""
      for f in $(echo "$failed_tests" | head -3); do
        echo "  --- $(basename "$f") (前 30 行) ---"
        head -30 "$f" | sed 's/^/  /'
        echo ""
      done
    fi
  fi

  local failsafe_dir="${PROJECT_DIR}/target/failsafe-reports"
  if [ -d "$failsafe_dir" ]; then
    local failed_its
    failed_its="$(grep -rl '<failure' "$failsafe_dir" --include='*.txt' 2>/dev/null || true)"
    if [ -n "$failed_its" ]; then
      found_logs=1
      log_warn "Failsafe 失败报告:"
      echo "$failed_its" | head -10 | sed 's/^/  /'
    fi
  fi

  local build_log="${PROJECT_DIR}/target/build-output.log"
  if [ -f "$build_log" ]; then
    found_logs=1
    log_warn "完整构建日志: ${build_log}"
    echo "  最后 30 行:"
    tail -30 "$build_log" | sed 's/^/    /'
  fi

  if [ "$found_logs" -eq 0 ]; then
    log_warn "未找到详细的错误日志文件"
  fi

  print_separator
}

check_coverage() {
  print_separator
  log_info "检查 JaCoCo 代码覆盖率 (阈值: ${COVERAGE_THRESHOLD}%)"
  print_separator

  local jacoco_csv="${PROJECT_DIR}/target/site/jacoco/jacoco.csv"

  if [ ! -f "$jacoco_csv" ]; then
    jacoco_csv="${PROJECT_DIR}/target/jacoco-ut/jacoco.csv"
  fi

  if [ ! -f "$jacoco_csv" ]; then
    log_fail "未找到 JaCoCo 覆盖率报告 (jacoco.csv)"
    echo "  搜索路径:"
    echo "    - ${PROJECT_DIR}/target/site/jacoco/jacoco.csv"
    echo "    - ${PROJECT_DIR}/target/jacoco-ut/jacoco.csv"
    echo ""
    log_warn "尝试从构建日志中提取覆盖率信息..."
    local build_log="${PROJECT_DIR}/target/build-output.log"
    if [ -f "$build_log" ]; then
      grep -i 'jacoco\|coverage' "$build_log" | tail -10 | sed 's/^/  /' || true
    fi
    log_fail "无法获取覆盖率数据，以失败退出"
    exit 1
  fi

  log_ok "找到 JaCoCo 报告: ${jacoco_csv}"

  local instr_missed instr_covered branch_missed branch_covered
  local line_missed line_covered

  instr_missed=0
  instr_covered=0
  branch_missed=0
  branch_covered=0
  line_missed=0
  line_covered=0

  local is_header=true
  while IFS=',' read -r group pkg cls instr_m instr_c branch_m branch_c line_m line_c \
                         complexity_m complexity_c method_m method_c type; do
    if [ "$is_header" = true ]; then
      is_header=false
      continue
    fi

    instr_missed=$((instr_missed + instr_m))
    instr_covered=$((instr_covered + instr_c))
    branch_missed=$((branch_missed + branch_m))
    branch_covered=$((branch_covered + branch_c))
    line_missed=$((line_missed + line_m))
    line_covered=$((line_covered + line_c))
  done < "$jacoco_csv"

  local instr_total=$((instr_missed + instr_covered))
  local branch_total=$((branch_missed + branch_covered))
  local line_total=$((line_missed + line_covered))

  local instr_pct=0
  local branch_pct=0
  local line_pct=0

  if [ "$instr_total" -gt 0 ]; then
    instr_pct=$((instr_covered * 100 / instr_total))
  fi
  if [ "$branch_total" -gt 0 ]; then
    branch_pct=$((branch_covered * 100 / branch_total))
  fi
  if [ "$line_total" -gt 0 ]; then
    line_pct=$((line_covered * 100 / line_total))
  fi

  echo ""
  echo "  ┌──────────────────────┬──────────┬──────────┬──────────┐"
  echo "  │ 指标                 │ 覆盖     │ 未覆盖   │ 覆盖率   │"
  echo "  ├──────────────────────┼──────────┼──────────┼──────────┤"
  printf "  │ %-20s │ %8d │ %8d │ %6d%%  │\n" "指令 (Instruction)" "$instr_covered" "$instr_missed" "$instr_pct"
  printf "  │ %-20s │ %8d │ %8d │ %6d%%  │\n" "分支 (Branch)" "$branch_covered" "$branch_missed" "$branch_pct"
  printf "  │ %-20s │ %8d │ %8d │ %6d%%  │\n" "行 (Line)" "$line_covered" "$line_missed" "$line_pct"
  echo "  └──────────────────────┴──────────┴──────────┴──────────┘"
  echo ""

  local overall_pct=$instr_pct

  if [ "$overall_pct" -ge "$COVERAGE_THRESHOLD" ]; then
    log_ok "指令覆盖率 ${overall_pct}% ≥ ${COVERAGE_THRESHOLD}% — 通过"
  else
    log_fail "指令覆盖率 ${overall_pct}% < ${COVERAGE_THRESHOLD}% — 未达标"
    log_fail "覆盖率检查失败，退出码非 0"
    exit 1
  fi

  if [ "$branch_pct" -lt "$COVERAGE_THRESHOLD" ]; then
    log_warn "分支覆盖率 ${branch_pct}% < ${COVERAGE_THRESHOLD}% — 建议补充分支测试"
  fi

  if [ "$line_pct" -lt "$COVERAGE_THRESHOLD" ]; then
    log_warn "行覆盖率 ${line_pct}% < ${COVERAGE_THRESHOLD}% — 建议补充行覆盖"
  fi

  local jacoco_index="${PROJECT_DIR}/target/site/jacoco/index.html"
  if [ -f "$jacoco_index" ]; then
    log_info "HTML 覆盖率报告: ${jacoco_index}"
  fi

  print_separator
}

main() {
  echo ""
  print_separator
  echo "  Apache Commons Compress — CI/本地统一构建检查"
  echo "  脚本: ${SCRIPT_NAME} | 阈值: ${COVERAGE_THRESHOLD}%"
  print_separator
  echo ""

  check_jdk_version
  check_maven
  print_environment_info
  run_build
  check_coverage

  echo ""
  print_separator
  log_ok "所有构建检查已通过 ✓"
  print_separator
  echo ""
}

main "$@"
