#!/usr/bin/env python3
"""
Helix BLE Receiver
Receives packed orientation data (roll, pitch, yaw) from Helix wearable
"""

import asyncio
import struct
from bleak import BleakClient, BleakScanner

# Must match firmware
ORIENTATION_UUID = "917649a1-d98e-11e5-9eec-0002a5d5c51b"
DEVICE_NAME = "Helix"


def orientation_handler(sender, data: bytearray):
    """Single notify with all 3 floats packed — no lag, no sync issues"""
    roll, pitch, yaw = struct.unpack('<fff', bytes(data))
    print(f"\rRoll: {roll:7.2f}°  Pitch: {pitch:7.2f}°  Yaw: {yaw:7.2f}°    ", end="", flush=True)


async def main():
    print(f"Scanning for '{DEVICE_NAME}'...")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)

    if not device:
        print("Helix not found! Is it powered on?")
        return

    print(f"Found: {device.address}")

    async with BleakClient(device) as client:
        print("Connected!")
        print("Receiving orientation data... Ctrl+C to stop")
        print("-" * 60)

        await client.start_notify(ORIENTATION_UUID, orientation_handler)

        try:
            while True:
                await asyncio.sleep(0.1)
        except KeyboardInterrupt:
            print("\nStopping...")

        await client.stop_notify(ORIENTATION_UUID)

    print("Disconnected")


if __name__ == "__main__":
    asyncio.run(main())
