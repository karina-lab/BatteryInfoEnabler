# Battery Info Enabler

An LSPosed module that unlocks and enhances the native battery information for Pixel 6+ devices.

## Description

While Google officially introduced detailed battery metrics (Manufacture date, Date of first use, Cycle count and Battery health) starting with the **Pixel 9a**, this module backports the interface to all Tensor-based Pixels (6 and newer).

## Installation

1. Install the APK.
2. Enable the **Battery Info Enabler** module in **LSPosed Manager**.
3.  Ensure **both** of the following are selected in the module's scope:
* **Settings** (`com.android.settings`)
* **Settings Services** (`com.google.android.settings.intelligence`)
4. Restart the **Settings** and **Settings Services** apps.
5. Grant root access to **Settings** and **Settings Services** (when prompted or manually in your root manager) to allow reading hardware battery stats.

## Features
The information is now distributed across two sections in your device settings:

### 1. Battery Information
**Location:** `Settings` -> `About Phone` -> `Battery information`
* **Manufacture date**: Original production date.
* **Date of first use**: When the battery was first powered on.
* **Cycle count**: Total charge cycles.
* **Design capacity**: The battery's intended capacity when new.
* **Maximum capacity**: Current capacity relative to design capacity.
* **Temperature**: Current battery temperature.
* **Serial number**: Unique hardware identifier.

### 2. Battery Health
**Location:** `Settings` -> `Battery` -> `Battery Health`
* **Maximum capacity**: Current capacity relative to design capacity.
* **Health Notification**: Will tell you if the battery requires service due to:
    * Low maximum capacity (less than 80%).
    * High internal resistance.
    * Low battery health.

## Compatibility

* **Devices**: Pixel 6 series and newer.
* **OS**: Tested on Android 16 QPR3.
