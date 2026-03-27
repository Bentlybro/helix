# HeadTracker Python Receiver

Receives IMU data from HeadTracker wearable over BLE and displays orientation in real-time.

## Setup

```bash
pip install bleak numpy
```

## Usage

```bash
python receiver.py
```

## Requirements

- Bluetooth adapter (built-in or USB)
- Linux/Windows/macOS

## Protocol

Receives two characteristics:
- **Orientation** (12 bytes): roll, pitch, yaw as 3x float32
- **Raw IMU** (24 bytes): accel X/Y/Z, gyro X/Y/Z as 6x float32
