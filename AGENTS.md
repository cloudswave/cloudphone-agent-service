# AGENTS.md — 智能体开发指南

## 项目概述

这是一个 Android 后台服务 APK 项目，无界面图标，通过 root 权限在 Android 设备上运行 cloudphone-agent（WebRTC 远程控制 Agent）。

## 核心技术栈

- **语言**: Kotlin
- **构建**: Gradle 8.11.1 + AGP 8.7.3
- **最低 SDK**: 24 (Android 7.0)
- **目标 SDK**: 35
- **架构**: 仅 arm64-v8a
- **权限**: su(root)、FOREGROUND_SERVICE、BOOT_COMPLETED、INTERNET、WAKE_LOCK

## 关键架构决策

### 无图标设计
- Manifest 中**没有** `MAIN/LAUNCHER` intent-filter
- 启动方式：`am start-foreground-service -n com.cloudphone.agentservice/.AgentService`
- 开机自启：`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` 广播 → `BootReceiver` → `AgentService`

### Root 权限
- 不依赖 Shizuku，直接 `Runtime.getRuntime().exec(arrayOf("su", "-c", command))`
- `RootHelper.kt` 封装所有 su 操作：部署文件、启动 agent、进程监控
- 判断 root 可用：`RootHelper.hasRoot()` → `id` 命令返回 0

### 配置管理
- 外部配置文件 `/sdcard/cloudphone/agent.config`（shell 格式）
- 15 秒轮询检测变化，自动重启 agent
- 配置缺失时等待，不崩溃

### Agent 生命周期
1. BootReceiver 触发 → AgentService 启动
2. 检查 root → 检查配置 → 从 assets 释放二进制
3. 部署到 `/data/local/tmp/cloudphone-agent` + `ss.jar`
4. 通过 `su -c nohup ... &` 启动
5. 10 秒轮询进程存活，崩溃自动重启

## 关键文件

| 文件 | 职责 |
|------|------|
| `AgentService.kt` | 前台服务，生命周期管理，监控循环，配置轮询 |
| `RootHelper.kt` | su 命令封装，agent 部署/启动/停止 |
| `AgentConfig.kt` | 解析 shell 格式配置文件 |
| `BootReceiver.kt` | 开机广播接收器 |
| `android-app/app/src/main/assets/` | 内置 cloudphone-agent-arm64 + scrcpy-server.jar |

## 构建

```bash
# 本地构建
./gradlew assembleRelease --no-daemon

# 指定版本
./gradlew assembleRelease -PversionName=1.0.1 -PversionCode=2 --no-daemon
```

APK 输出：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`

## CI/CD

`.github/workflows/build-apk.yaml`:
- **workflow_dispatch**: 手动触发，可指定 versionName/versionCode
- **tag push**: 打 `v*` tag 自动触发并上传 APK 到 Release
- 仅构建 arm64-v8a，debug 签名（CI 自生成 keystore）

## 数据流

```
/sdcard/cloudphone/agent.config
  → AgentService 读取解析
  → assets 中 cloudphone-agent-arm64 释放到 /data/local/tmp/
  → su -c /data/local/tmp/cloudphone-agent -id X -signaling Y -jar Z
  → 10s 轮询进程存活，崩溃自动重启
  → 15s 轮询配置变化，变化后 kill + 重启
```

## 注意事项

- assets 中的 cloudphone-agent-arm64 约 9.4MB，scrcpy-server.jar 约 92KB
- 编译时大文件会经过压缩，不影响 APK 体积
- 签名用 debug keystore（CI 中自生成），覆盖安装只需 adb install -r
- 国产 ROM 需在"自启动管理"中允许本应用，否则开机广播可能被拦截