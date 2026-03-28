# Helix

A modular behind-the-ear wearable platform. The ear clip is the mount — snap any module onto it magnetically.

## Concept

Helix is not a single-purpose device. It's a **platform**.

The ear clip attaches to your ear and stays there. Different electronic modules magnetically connect to it — each with their own MCU, sensors, and purpose. Swap modules, change what the device does. The clip is the constant, the module is the variable.

Current modules:
- **Helix IMU** — head pose tracking (roll, pitch, yaw) over BLE
- **Helix Mic** — microphone audio streaming over BLE for speech-to-text

Planned modules: anything that fits behind an ear.

## Hardware

### Ear Clip
3D printed, behind-the-ear hook design with magnetic mounting face. Sits discreetly over the ear like a hearing aid. Houses no electronics — purely mechanical.

### Helix IMU Module
- **MCU**: Seeed XIAO nRF52840 Sense
- **IMU**: Onboard LSM6DS3 (accel + gyro) — 6DOF, 200Hz
- **Connectivity**: BLE 5.0
- **Power**: LP502030 250mAh LiPo + slide switch
- **Extras**: Battery % monitoring, onboard temperature sensor

### Helix Mic Module
- **MCU**: Seeed XIAO nRF52840 Sense
- **Mic**: Onboard PDM microphone — 16kHz 16-bit mono
- **Connectivity**: BLE 5.0
- **Firmware**: Zephyr RTOS

## Repository Structure

```
helix/
├── firmware/
│   ├── helix-imu/          # Arduino (Seeed nRF52 Boards)
│   │   └── helix-imu.ino
│   └── helix-mic/          # Zephyr RTOS (nRF Connect SDK v2.5.0)
│       ├── CMakeLists.txt
│       ├── prj.conf
│       ├── boards/
│       └── src/
├── apps/
│   ├── helix-imu/              # Python BLE receiver (testing)
│   │   ├── receiver.py
│   │   └── scan.py
│   ├── helix-imu-android/      # Android app — live orientation + visualiser
│   └── helix-mic-android/      # Android app — audio stream + Whisper STT
└── hardware/                   # 3D models (coming soon)
```

## BLE Protocol

All Helix modules share a common service UUID with module-specific characteristics.

**Service UUID:** `917649A0-D98E-11E5-9EEC-0002A5D5C51B`

| Characteristic | UUID | Module | Format |
|---|---|---|---|
| Orientation | `917649A1-...` | IMU | 3x float32 (roll, pitch, yaw °) |
| Battery | `917649A2-...` | IMU, Mic | uint8 (0–100%) |
| Temperature | `917649A3-...` | IMU | float32 (°C) |
| Audio | `917649B0-...` | Mic | [seq:2][flags:1][pcm data] |

## Getting Started

### Helix IMU

**Flash firmware (Arduino):**
1. Install Arduino IDE
2. Add board URL: `https://files.seeedstudio.com/arduino/package_seeeduino_arduino_board_index.json`
3. Install **Seeed nRF52 Boards** (non-mbed variant)
4. Install **Seeed Arduino LSM6DS3** library
5. Select **Seeed XIAO BLE Sense - nRF52840**
6. Open `firmware/helix-imu/helix-imu.ino` and upload

**Android app:**
Open `apps/helix-imu-android/` in Android Studio and run.

Features: live roll/pitch/yaw display, 3D direction visualiser, calibration, battery %, temperature.

### Helix Mic

**Flash firmware (Zephyr):**
1. Build with nRF Connect SDK v2.5.0
2. Double-tap reset on XIAO to enter bootloader
3. Drag `zephyr.uf2` onto the `XIAO-SENSE` drive

**Android app:**
Open `apps/helix-mic-android/` in Android Studio and run.

Features: BLE audio receive, WAV buffering, Whisper API transcription.

## Building Firmware (Server)

nRF Connect SDK is set up on the build server.

```bash
# Helix Mic (Zephyr)
cd ~/nrf-sdk/zephyr
west build -b xiao_ble ~/clawd/repos/helix/firmware/helix-mic

# Output
build/zephyr/zephyr.uf2
```

## Status

| Module | Firmware | Android App | Hardware |
|---|---|---|---|
| Helix IMU | ✅ Working | ✅ Working | 🚧 Enclosure in progress |
| Helix Mic | ✅ Advertising | 🚧 In progress | ⬜ Not started |

## License

MIT
