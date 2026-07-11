# SSH Control UI Design

## Goal

Add one native Android screen to configure the SSH username, password, and port, display the current service state and LAN endpoint, and start or stop the existing boot-persistent SSH service.

## UI

`MainActivity` uses AppCompat and Material Components with an XML layout. It contains a compact top app bar, a service status block, the current LAN address, outlined username/password/port fields, a password visibility control, a `Save and apply` command, and a primary start/stop command. Field validation errors appear on the corresponding input layout. Service startup failures appear as persistent status text rather than only a transient toast.

The launcher uses a restrained dark industrial-terminal style: graphite surfaces, cyan-green operational accents, monospace endpoint and status text, and explicit ONLINE/OFFLINE/STARTING/ERROR labels. It avoids gradients, decorative glow, and marketing composition. The header reads `POCKETSSH / LOCAL NODE`; familiar save, visibility, and power icons accompany commands. Android 13 and later request notification permission when the screen first opens.

## Configuration

`SshConfigRepository` becomes the single configuration source for both UI and service. Values are persisted in device-protected `SharedPreferences`, allowing locked-boot startup. BuildConfig values remain first-run defaults. The stored values are bind address, port, username, password, and enabled. The UI edits username, password, and port; bind address remains `0.0.0.0`.

Validation rejects a blank username, empty password, and ports outside `1..65535`. Invalid fields are not saved and do not restart the service.

## Service Control

The service accepts explicit `START` and `STOP` actions and publishes an in-process state snapshot containing `STARTING`, `RUNNING`, `STOPPED`, or `ERROR`, plus an optional error message and bound port. Repeated commands remain idempotent.

Saving valid settings persists them, sends `STOP`, then sends `START`; the new credentials and port therefore apply immediately. The stop button only stops the current process and does not disable boot startup. `BootReceiver` continues to start the service after boot with the saved configuration.

The activity observes state broadcasts while visible and also reads the latest state when resumed. It renders actual service state rather than assuming a command succeeded. The notification remains required while SSH is running.

## Connection Address

The activity determines the active non-loopback IPv4 address from available network interfaces and displays `<address>:<port>`. If no LAN address is available, it displays `Unavailable` while leaving service control functional.

## Android Integration

`MainActivity` is exported with the MAIN/LAUNCHER intent filter. Existing boot receiver and foreground service declarations remain. No storage permission or legacy storage mode is introduced.

## Testing

Unit tests cover preference persistence and defaults, validation, service command action mapping, state transitions, and LAN-address selection logic. Existing SSH command, manager, configuration, and boot tests remain green. Final verification runs the complete unit suite and builds the debug APK. Device testing remains necessary for notification permission, lifecycle UI updates, and real client reconnection after applying new credentials.

## Constraints

The implementation stays on minSdk 24, targetSdk 35, Kotlin/JVM 11, XML views, AppCompat, and Material Components. The workspace has no Git metadata, so specification and implementation commits cannot be created.
