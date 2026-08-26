# CloudPhone Agent Service

无图标 Android 后台服务 APK，通过 root 权限自动拉起 cloudphone-agent 并维持 WebRTC 连接。

## 功能

- 无桌面图标，无 LAUNCHER Activity
- 开机自启 (BOOT_COMPLETED 广播)
- 读取 `/sdcard/cloudphone/agent.config` 配置文件
- 15 秒轮询配置变化，自动重启 agent
- 通过 `su -c` 执行 root 命令，不依赖 Shizuku
- 前台服务通知栏常驻，可点击停止
- 自动监控 agent 进程，崩溃后自动重启

## 快速开始

```bash
# 前提：手机已 ROOT

# 1. 安装 APK
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

# 2. 创建配置文件
adb shell mkdir -p /sdcard/cloudphone
adb push agent.config.example /sdcard/cloudphone/agent.config

# 3. 编辑配置（修改 AGENT_ID 和 SIGNALING）
# 4. 启动服务
adb shell am start-foreground-service -n com.cloudphone.agentservice/.AgentService

# 5. 查看日志
adb shell cat /data/local/tmp/cloudphone-agent.log
```

## 配置文件格式

存放路径: `/sdcard/cloudphone/agent.config`

```bash
AGENT_ID='my-device-001'
SIGNALING='ws://your-server:8080'
ICE_SERVERS='turn:xxx:3478?transport=tcp'
```

## 手动启动

```bash
am start-foreground-service -n com.cloudphone.agentservice/.AgentService
```

## 停止服务

```bash
am force-stop com.cloudphone.agentservice
```

## 项目结构

```
├── app/
│   ├── src/main/
│   │   ├── java/com/cloudphone/agentservice/
│   │   │   ├── AgentService.kt    # 前台服务，核心逻辑
│   │   │   ├── AgentConfig.kt     # 配置解析
│   │   │   ├── BootReceiver.kt    # 开机自启广播
│   │   │   └── RootHelper.kt      # su 命令执行封装
│   │   ├── assets/
│   │   │   ├── cloudphone-agent-arm64   # agent 二进制
│   │   │   └── scrcpy-server.jar        # scrcpy 服务端
│   │   ├── AndroidManifest.xml
│   │   └── res/values/
│   └── build.gradle.kts
├── scripts/
│   └── start.sh               # MT管理器一键启动脚本
├── agent.config.example       # 配置模板
├── 使用说明.txt                # 用户使用说明
├── .github/workflows/
│   └── build-apk.yaml         # CI: 手动触发 + tag 触发
└── README.md
```

## 编译

```bash
./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=1 --no-daemon
```

仅输出 arm64-v8a 单架构 APK。