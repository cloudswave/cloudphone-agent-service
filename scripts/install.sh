#!/system/bin/sh
# ============================================================
# CloudPhone Agent 一键安装运行脚本 (MT管理器终端)
# 用法: sh install.sh   (建议勾选【使用root权限执行】)
# 功能: 检测APK -> 未装则下载+安装 -> 输入设备ID保存配置 -> 启动
# ============================================================

BASE_URL="http://47.100.93.238:5244/cloudphone-agent"
DIR="/sdcard/cloudphone"
PKG="com.cloudphone.agentservice"
APK="$DIR/cloudphone-agent-service-arm64-v8a.apk"
CONFIG="$DIR/agent.config"
START_SH="$DIR/start.sh"

# 默认信令/ICE（与服务器模板一致，下载失败时兜底）
DEF_SIGNALING="ws://47.100.93.238:8081"
DEF_ICE="turn:cloudphone:123456@47.100.93.238:3478?transport=udp"

echo "=============================================="
echo "  CloudPhone Agent 一键安装运行"
echo "=============================================="

# ---------- 1. 检测 root ----------
IS_ROOT=0
if [ "$(id -u)" = "0" ]; then
    IS_ROOT=1
else
    su -c "id -u" 2>/dev/null | grep -q '^0$' && IS_ROOT=1
fi
if [ "$IS_ROOT" = "0" ]; then
    echo "!! 未检测到 root 权限"
    echo "   安装 APK 需要 root，请在 MT管理器 勾选【使用root权限执行】"
    echo "   或先在终端执行 su 后再运行本脚本"
    echo -n "按回车继续尝试（可能安装失败），Ctrl+C 退出: "
    read _dummy
fi

# ---------- 2. 检测下载工具 ----------
CURL=0; WGET=0; BWGET=0
command -v curl >/dev/null 2>&1 && CURL=1
command -v wget >/dev/null 2>&1 && WGET=1
if busybox wget --help >/dev/null 2>&1; then BWGET=1; fi
if [ "$CURL$WGET$BWGET" = "000" ]; then
    echo "!! 未找到 curl / wget / busybox wget"
    echo "   MT管理器终端通常自带 busybox，请确认环境"
    exit 1
fi

download() {
    # $1 = 远程文件名
    _url="$BASE_URL/$1"
    _dest="$DIR/$1"
    if [ "$CURL" = "1" ]; then
        curl -sL -o "$_dest" "$_url" 2>/dev/null
    elif [ "$WGET" = "1" ]; then
        wget -q -O "$_dest" "$_url" 2>/dev/null
    else
        busybox wget -q -O "$_dest" "$_url" 2>/dev/null
    fi
    [ -s "$_dest" ]
}

# ---------- 3. 确保目录 ----------
mkdir -p "$DIR"

# ---------- 4. 检测 APK 是否已安装【修复：兼容\r换行、高版本Android】 ----------
IS_INSTALLED=0
if pm list packages 2>/dev/null | tr -d '\r' | grep -q "package:${PKG}"; then
    IS_INSTALLED=1
fi
# dumpsys 兜底校验，pm list 被限制时也能识别
if [ "$IS_INSTALLED" = "0" ]; then
    dumpsys package "$PKG" >/dev/null 2>&1 && IS_INSTALLED=1
fi

if [ "$IS_INSTALLED" = "1" ]; then
    echo ">> APK 已安装，跳过下载安装"
else
    echo ">> 未检测到 APK，开始下载..."
    echo "   下载中: $APK"
    if ! download "cloudphone-agent-service-arm64-v8a.apk"; then
        echo "!! 下载 APK 失败，请检查网络或服务器"
        exit 1
    fi
    echo "   OK APK 下载完成 ($(ls -l "$APK" 2>/dev/null | awk '{print $5}') bytes)"

    # 确保 start.sh 就位
    if [ ! -f "$START_SH" ]; then
        echo "   下载中: start.sh"
        download "start.sh" || echo "   !! start.sh 下载失败，将用内置命令启动"
    fi

    echo ">> 安装 APK..."
    if [ "$IS_ROOT" = "1" ]; then
        pm install -r "$APK"
    else
        su -c "pm install -r '$APK'"
    fi
    if [ $? -ne 0 ]; then
        echo "!! APK 安装失败"
        echo "   请确认已授予 root 权限后重试"
        exit 1
    fi
    echo "   OK APK 安装成功"
fi

# ---------- 5. 配置文件 ----------
# 本地没有配置时，先尝试从服务器下载模板（含默认信令/ICE）
if [ ! -f "$CONFIG" ]; then
    echo ">> 未找到配置文件，尝试下载模板..."
    if download "agent.config"; then
        echo "   OK 已下载配置模板"
    else
        echo "   !! 模板下载失败，将使用内置默认配置"
    fi
fi

# 读取当前配置（保留信令/ICE）
CUR_ID=""; SIGNALING=""; ICE=""
if [ -f "$CONFIG" ]; then
    CUR_ID=$(grep '^AGENT_ID=' "$CONFIG" | head -1 | cut -d= -f2- | tr -d "'\"")
    SIGNALING=$(grep '^SIGNALING=' "$CONFIG" | head -1 | cut -d= -f2- | tr -d "'\"")
    ICE=$(grep '^ICE_SERVERS=' "$CONFIG" | head -1 | cut -d= -f2- | tr -d "'\"")
fi
[ -z "$SIGNALING" ] && SIGNALING="$DEF_SIGNALING"
[ -z "$ICE" ] && ICE="$DEF_ICE"

# ---------- 6. 输入设备 ID ----------
echo ""
echo "当前设备ID: ${CUR_ID:-（空）}"
echo -n "请输入设备ID [直接回车沿用]: "
read AGENT_ID
if [ -z "$AGENT_ID" ]; then
    AGENT_ID="$CUR_ID"
fi
if [ -z "$AGENT_ID" ]; then
    echo "!! 设备ID不能为空"
    exit 1
fi

# 写入配置
cat > "$CONFIG" <<EOF
# CloudPhone Agent config
AGENT_ID='$AGENT_ID'
ICE_SERVERS='$ICE'
SIGNALING='$SIGNALING'
EOF
echo "   OK 配置已保存: $CONFIG"
echo "      设备ID:    $AGENT_ID"
echo "      信令地址:  $SIGNALING"

# ---------- 7. 启动服务 ----------
echo ""
echo ">> 启动 CloudPhone Agent 服务..."
if [ -f "$START_SH" ]; then
    sh "$START_SH"
else
    am start-foreground-service -n com.cloudphone.agentservice/.AgentService
fi

echo ""
echo "=============================================="
echo "  完成！请查看通知栏确认连接状态"
echo "  若首次安装，请允许本应用的通知权限"
echo "=============================================="
