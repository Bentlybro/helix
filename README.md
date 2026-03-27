# HeadTracker

A discreet behind-the-ear wearable for head pose tracking. Generic IMU platform that any app can consume.

## Vision

Not a single-purpose device — a **platform**. The wearable streams head rotation and movement data over Bluetooth or WiFi, and any application can subscribe to that data stream.

Use cases are built as separate apps:
- **Monitor focus switching** — auto-switch active window based on which screen you're facing
- **Gesture control** — nod/shake/tilt as input
- **VRChat head tracking** — feed directly into avatar animation
- **Accessibility** — head-as-mouse for hands-free computing
- **Sports/training** — track head position during drills

## Form Factor

Behind-the-ear hook style (similar to hearing aids):
- Discreet enough for daily wear
- Stable positioning for accurate tracking
- Room for small battery and electronics

## Connectivity

- **Bluetooth LE** — primary, for mobile apps and always-on use
- **WiFi** — secondary, for home/PC use (lower latency, higher bandwidth)

Both protocols expose the same data stream — apps don't care which transport.

## Data Stream

The device broadcasts:
- **Orientation** — roll, pitch, yaw (degrees)
- **Raw IMU** — accelerometer XYZ, gyroscope XYZ
- **Timestamp** — for synchronization

## Architecture

```
┌─────────────────┐      BLE       ┌─────────────────┐
│   HeadTracker   │ ─────────────► │   Client App    │
│ (XIAO nRF52840  │                │   (Phone/PC)    │
│    Sense)       │                │                 │
└─────────────────┘                └─────────────────┘
```

The device is dumb — it just streams sensor data. All the logic (gesture detection, screen mapping, etc.) lives in the client apps.

## Hardware

**Prototype (current):**
- **MCU**: Seeed XIAO nRF52840 Sense
- **IMU**: Onboard LSM6DS3 (accel + gyro)
- **Connectivity**: BLE 5.0
- **Power**: USB-C (battery TBD)

**Future (custom PCB):**
- Same hardware, miniaturized
- Integrated LiPo + charging
- Behind-the-ear enclosure

## Repository Structure

```
headtracker/
├── firmware/           # Arduino firmware for XIAO nRF52840 Sense
│   └── headtracker.ino
├── apps/
│   └── receiver-python/ # Python BLE receiver for testing
│       └── receiver.py
└── README.md
```

## Getting Started

### 1. Flash Firmware

1. Install [Arduino IDE](https://www.arduino.cc/en/software)
2. Add Seeed nRF52 boards: `https://files.seeedstudio.com/arduino/package_seeeduino_arduino_board_index.json`
3. Install "Seeed nRF52 Boards" package
4. Open `firmware/headtracker.ino`
5. Select "Seeed XIAO BLE Sense - nRF52840" as board
6. Upload (double-press reset to enter bootloader)

### 2. Run Receiver

```bash
cd apps/receiver-python
pip install bleak numpy
python receiver.py
```

The receiver will scan for "HeadTracker" and display orientation/IMU data.

## Status

🚧 **Prototype phase** — firmware and basic receiver working

## Next Steps

- [ ] Test firmware on hardware
- [ ] Add battery + charging
- [ ] Design 3D printed enclosure
- [ ] Build monitor-switching app
- [ ] Improve sensor fusion (Madgwick filter?)

## License

MIT
