# rockchip-ssh-sftp

中文 | [English](README.md)

rockchip-ssh-sftp 是一个运行在 Android 设备本地的轻量 SSH 服务端项目，面向局域网设备维护、调试、文件传输和自动化控制场景。应用提供前台 SSH 服务、深蓝暗黑科技风配置页面、开机自启动支持，并兼容常见桌面 SSH/SFTP/SCP 客户端。

本项目当前主要面向基于 Rockchip 芯片的 Android 开发板进行 SSH 远程访问开发和测试。手机、平板、其他芯片平台以及厂商定制 Android 系统的兼容性可能存在差异。

rockchip-ssh-sftp 采用宽松的 MIT 许可证完全开源，允许个人使用、商业使用、修改、再分发以及集成到其他产品，但须按许可证要求保留版权和许可声明。

## 功能亮点

- 基于 Apache MINA SSHD 的 Android 本地 SSH 服务端。
- 兼容常见 SSH 客户端，例如 OpenSSH、PuTTY、WinSCP、Termius 以及其他 SFTP/SCP 工具。
- 可在应用页面配置用户名、密码和端口。
- 保存配置后自动重启 SSH 服务，使新参数立即生效。
- 支持前台服务和开机自动启动。
- 支持 SSH interactive shell、远程 exec、SCP 和 SFTP。
- SSH 命令行和 SFTP 默认从共享存储（`/storage/emulated/0`）启动，连接后直接执行 `ls` 或打开文件列表即可看到 sdcard 内容。
- Android 11 及以上系统会引导授予“所有文件访问权限”，授权返回后自动重启 SSH 服务。
- SFTP 支持常用 Android 存储路径别名：
  - `/sdcard`
  - `/storage/emulated/0`
  - `/storage/self/primary`
- Host Key 持久化，避免每次重启服务后 SSH 客户端识别为新的服务端。
- 迁移并实现 IoT 模块中的设备命令能力，包括文件、系统信息、应用、运行进程、摄像头、音量、SQLite 等诊断命令。
- 页面和通知栏文字跟随 Android 系统语言：
  - 默认资源语言为英文。
  - 简体中文通过 `values-zh-rCN` 提供。

## 页面和通知栏

应用提供一个简单配置页面，用于：

- 查看服务状态和局域网连接地址。
- 编辑用户名、密码和端口。
- 启动或停止 SSH 服务。
- 保存配置并立即应用。

通知栏文字同样通过 Android 资源国际化，并跟随系统语言显示。

## 快速开始

1. 安装并打开 rockchip-ssh-sftp。
2. 配置 `用户名`、`密码` 和 `端口`。
3. 点击 `保存并应用` 保存配置并自动重启服务。
4. 如果服务未运行，点击 `启动服务`。
5. 在同一局域网电脑上连接：

```bash
ssh -p 2222 android@<device-ip>
```

开发默认参数通常为：

```text
用户名: android
密码: android
端口: 2222
```

实际参数以应用页面保存的配置为准。

## SFTP 和 SCP

SFTP：

```bash
sftp -P 2222 android@<device-ip>
```

SCP 下载：

```bash
scp -P 2222 android@<device-ip>:/sdcard/Download/example.txt ./example.txt
```

SCP 上传：

```bash
scp -P 2222 ./local.txt android@<device-ip>:/sdcard/Download/local.txt
```

部分 OpenSSH 客户端可能需要旧版 SCP 兼容模式：

```bash
scp -O -P 2222 ./local.txt android@<device-ip>:/sdcard/Download/local.txt
```

SFTP 连接后的 `/` 表示设备共享存储根目录，以下路径等价：

```text
/
/sdcard
/storage/emulated/0
```

在 `/` 点击返回上一级仍停留在共享存储根目录，不会进入受保护的 Android 系统根目录。

## 构建

macOS 或 Linux：

```bash
./gradlew :app:assembleDebug
```

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK 会生成带版本号的文件名：

```text
app/build/outputs/apk/debug/rockchip-ssh-sftp-v1.0.0002.apk
```

命名格式为：

```text
rockchip-ssh-sftp-v{versionName}.{versionCode 四位补零}.apk
```

## GitHub 版本发布

推送与 APK 版本一致的标签后，GitHub 会自动发布对应版本：

```bash
git tag v1.0.0002
git push origin v1.0.0002
```

GitHub Actions 会自动执行单元测试、构建可安装 APK、创建 Release，并上传 `rockchip-ssh-sftp-v1.0.0002.apk`。发布新版本前，请先修改 `app/build.gradle.kts` 中的 `versionName` 和 `versionCode`。

## 文档

项目文档采用简单的国际化文件结构：

- 默认英文：
  - [README.md](README.md)
  - [USAGE.md](USAGE.md)
  - [LICENSE.md](LICENSE.md)
  - [DISCLAIMER.md](DISCLAIMER.md)
- 简体中文：
  - [README.zh-CN.md](README.zh-CN.md)
  - [USAGE.zh-CN.md](USAGE.zh-CN.md)
  - [LICENSE.zh-CN.md](LICENSE.zh-CN.md)
  - [DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md)

## 重要说明

- rockchip-ssh-sftp 不是系统 root shell，默认以应用自身权限执行命令。
- 设备未取得 Root 权限时，部分命令和受保护目录会因缺少系统权限而无法执行或访问。这属于 Android 系统安全限制，并非 SSH 账号认证问题。
- 服务行为受 Android 应用沙箱、文件访问规则、SELinux、系统权限和设备 root 状态限制。
- 需要系统级权限的命令可能需要 root 或系统签名权限；缺少权限时应返回明确失败信息。
- SFTP 访问范围仍受 Android 实际文件权限限制。
- Android 11 及以上系统需为 rockchip-ssh-sftp 开启“所有文件访问权限”才能浏览共享存储；`Android/data`、`Android/obb` 和系统 `/data` 仍可能受 Android、SELinux、厂商策略或 root 状态限制。
- 请不要在公网环境直接暴露弱密码 SSH 服务。
- 商业或批量部署前，应先进行安全评估、权限审计和密码策略规划。
- 当前主要测试环境为 Rockchip Android 开发板的 SSH 远程维护场景；部署到目标设备前，请按实际硬件和 Android 系统版本验证所需指令。

## 协议

rockchip-ssh-sftp 是采用 [MIT License](LICENSE.md) 发布的开源软件。个人和组织均可使用、修改、分发、再许可及商业使用，无需另行取得作者授权，但必须保留版权和许可声明。

详细条款见 [LICENSE.zh-CN.md](LICENSE.zh-CN.md)。使用前请同时阅读 [DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md)。
