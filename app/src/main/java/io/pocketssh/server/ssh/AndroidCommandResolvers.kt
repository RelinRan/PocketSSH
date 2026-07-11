package io.pocketssh.server.ssh

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Range
import android.util.Size
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AndroidCommandResolvers(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private var lastStartedRemoteApp: AndroidInteractiveShellFactory.RunningAppInfo? = null
    private var lastStartedRemoteAppAt: Long = 0L

    val appInfoResolver: (String) -> AndroidInteractiveShellFactory.AppInfo? = { packageName ->
        runCatching {
            packageManager.getPackageInfoCompat(packageName).toRemoteAppInfo(packageManager)
        }.getOrNull()
    }

    val appListResolver: () -> List<AndroidInteractiveShellFactory.AppInfo> = {
        runCatching {
            packageManager.getInstalledPackagesCompat()
                .map { packageInfo -> packageInfo.toRemoteAppInfo(packageManager) }
        }.getOrDefault(emptyList())
    }

    val appLaunchActivityResolver: (String) -> String? = { packageName ->
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)
                ?.component
                ?.flattenToShortString()
        }.getOrNull()
    }

    val appStartResolver: (String, String?) -> AndroidInteractiveShellFactory.AppStartResult? = { packageName, activity ->
        startRemoteApp(packageName, activity)
    }

    val runningAppResolver: () -> List<AndroidInteractiveShellFactory.RunningAppInfo> = {
        resolveRunningApps()
    }

    val cameraResolver: () -> List<AndroidInteractiveShellFactory.CameraInfo> = {
        resolveCameraInfo()
    }

    val volumeResolver: () -> List<AndroidInteractiveShellFactory.VolumeInfo> = {
        resolveVolumeInfo()
    }

    private fun startRemoteApp(
        packageName: String,
        activity: String?,
    ): AndroidInteractiveShellFactory.AppStartResult {
        return runCatching {
            val intent = if (activity.isNullOrBlank()) {
                packageManager.getLaunchIntentForPackage(packageName)
                    ?: return AndroidInteractiveShellFactory.AppStartResult(false, "launcher activity not found")
            } else {
                val componentSpec = activityComponentSpec(packageName, activity)
                    ?: return AndroidInteractiveShellFactory.AppStartResult(false, "invalid activity: $activity")
                val component = ComponentName.unflattenFromString(componentSpec)
                    ?: return AndroidInteractiveShellFactory.AppStartResult(false, "invalid activity: $activity")
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(component)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            val component = intent.component?.flattenToShortString() ?: packageName
            lastStartedRemoteApp = AndroidInteractiveShellFactory.RunningAppInfo(
                packageName = packageName,
                processName = packageName,
                state = "FOREGROUND",
                source = "last-started",
            )
            lastStartedRemoteAppAt = System.currentTimeMillis()
            AndroidInteractiveShellFactory.AppStartResult(true, component)
        }.getOrElse { error ->
            AndroidInteractiveShellFactory.AppStartResult(false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun resolveRunningApps(): List<AndroidInteractiveShellFactory.RunningAppInfo> {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val rows = mutableListOf<AndroidInteractiveShellFactory.RunningAppInfo>()
        runCatching {
            activityManager.runningAppProcesses.orEmpty().forEach { process ->
                process.pkgList?.filter { it.isNotBlank() }.orEmpty().forEach { packageName ->
                    rows += AndroidInteractiveShellFactory.RunningAppInfo(
                        packageName = packageName,
                        pid = process.pid.takeIf { it > 0 }?.toString().orEmpty(),
                        processName = process.processName ?: packageName,
                        state = process.importance.toRunningState(),
                        source = "activity-manager",
                    )
                }
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            activityManager.getRunningServices(Int.MAX_VALUE).orEmpty().forEach { service ->
                val component = service.service ?: return@forEach
                rows += AndroidInteractiveShellFactory.RunningAppInfo(
                    packageName = component.packageName,
                    pid = service.pid.takeIf { it > 0 }?.toString().orEmpty(),
                    processName = service.process ?: component.packageName,
                    state = "SERVICE",
                    source = "activity-service",
                )
            }
        }
        val lastStarted = lastStartedRemoteApp
        if (lastStarted != null && System.currentTimeMillis() - lastStartedRemoteAppAt <= LAST_STARTED_APP_VISIBLE_MILLIS) {
            rows += enrichLastStartedRemoteApp(activityManager, lastStarted)
        }
        return rows.distinctBy { "${it.packageName}:${it.pid}:${it.processName}:${it.source}" }
    }

    private fun enrichLastStartedRemoteApp(
        activityManager: ActivityManager,
        app: AndroidInteractiveShellFactory.RunningAppInfo,
    ): AndroidInteractiveShellFactory.RunningAppInfo {
        val process = activityManager.runningAppProcesses.orEmpty().firstOrNull { process ->
            process.processName == app.packageName || process.pkgList?.contains(app.packageName) == true
        } ?: return app
        return app.copy(
            pid = process.pid.takeIf { it > 0 }?.toString().orEmpty(),
            processName = process.processName ?: app.processName,
            state = process.importance.toRunningState(),
        )
    }

    private fun resolveCameraInfo(): List<AndroidInteractiveShellFactory.CameraInfo> {
        val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        return runCatching {
            manager.cameraIdList.map { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                AndroidInteractiveShellFactory.CameraInfo(
                    id = id,
                    facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                        CameraCharacteristics.LENS_FACING_FRONT -> "front"
                        CameraCharacteristics.LENS_FACING_BACK -> "back"
                        CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                        else -> "-"
                    },
                    orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)?.toString() ?: "-",
                    hardwareLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)?.let(::cameraHardwareLevelName) ?: "-",
                    flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)?.toString() ?: "-",
                    autofocus = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.joinToString(",") { mode -> cameraAfModeName(mode) } ?: "-",
                    fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString(",") { range -> formatRange(range) } ?: "-",
                    photoSizes = streamMap.formatOutputSizes(ImageFormat.JPEG),
                    videoSizes = streamMap.classOutputSizes(MediaRecorder::class.java),
                    capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.joinToString(",") { capability -> cameraCapabilityName(capability) } ?: "-",
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun resolveVolumeInfo(): List<AndroidInteractiveShellFactory.VolumeInfo> {
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return emptyList()
        val streams = listOf(
            "voice-call" to AudioManager.STREAM_VOICE_CALL,
            "system" to AudioManager.STREAM_SYSTEM,
            "ring" to AudioManager.STREAM_RING,
            "music" to AudioManager.STREAM_MUSIC,
            "alarm" to AudioManager.STREAM_ALARM,
            "notification" to AudioManager.STREAM_NOTIFICATION,
        )
        return streams.map { (name, stream) ->
            AndroidInteractiveShellFactory.VolumeInfo(
                stream = name,
                min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audioManager.getStreamMinVolume(stream).toString() else "0",
                current = audioManager.getStreamVolume(stream).toString(),
                max = audioManager.getStreamMaxVolume(stream).toString(),
                muted = runCatching { audioManager.isStreamMute(stream).toString() }.getOrDefault("-"),
            )
        }
    }

    private fun PackageManager.getPackageInfoCompat(packageName: String): PackageInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getPackageInfo(packageName, 0)
        }
    }

    private fun PackageManager.getInstalledPackagesCompat(): List<PackageInfo> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            getInstalledPackages(0)
        }
    }

    private fun PackageInfo.toRemoteAppInfo(packageManager: PackageManager): AndroidInteractiveShellFactory.AppInfo {
        val appInfo = applicationInfo
        return AndroidInteractiveShellFactory.AppInfo(
            packageName = packageName,
            appName = appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: "-",
            versionName = versionName ?: "-",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode.toString() else {
                @Suppress("DEPRECATION")
                versionCode.toString()
            },
            apkPath = appInfo?.sourceDir ?: "-",
            firstInstallTime = formatAppTime(firstInstallTime),
            lastUpdateTime = formatAppTime(lastUpdateTime),
        )
    }

    private fun StreamConfigurationMap?.formatOutputSizes(format: Int): String {
        return this?.getOutputSizes(format)
            ?.sortedByDescending { size -> size.width.toLong() * size.height }
            ?.take(6)
            ?.joinToString(",") { size -> formatSize(size) }
            ?.ifBlank { "-" }
            ?: "-"
    }

    private fun StreamConfigurationMap?.classOutputSizes(clazz: Class<*>): String {
        return runCatching {
            this?.getOutputSizes(clazz)
                ?.sortedByDescending { size -> size.width.toLong() * size.height }
                ?.take(6)
                ?.joinToString(",") { size -> formatSize(size) }
                ?.ifBlank { "-" }
                ?: "-"
        }.getOrDefault("-")
    }

    private fun Int.toRunningState(): String {
        return when {
            this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "FOREGROUND"
            this <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "SERVICE"
            else -> "BACKGROUND"
        }
    }

    companion object {
        private const val LAST_STARTED_APP_VISIBLE_MILLIS = 10 * 60 * 1000L

        fun activityComponentSpec(packageName: String, activity: String?): String? {
            val value = activity?.trim().orEmpty()
            if (value.isBlank()) return null
            return when {
                "/" in value && !value.startsWith("/") -> value
                value.startsWith("/.") -> "$packageName/${value.removePrefix("/")}"
                value.startsWith(".") -> "$packageName/$value"
                "." in value -> "$packageName/$value"
                else -> "$packageName/$packageName.$value"
            }
        }

        private fun formatSize(size: Size): String = "${size.width}x${size.height}"

        private fun formatRange(range: Range<Int>): String = "${range.lower}-${range.upper}"

        private fun cameraAfModeName(mode: Int): String {
            return when (mode) {
                CameraCharacteristics.CONTROL_AF_MODE_OFF -> "off"
                CameraCharacteristics.CONTROL_AF_MODE_AUTO -> "auto"
                CameraCharacteristics.CONTROL_AF_MODE_MACRO -> "macro"
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> "continuous-video"
                CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> "continuous-picture"
                CameraCharacteristics.CONTROL_AF_MODE_EDOF -> "edof"
                else -> mode.toString()
            }
        }

        private fun cameraCapabilityName(capability: Int): String {
            return when (capability) {
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "backward-compatible"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "manual-sensor"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "manual-post-processing"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "raw"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "private-reprocessing"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "read-sensor-settings"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "burst"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "yuv-reprocessing"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "depth"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "high-speed-video"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "motion-tracking"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "logical-multi-camera"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "monochrome"
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "secure-image-data"
                else -> capability.toString()
            }
        }

        private fun cameraHardwareLevelName(level: Int): String {
            return when (level) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "legacy"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "level-3"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "external"
                else -> level.toString()
            }
        }

        private fun formatAppTime(timeMillis: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMillis))
        }
    }
}
