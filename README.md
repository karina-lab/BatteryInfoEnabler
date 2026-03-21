# Pixel Battery Info Enabler

An LSPosed module that unlocks and enhances the native battery information for Pixel 6+ devices.

## Description

While Google officially introduced detailed battery metrics (Manufacture date, Date of first use, and Cycle count) starting with the **Pixel 9a**, this module backports the interface to all Tensor-based Pixels (6 and newer).

In addition to the standard fields, this module injects two exclusive hardware data points that are not available in the stock implementation: **Maximum Capacity** (remaining health percentage) and the battery's unique **Serial Number**.

## Installation

1. Install the APK.
2. Enable the **Pixel Battery Info Enabler** module in **LSPosed Manager**.
3. Ensure **Settings** (`com.android.settings`) is selected in the module's scope.
4. **(KernelSU/APatch)** Manually grant root access to the **Settings** to allow reading hardware battery stats.
5. Restart the **Settings** app.
6. Navigate to **Settings -> About Phone -> Battery information**.
7. **(Magisk)** Grant root access to the **Settings** to allow reading hardware battery stats. 


## Features

* **Manufacture date**: Original production date.
* **Date of first use**: When the battery was first powered on.
* **Cycle count**: Total charge cycles.
* **Maximum capacity**: Current health relative to design capacity.
* **Serial number**: Battery hardware identifier.

**Note**: If you don't grant root access to Settings, **Maximum capacity** and  **Serial number** won't show.

## Compatibility

* **Devices**: Pixel 6 series and newer.
* **OS**: Tested on Android 16 QRP3.