#!/system/bin/sh
# CloudPhone Agent 启动脚本
# 使用方式：MT管理器终端执行 sh start.sh
# 前提：/sdcard/cloudphone/agent.config 已配置好

CONFIG_FILE="/sdcard/cloudphone/agent.config"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "!! 配置文件不存在: $CONFIG_FILE"
    echo "请先创建配置文件再运行"
    exit 1
fi

echo "== 启动 CloudPhone Agent 服务 =="
am start-foreground-service -n com.cloudphone.agentservice/.AgentService

if [ $? -eq 0 ]; then
    echo "OK 启动命令已发送"
    echo "查看通知栏确认连接状态"
else
    echo "!! 启动失败，请确认 APK 已安装"
    exit 1
fi