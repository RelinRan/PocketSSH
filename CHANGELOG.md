# Changelog

## v1.0.0003

- Improved Android 13 command compatibility for remote shell operations.
- Added GitHub Actions diagnostics for unit test failures, including stack traces, annotations, and uploaded test reports.

## v1.0.0002

- Fixed SFTP startup failure on Android devices caused by the unsupported `Path.of` API.
- Added Android 11+ All files access guidance and automatic SSH service restart after authorization.
- Changed the default SSH shell and SFTP directory to shared storage (`/storage/emulated/0`).
- Made SFTP `/`, `/sdcard`, and `/storage/emulated/0` equivalent and kept parent navigation inside the shared-storage root.
- Added Android-compatible SSHD/SFTP diagnostic logging.
- Verified SSH and SFTP file listing on Android 12 and Android 13 devices.

## v1.0.0001

- Initial open-source release.
