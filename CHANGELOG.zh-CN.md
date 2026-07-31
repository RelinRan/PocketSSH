# 更新日志

## v1.1.0004

- 增加 root 设备上的 SSH 和 SFTP 目录访问逻辑，支持 `/data` 和 `/storage/emulated/0/Android`。
- `/storage` 和 `/sdcard` 统一映射到 `/storage/emulated/0`，并修正根目录返回逻辑。
- 修复 SFTP 客户端显示内部 shadow 路径的问题，客户端现在显示逻辑路径。
- 改进 Linux 风格 `ls` 的颜色、列对齐、权限、属主、属组、文件大小、时间和符号链接显示。
- 将 Apache MINA SSHD 依赖迁移到 library 模块，增加可下载的 fat AAR 和 fat JAR。
- 更新 GitHub Actions，发布 APK、library 依赖文件、Bundle ZIP 和 SHA-256 校验文件。

## v1.0.0003

- 改进 Android 13 远程 Shell 命令兼容性。
- 增强 GitHub Actions 单元测试失败诊断，包含堆栈、注解和测试报告 artifact。

## v1.0.0002

- 修复 Android 设备不支持 `Path.of` 导致的 SFTP 启动异常。
- 增加 Android 11 及以上“所有文件访问权限”引导，并在授权返回后自动重启 SSH 服务。
- SSH 命令行和 SFTP 默认目录调整为共享存储 `/storage/emulated/0`。
- 增加 Android 兼容的 SSHD/SFTP 诊断日志。
- 在 Android 12 和 Android 13 设备上验证 SSH、SFTP 文件列举。

## v1.0.0001

- 首个开源版本。
