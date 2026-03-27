#!/usr/bin/env python3
"""
HeadTracker BLE Receiver
Receives orientation data from the HeadTracker wearable
"""

import asyncio
import struct
from bleak import BleakClient, BleakScanner

# BLE UUIDs (must match firmware)
ROLL_UUID  = "917649a1-d98e-11e5-9eec-0002a5d5c51b"
PITCH_UUID = "917649a2-d98e-11e5-9eec-0002a5d5c51b"
YAW_UUID   = "917649a3-d98e-11e5-9eec-0002a5d5c51b"

DEVICE_NAME = "HeadTracker"

# Latest values
state = {"roll": 0.0, "pitch": 0.0, "yaw": 0.0}


def parse_float(data: bytearray) -> float:
    """Parse a little-endian IEEE 754 float from 4 bytes"""
    return struct.unpack('<f', bytes(data))[0]


def roll_handler(sender, data: bytearray):
    state["roll"] = parse_float(data)
    print_state()


def pitch_handler(sender, data: bytearray):
    state["pitch"] = parse_float(data)
    print_state()


def yaw_handler(sender, data: bytearray):
    state["yaw"] = parse_float(data)
    print_state()


def print_state():
    print(f"\rRoll: {state['roll']:7.2f}°  Pitch: {state['pitch']:7.2f}°  Yaw: {state['yaw']:7.2f}°    ", end="", flush=True)


async def find_device(timeout=10.0):
    """Scan for HeadTracker device"""
    print(f"Scanning for '{DEVICE_NAME}' (timeout {timeout}s)...")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=timeout)
    return device


async def main():
    device = await find_device()
    if not device:
        print(f"\nHeadTracker not found!")
        print("Make sure it's powered on and advertising.")
        print("Try running: python scan.py  to list nearby BLE devices")
        return

    print(f"Found HeadTracker: {device.address}")

    async with BleakClient(device) as client:
        print(f"Connected!")
        print("Receiving data... Press Ctrl+C to stop")
        print("-" * 60)

        await client.start_notify(ROLL_UUID, roll_handler)
        await client.start_notify(PITCH_UUID, pitch_handler)
        await client.start_notify(YAW_UUID, yaw_handler)

        try:
            while True:
                await asyncio.sleep(0.1)
        except KeyboardInterrupt:
            print("\nStopping...")

        await client.stop_notify(ROLL_UUID)
        await client.stop_notify(PITCH_UUID)
        await client.stop_notify(YAW_UUID)

    print("Disconnected")


if __name__ == "__main__":
    asyncio.run(main())
