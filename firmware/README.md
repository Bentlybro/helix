# Helix Firmware

Each firmware module lives in its own folder matching the `.ino` filename (Arduino requirement).

## Modules

| Folder | Description |
|--------|-------------|
| `helix-imu/` | XIAO nRF52840 Sense — streams roll/pitch/yaw over BLE |

## Setup (all modules)

**Board:** Seeed nRF52 Boards → Seeed XIAO BLE Sense - nRF52840 *(NOT mbed-enabled)*

**Libraries:**
- Seeed Arduino LSM6DS3 (by Seeed Studio)
