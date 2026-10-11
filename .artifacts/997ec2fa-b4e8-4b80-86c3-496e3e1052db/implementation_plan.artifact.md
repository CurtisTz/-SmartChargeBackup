# Implementation Plan - Storage Metrics Fix & Manual Start Control

This plan addresses the missing storage capacity metrics after SAF authorization and implements a toggleable START/STOP button for manual backup control.

## Proposed Changes

### Logic & UI Refinement

#### [MODIFY] [MainActivity.kt](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/java/com/example/smartchargebackup/MainActivity.kt)
- **Storage Metrics Fix**:
    - Enhance `updateStorageInfo()` to better resolve storage paths.
    - If `volume.directory` is null, try to match the volume via its UUID or description against the authorized `usbTreeUri`.
    - Implement a more robust way to calculate capacity for SAF-authorized volumes using `DocumentFile` and `ContentResolver` if `StatFs` fails.
- **START/STOP Toggle Logic**:
    - Rename the internal logic handling `btnStop` to support a "START" state.
    - When `!isMonitoring`, `btnStop` text becomes **"START"** (Green/Neon color).
    - When `isMonitoring`, `btnStop` text remains **"STOP"** (Red color).
    - Enable the "START" button if a valid `usbTreeUri` exists or a USB device is detected.
- **Manual Backup Trigger**:
    - Add `manualStartBackup()` which checks for permissions and authorized folders before starting the coroutine job.

### UI Enhancements

#### [MODIFY] [activity_main.xml](file:///C:/Users/iiscu/OneDrive/Desktop/level up/Android/android_project/SmartChargeBackup/app/src/main/res/layout/activity_main.xml)
- Adjust `btnStop` initial state/text if necessary (though code will handle most of it).

## Verification Plan

### Automated Tests
- Build the project to verify that the new state-checking logic doesn't interfere with the `updateSystemStatus` loop.

### Manual Verification
1.  **Capacity Display**: Plug in a USB, authorize it, and verify that "AUTH GRANTED" is replaced by actual GB metrics (e.g., "15.0 / 32.0 GB FREE").
2.  **Manual Start**: Press "STOP" during a backup. Verify it turns into a green "START" button. Press "START" and verify the backup begins again without re-plugging the device.
3.  **Dynamic Update**: Verify the capacity numbers update every 2 seconds during the backup.
