# Background Operation

TickMode uses an Android foreground service to maintain an active interval session.

Android and device manufacturers apply power-management restrictions to background applications. These restrictions differ between manufacturers and firmware versions.

If signals stop after locking the screen, check the settings below.

## General Android settings

First check:

- TickMode is allowed to use notifications.
- Allow background usage is enabled, if the device provides this option.
- TickMode is not restricted by Android battery optimization.
- The device is not applying an active battery-saving restriction to TickMode.

These settings may not be sufficient on every device.

## Infinix / XOS

An additional restriction was identified during testing on:

- Device: Infinix X6853
- Android: 15
- XOS: 15.1.2.145SP10

On this device, Android's normal battery optimization exemption and the Allow Background Usage setting were already enabled. TickMode still stopped signalling when the display was turned off.

The setting that resolved the issue was:

1. Open Settings.
2. Open Battery and power saving.
3. Open App power consumption rankings.
4. Select TickMode.
5. Set its power mode to Unrestricted.

After changing this setting, test a session with the screen locked.

Other Infinix and XOS versions may use different menu names or offer different options.

## Other manufacturers

Some manufacturers provide additional controls for background applications.

Depending on the device, look for settings such as:

- Unrestricted battery usage
- Background activity
- Sleeping apps or Deep sleeping apps
- App auto-start
- Protected apps
- Background power management

The location and behaviour of these settings vary. Do not assume that the Infinix instructions above apply to another manufacturer.

## Testing

After changing background settings:

1. Start a session using a short interval.
2. Lock the screen.
3. Leave the device untouched for several minutes.
4. Check whether signals continue.
5. Check the persistent TickMode notification.

For important use, verify background operation on the particular device before relying on it.

## Limitations

Android does not guarantee identical background behaviour across all devices.

Force-stopping the app, manufacturer-specific process management, power-saving modes and operating-system restrictions can interrupt a session. TickMode cannot guarantee delivery of every signal on every device.
