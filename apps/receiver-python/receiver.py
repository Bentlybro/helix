#!/usr/bin/env python3
"""
HeadTracker BLE Receiver
Receives orientation and IMU data from the HeadTracker wearable
"""

import asyncio
import struct
from bleak import BleakClient, BleakScanner

# BLE UUIDs (must match firmware)
SERVICE_UUID = "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
ORIENTATION_UUID = "917649A1-D98E-11E5-9EEC-0002A5D5C51B"
RAW_IMU_UUID = "917649A2-D98E-11E5-9EEC-0002A5D5C51B"

DEVICE_NAME = "HeadTracker"


def parse_orientation(data: bytearray) -> tuple:
    """Parse roll, pitch, yaw from 12 bytes (3x float32)"""
    return struct.unpack('<fff', data)


def parse_raw_imu(data: bytearray) -> tuple:
    """Parse accel XYZ, gyro XYZ from 24 bytes (6x float32)"""
    values = struct.unpack('<ffffff', data)
    return values[:3], values[3:]


def orientation_handler(sender, data: bytearray):
    """Callback for orientation notifications"""
    roll, pitch, yaw = parse_orientation(data)
    print(f"[ORIENTATION] Roll: {roll:7.2f}° | Pitch: {pitch:7.2f}° | Yaw: {yaw:7.2f}°")


def raw_imu_handler(sender, data: bytearray):
    """Callback for raw IMU notifications"""
    accel, gyro = parse_raw_imu(data)
    print(f"[RAW IMU] Accel: ({accel[0]:6.2f}, {accel[1]:6.2f}, {accel[2]:6.2f}) | "
          f"Gyro: ({gyro[0]:6.2f}, {gyro[1]:6.2f}, {gyro[2]:6.2f})")


async def find_device():
    """Scan for HeadTracker device"""
    print("Scanning for HeadTracker...")
    device = await BleakScanner.find_device_by_filter(
        lambda d, ad: d.name and d.name == DEVICE_NAME
    )
    return device


async def main():
    # Find device
    device = await find_device()
    if not device:
        print("HeadTracker not found! Make sure it's powered on and advertising.")
        return

    print(f"Found HeadTracker: {device.address}")

    async with BleakClient(device) as client:
        print(f"Connected to {device.address}")

        # Subscribe to characteristics
        await client.start_notify(ORIENTATION_UUID, orientation_handler)
        await client.start_notify(RAW_IMU_UUID, raw_imu_handler)

        print("Receiving data... Press Ctrl+C to stop")
        print("-" * 80)

        try:
            # Keep running
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            print("\nStopping...")

        # Unsubscribe
        await client.stop_notify(ORIENTATION_UUID)
        await client.stop_notify(RAW_IMU_UUID)

    print("Disconnected")


if __name__ == "__main__":
    asyncio.run(main())
