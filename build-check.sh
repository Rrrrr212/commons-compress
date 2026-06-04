#!/bin/bash

set -e

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

print_section() {
    echo ""
    echo "========================================"
    echo "$1"
    echo "========================================"
}

check_jdk_version() {
    print_section "JDK 版本检查"
    
    if ! command -v java &> /dev/null; then
        print_error "未找到 Java 命令"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1,2)
    print_info "当前 Java 版本: $JAVA_VERSION"
    
    JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d'.' -f1)
    
    if [ "$JAVA_MAJOR" -eq 1 ]; then
        JAVA_MAJOR=$(echo "$JAVA_VERSION" | cut -d'.' -f2)
    fi
    
    if [ "$JAVA_MAJOR" -lt 8 ]; then
        print_error "需要 JDK 8 或更高版本，当前版本不满足要求"
        exit 1
    fi
    
    print_info "JDK 版本检查通过"
}

run_maven_build() {
    print_section "Maven 构建"
    
    if ! command -v mvn &> /dev/null; then
        print_error "未找到 Maven 命令"
        exit 1
    fi
    
    print_info "开始执行 mvn clean verify..."
    
    BUILD_LOG=$(mktemp /tmp/maven-build-XXXXXX.log)
    
    set +e
    mvn clean verify jacoco:report -Djacoco.skip=false 2>&1 | tee "$BUILD_LOG"
    MVN_EXIT_CODE=${PIPESTATUS[0]}
    set -e
    
    if [ $MVN_EXIT_CODE -ne 0 ]; then
        print_error "Maven 构建失败，退出码: $MVN_EXIT_CODE"
        analyze_build_failure "$BUILD_LOG"
        cleanup "$BUILD_LOG"
        exit $MVN_EXIT_CODE
    fi
    
    cleanup "$BUILD_LOG"
    print_info "Maven 构建成功"
}

analyze_build_failure() {
    local LOG_FILE=$1
    
    print_section "构建失败分析"
    
    echo "检测到构建失败，正在分析失败阶段..."
    
    if grep -q "BUILD FAILURE" "$LOG_FILE"; then
        if grep -q "COMPILATION ERROR" "$LOG_FILE"; then
            print_error "构建在 编译 阶段失败"
            echo ""
            echo "编译错误摘要："
            grep -A 20 "COMPILATION ERROR" "$LOG_FILE" || true
        elif grep -q "Tests in error" "$LOG_FILE" || grep -q "Tests failed" "$LOG_FILE"; then
            print_error "构建在 测试 阶段失败"
            echo ""
            echo "测试失败摘要："
            grep -A 30 -E "(Tests in error|Tests failed|BUILD FAILURE)" "$LOG_FILE" | head -n 100 || true
        elif grep -q "checkstyle:check" "$LOG_FILE" || grep -q "pmd:check" "$LOG_FILE" || grep -q "spotbugs:check" "$LOG_FILE"; then
            print_error "构建在 代码检查 阶段失败"
            echo ""
            echo "检查错误摘要："
            grep -A 30 -E "(Checkstyle|PMD|SpotBugs|ERROR)" "$LOG_FILE" | tail -n 100 || true
        else
            print_error "构建失败原因未知"
        fi
    fi
    
    echo ""
    echo "查找 target/ 目录下的错误日志..."
    
    if [ -d "target" ]; then
        echo ""
        echo "=== target/surefire-reports 目录内容 ==="
        if [ -d "target/surefire-reports" ]; then
            ls -la target/surefire-reports/ || true
            echo ""
            echo "=== 失败测试摘要 ==="
            grep -r "ERROR" target/surefire-reports/ --include="*.txt" 2>/dev/null | head -n 50 || true
        fi
        
        echo ""
        echo "=== target/failsafe-reports 目录内容 ==="
        if [ -d "target/failsafe-reports" ]; then
            ls -la target/failsafe-reports/ || true
        fi
    fi
}

check_coverage() {
    print_section "代码覆盖率检查"
    
    COVERAGE_REPORT="target/site/jacoco/index.html"
    COVERAGE_CSV="target/site/jacoco/jacoco.csv"
    
    if [ ! -f "$COVERAGE_CSV" ]; then
        print_error "未找到 JaCoCo 覆盖率报告: $COVERAGE_CSV"
        exit 1
    fi
    
    print_info "解析覆盖率数据..."
    
    awk -F',' '
    BEGIN {
        total_instr = 0;
        missed_instr = 0;
    }
    NR > 1 {
        missed_instr += $4;
        total_instr += $4 + $5;
    }
    END {
        if (total_instr > 0) {
            coverage = 100 - (missed_instr / total_instr * 100);
            printf "%.2f\n", coverage;
        } else {
            print "0.00";
        }
    }
    ' "$COVERAGE_CSV" > /tmp/coverage.txt
    
    COVERAGE_PERCENT=$(cat /tmp/coverage.txt)
    
    print_info "当前指令覆盖率: ${COVERAGE_PERCENT}%"
    print_info "目标覆盖率: 80.00%"
    
    if (( $(echo "$COVERAGE_PERCENT < 80.00" | bc -l) )); then
        print_error "代码覆盖率低于 80%，构建失败"
        if [ -f "$COVERAGE_REPORT" ]; then
            print_info "详细覆盖率报告请查看: $COVERAGE_REPORT"
        fi
        rm -f /tmp/coverage.txt
        exit 1
    fi
    
    print_info "代码覆盖率检查通过"
    rm -f /tmp/coverage.txt
}

print_debug_info() {
    print_section "调试信息"
    
    echo "Java 版本详情:"
    java -version 2>&1
    echo ""
    
    echo "Maven 版本:"
    mvn -version 2>&1 | head -n 5
    echo ""
    
    echo "当前目录: $(pwd)"
    echo ""
    
    echo "项目文件:"
    ls -la pom.xml 2>/dev/null || echo "未找到 pom.xml"
    echo ""
}

cleanup() {
    if [ -n "$1" ] && [ -f "$1" ]; then
        rm -f "$1"
    fi
}

main() {
    print_debug_info
    check_jdk_version
    run_maven_build
    check_coverage
    
    print_section "构建检查完成"
    print_info "所有检查均通过！"
}

main "$@"
