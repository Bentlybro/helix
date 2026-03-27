# Helix IMU — Python Receiver

Receives orientation data (roll, pitch, yaw) from the Helix IMU module over BLE.

## Setup

```bash
pip install bleak
```

## Usage

```bash
# Check if Helix IMU is advertising
python scan.py

# Connect and receive orientation data
python receiver.py
```
