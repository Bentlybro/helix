#!/usr/bin/env python3
"""
BLE Scanner - lists all nearby BLE devices
Use this to verify HeadTracker is advertising
"""

import asyncio
from bleak import BleakScanner


async def main():
    print("Scanning for BLE devices (5 seconds)...")
    devices = await BleakScanner.discover(timeout=5.0)

    if not devices:
        print("No BLE devices found.")
        return

    print(f"\nFound {len(devices)} device(s):\n")
    for d in sorted(devices, key=lambda x: x.rssi, reverse=True):
        name = d.name or "(unnamed)"
        print(f"  {d.address}  RSSI: {d.rssi:4d}  Name: {name}")


if __name__ == "__main__":
    asyncio.run(main())
