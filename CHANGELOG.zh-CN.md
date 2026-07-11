# 更新日志

## v1.0.0002

- 修复 Android 设备因不支持 `Path.of` 导致 SFTP 启动后断开的问题。
- 增加 Android 11 及以上“所有文件访问权限”引导，授权返回后自动重启 SSH 服务。
- SSH 命令行和 SFTP 默认目录调整为共享存储（`/storage/emulated/0`）。
- SFTP `/`、`/sdcard` 和 `/storage/emulated/0` 显示相同内容，并将上级导航限制在共享存储根目录。
- 增加 Android 兼容的 SSHD/SFTP 诊断日志。
- 已在 Android 12 和 Android 13 设备验证 SSH 与 SFTP 文件列表。

## v1.0.0001

- 首个开源版本。
