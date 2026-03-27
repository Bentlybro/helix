#!/usr/bin/env python3
"""
HeadTracker BLE Receiver
Receives orientation data from the HeadTracker wearable
"""

import asyncio
from bleak import BleakClient, BleakScanner

# BLE UUIDs (must match firmware)
SERVICE_UUID = "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
ROLL_UUID = "917649A1-D98E-11E5-9EEC-0002A5D5C51B"
PITCH_UUID = "917649A2-D98E-11E5-9EEC-0002A5D5C51B"
YAW_UUID = "917649A3-D98E-11E5-9EEC-0002A5D5C51B"

DEVICE_NAME = "HeadTracker"

# Latest values
roll_val = 0.0
pitch_val = 0.0
yaw_val = 0.0


def roll_handler(sender, data: bytearray):
    global roll_val
    roll_val = float.from_bytes(data, byteorder='little', signed=True)
    print_state()


def pitch_handler(sender, data: bytearray):
    global pitch_val
    pitch_val = float.from_bytes(data, byteorder='little', signed=True)
    print_state()


def yaw_handler(sender, data: bytearray):
    global yaw_val
    yaw_val = float.from_bytes(data, byteorder='little', signed=True)
    print_state()


def print_state():
    print(f"\rRoll: {roll_val:7.2f}° | Pitch: {pitch_val:7.2f}° | Yaw: {yaw_val:7.2f}°", end="", flush=True)


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
        print("Receiving data... Press Ctrl+C to stop")
        print("-" * 60)

        # Subscribe to characteristics
        await client.start_notify(ROLL_UUID, roll_handler)
        await client.start_notify(PITCH_UUID, pitch_handler)
        await client.start_notify(YAW_UUID, yaw_handler)

        try:
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            print("\nStopping...")

        await client.stop_notify(ROLL_UUID)
        await client.stop_notify(PITCH_UUID)
        await client.stop_notify(YAW_UUID)

    print("Disconnected")


if __name__ == "__main__":
    asyncio.run(main())
