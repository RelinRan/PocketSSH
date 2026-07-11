# PocketSSH 使用说明

中文 | [English](USAGE.md)

本文档说明 PocketSSH 的安装、配置、连接和常用命令使用方式。

## 1. 页面配置

打开应用后，可在主页面配置以下参数：

- `Username`：SSH 登录用户名。
- `Password`：SSH 登录密码。
- `Port`：SSH 服务端口，范围为 `1-65535`。

点击 `SAVE AND APPLY` 后，应用会保存配置并自动重启 SSH 服务，使新账号、密码和端口立即生效。

点击 `START SERVICE` 可启动 SSH 服务；服务运行后按钮会变为 `STOP SERVICE`。

## 2. SSH 连接

确认手机或 Android 设备与电脑在同一局域网内，然后查看页面显示的地址，例如：

```text
192.168.15.109:2222
```

使用常见 SSH 客户端连接：

```bash
ssh -p 2222 android@192.168.15.109
```

其中：

- `2222` 替换为页面配置的端口。
- `android` 替换为页面配置的用户名。
- `192.168.15.109` 替换为页面显示的设备 IP。

## 3. 远程执行命令

可以直接通过 SSH 执行单条命令：

```bash
ssh -p 2222 android@192.168.15.109 "id"
```

也可以进入交互 shell 后使用内置命令：

```bash
ssh -p 2222 android@192.168.15.109
help
```

交互命令行默认目录为 `/storage/emulated/0`，因此 `pwd` 会显示共享存储路径，无参数 `ls` 会直接列出 sdcard 文件。

常用命令示例：

```text
help
pwd
ls /sdcard
cat /proc/meminfo
apps
running-apps
cameras
volume
sqlite <db-path> <sql>
```

实际可用命令以 `help` 输出为准。

## 4. SFTP 使用

使用系统自带或第三方 SFTP 客户端连接：

```bash
sftp -P 2222 android@192.168.15.109
```

连接后可访问常用 Android 存储路径：

```text
/sdcard
/storage/emulated/0
/storage/self/primary
```

SFTP 的 `/` 默认表示共享存储根目录。`/`、`/sdcard` 和 `/storage/emulated/0` 显示相同内容；从 `/` 返回上一级仍停留在共享存储根目录。

示例：

```sftp
ls /sdcard
get /sdcard/Download/example.txt
put local.txt /sdcard/Download/local.txt
```

## 5. SCP 使用

从设备复制文件到电脑：

```bash
scp -P 2222 android@192.168.15.109:/sdcard/Download/example.txt ./example.txt
```

从电脑复制文件到设备：

```bash
scp -P 2222 ./local.txt android@192.168.15.109:/sdcard/Download/local.txt
```

部分 OpenSSH 客户端在兼容模式下可使用：

```bash
scp -O -P 2222 ./local.txt android@192.168.15.109:/sdcard/Download/local.txt
```

## 6. 开机启动

应用支持接收开机广播并自动启动 SSH 服务。开机后是否能立即访问，取决于设备系统策略、前台服务限制、电池优化策略和用户是否已完成首次配置。

## 7. 权限和限制

- PocketSSH 不是系统 root shell，默认以应用自身权限运行。
- 设备未取得 Root 权限时，需要系统特权的命令可能返回 `Permission denied`、只能获取部分信息或无法执行。系统 `/data` 等受保护目录也只有在 Android 系统、SELinux 策略和设备权限允许时才能访问。
- Android 设备的文件访问、应用列表、进程信息和系统命令受系统版本与权限限制。
- 涉及网络配置、系统时间、安装卸载、重启等高权限操作时，设备可能需要 root 或系统级授权。
- 无权限时命令应返回明确错误，不应导致 SSH 服务崩溃。
- Android 11 及以上系统中，PSSH 打开系统设置页时请开启“所有文件访问权限”，返回应用后服务会自动重启。
- 即使已授予共享存储权限，Android 仍可能单独限制 `Android/data`、`Android/obb` 和系统 `/data`。

## 8. 测试设备范围

PocketSSH 当前主要在基于 Rockchip 芯片的 Android 开发板上进行 SSH 远程维护和文件操作测试。不同芯片平台、Android 版本、SELinux 策略和厂商 ROM 的行为可能不同，正式部署前请在实际目标设备上逐项验证所需指令。

## 9. 安全建议

- 修改默认账号和密码。
- 不要在公网暴露 SSH 端口。
- 尽量只在可信局域网内使用。
- 使用后及时停止服务。
- 生产或批量部署时请统一管理密码和端口策略，并完成安全评估。
