#!/bin/bash

# 应用名称
APP_NAME="ai-workshop-1.0.0.jar"

# 项目根目录
APP_HOME=~/ai-backend

# JAR文件路径
JAR_PATH=${APP_HOME}/target/${APP_NAME}

# PID文件路径
PID_FILE=${APP_HOME}/app.pid

# 日志文件路径
LOG_FILE=${APP_HOME}/logs/app.log

# JVM参数
JVM_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"

# 检查Java环境
check_java() {
    if [ -z "$JAVA_HOME" ]; then
        JAVA_CMD="java"
    else
        JAVA_CMD="$JAVA_HOME/bin/java"
    fi

    if ! command -v $JAVA_CMD &> /dev/null; then
        echo "Error: Java is not installed or JAVA_HOME is not set"
        exit 1
    fi
}

# 创建日志目录
create_log_dir() {
    if [ ! -d "${APP_HOME}/logs" ]; then
        mkdir -p ${APP_HOME}/logs
    fi
}

# 检查进程是否运行
is_running() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 $PID &> /dev/null; then
            return 0
        fi
    fi
    return 1
}

# 启动应用
start() {
    check_java
    create_log_dir

    if is_running; then
        PID=$(cat "$PID_FILE")
        echo "Application is already running with PID: $PID"
        return 1
    fi

    echo "Starting application..."

    cd ${APP_HOME}

    nohup $JAVA_CMD -jar $JVM_OPTS $JAR_PATH > $LOG_FILE 2>&1 &

    NEW_PID=$!
    echo $NEW_PID > $PID_FILE

    sleep 2

    if is_running; then
        echo "Application started successfully with PID: $NEW_PID"
        echo "Log file: $LOG_FILE"
    else
        echo "Application failed to start. Check log file: $LOG_FILE"
        rm -f $PID_FILE
        return 1
    fi
}

# 停止应用
stop() {
    if ! is_running; then
        echo "Application is not running"
        rm -f $PID_FILE
        return 1
    fi

    PID=$(cat "$PID_FILE")
    echo "Stopping application with PID: $PID..."

    # 尝试正常停止
    kill $PID &> /dev/null

    # 等待最多30秒
    for i in {1..30}; do
        if ! kill -0 $PID &> /dev/null; then
            echo "Application stopped successfully"
            rm -f $PID_FILE
            return 0
        fi
        sleep 1
    done

    # 如果还在运行，强制杀死
    echo "Force killing application..."
    kill -9 $PID &> /dev/null

    sleep 1

    if kill -0 $PID &> /dev/null; then
        echo "Failed to stop application"
        return 1
    else
        echo "Application stopped successfully (force)"
        rm -f $PID_FILE
    fi
}

# 查看状态
status() {
    if is_running; then
        PID=$(cat "$PID_FILE")
        echo "Application is running with PID: $PID"
    else
        echo "Application is not running"
    fi
}

# 查看日志
logs() {
    if [ -f "$LOG_FILE" ]; then
        tail -f $LOG_FILE
    else
        echo "Log file not found: $LOG_FILE"
    fi
}

# 重启应用
restart() {
    echo "Restarting application..."
    stop
    sleep 2
    start
}

# 显示帮助
show_help() {
    echo "Usage: sh script.sh {start|stop|restart|status|logs}"
    echo ""
    echo "  start   - Start the application"
    echo "  stop    - Stop the application"
    echo "  restart - Restart the application"
    echo "  status  - Check application status"
    echo "  logs    - View application logs"
}

# 主逻辑
case "$1" in
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    status)
        status
        ;;
    logs)
        logs
        ;;
    *)
        show_help
        exit 1
        ;;
esac

exit 0