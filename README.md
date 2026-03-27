# Helix

A modular behind-the-ear wearable platform. The ear clip is the mount — snap any module onto it.

## Vision

Not a single-purpose device — a **platform**. The ear clip attaches to your ear, and any module can magnetically connect to it. Start with the IMU module for head tracking, build anything else later.

**Modules (current + planned):**
- **Helix IMU** — XIAO nRF52840 Sense, head pose tracking over BLE
- More to come — the clip is the constant, the module is the variable

## Ear Clip

Behind-the-ear hook design, 3D printed:
- Hooks over the top and bottom of the ear
- Flat magnetic mounting face on the back
- Discreet, comfortable for all-day wear
- Designed for swappable modules

## Connectivity

- **Bluetooth LE** — primary, for mobile apps and desktop
- Protocol: single packed BLE characteristic (12 bytes: roll, pitch, yaw as float32)

## Repository Structure

```
helix/
├── firmware/
│   └── helix.ino          # Arduino firmware for XIAO nRF52840 Sense
├── apps/
│   └── receiver-python/   # Python BLE receiver for testing
│       ├── receiver.py
│       └── scan.py
└── README.md
```

## Getting Started (IMU Module)

### 1. Flash Firmware

1. Install [Arduino IDE](https://www.arduino.cc/en/software)
2. Add Seeed nRF52 boards: `https://files.seeedstudio.com/arduino/package_seeeduino_arduino_board_index.json`
3. Install **"Seeed nRF52 Boards"** package (NOT mbed-enabled)
4. Install **"Seeed Arduino LSM6DS3"** library
5. Select **"Seeed XIAO BLE Sense - nRF52840"** as board
6. Open `firmware/helix.ino` and upload

### 2. Run Receiver

```bash
cd apps/receiver-python
pip install bleak
python receiver.py
```

## Hardware (IMU Module)

- **MCU**: Seeed XIAO nRF52840 Sense (BLE + onboard LSM6DS3 IMU)
- **Battery**: LP502030 250mAh LiPo
- **Switch**: Slide switch in series with battery
- **Enclosure**: 3D printed module, magnetically attaches to ear clip

## Status

🚧 **Prototype phase** — firmware working, ear clip printed, module enclosure in progress

## License

MIT
