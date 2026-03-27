# HeadTracker Firmware

XIAO nRF52840 Sense firmware that streams IMU data over BLE.

## Hardware

- Seeed XIAO nRF52840 Sense
- Onboard LSM6DS3 IMU (accel + gyro)
- Battery TBD

## Development

### Setup

1. Install Arduino IDE
2. Add Seeed nRF52 boards URL: `https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json`
3. Install "Seeed nRF52 Boards" package
4. Select "Seeed XIAO BLE - nRF52840" as board

### Libraries Required

- `LSM6DS3` (Seeed library for onboard IMU)
- `ArduinoBLE` (BLE communication)

### Flashing

1. Connect XIAO via USB-C
2. Double-press reset button to enter bootloader
3. Upload from Arduino IDE

## BLE Protocol

**Service UUID:** `917649A0-D98E-11E5-9EEC-0002A5D5C51B` (custom)

**Characteristics:**

| Characteristic | UUID | Type | Description |
|---------------|------|------|-------------|
| Orientation | `917649A1-D98E-11E5-9EEC-0002A5D5C51B` | float[3] | Yaw, Pitch, Roll (radians) |
| Raw IMU | `917649A2-D98E-11E5-9EEC-0002A5D5C51B` | float[6] | Accel XYZ, Gyro XYZ |

## Status

🚧 In development
