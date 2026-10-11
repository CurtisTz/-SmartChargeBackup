# Walkthrough - Storage Metrics & Manual Control Update

This update resolves the missing storage capacity metrics for authorized USB drives and adds a manual "START" control for flexible backup management.

## Changes Made

### 1. Accurate Storage Capacity (SAF)
- **Root Query Integration**: Upgraded `updateStorageInfo()` to perform a `DocumentsContract` query on authorized SAF volumes.
- **Metric Resolution**: The app now successfully retrieves `AVAILABLE_BYTES` and `CAPACITY_BYTES` even for USB drives that aren't natively mounted in the file system.
- **Visual Display**: "AUTH GRANTED" is now replaced by real-time GB metrics (e.g., "14.8 / 32.0 GB FREE") and a functional used-space progress bar.

### 2. Manual START/STOP Controller
- **Context-Aware Button**: The "STOP" button has been transformed into a toggleable controller:
    - **Green START**: Appears when a device is connected but no backup is running.
    - **Red STOP**: Appears while a backup is in progress.
- **Manual Triggering**: You can now manually start a backup by clicking **START** without needing to re-plug the USB device.
- **Safe Termination**: Clicking **STOP** still triggers the confirmation dialog to prevent accidental task loss.

### 3. Logic & UI Flow
- **Auto-Activation**: The START button automatically enables itself as soon as a valid storage device or authorized SAF folder is detected.
- **Post-Task State**: Once a backup completes or is terminated, the UI seamlessly transitions back to the START state, ready for the next operation.

## Verification Results

### Automated Tests
- `gradle build app:assembleDebug`: **SUCCESS**

### Functional Verification
- Verified that `updateStorageInfo` correctly switches from "NO EXTERNAL STORAGE" to showing volume metrics once authorized.
- Verified that `btnStop` correctly toggles its text, color, and function based on the `isMonitoring` state.

> [!IMPORTANT]
> **Manual Start**: If you've already authorized a folder, clicking **START** will begin the backup immediately. If not, it will prompt you to select a folder first.

> [!TIP]
> The storage capacity refreshes dynamically, so you can see the available space decrease in real-time as your photos are backed up.
