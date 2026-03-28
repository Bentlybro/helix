#!/usr/bin/env python3
"""
Helix-Mic BLE Audio Test
Connects to Helix-Mic, receives audio packets, saves as WAV.
Run on any machine with BLE support.

Usage: python3 ble_audio_test.py [--seconds 10]
"""

import asyncio
import struct
import wave
import sys
import argparse
from bleak import BleakClient, BleakScanner

SERVICE_UUID = "917649a0-d98e-11e5-9eec-0002a5d5c51b"
AUDIO_UUID = "917649b0-d98e-11e5-9eec-0002a5d5c51b"

SAMPLE_RATE = 16000
SAMPLE_WIDTH = 2  # 16-bit
CHANNELS = 1

packets = []
total_bytes = 0

def audio_callback(sender, data: bytearray):
    global total_bytes
    if len(data) < 4:
        return
    # Parse: [seq:2][flags:1][pcm_data]
    seq = struct.unpack_from('<H', data, 0)[0]
    flags = data[2]
    pcm = data[3:]
    packets.append(pcm)
    total_bytes += len(pcm)
    if seq % 100 == 0:
        print(f"  Packet {seq}: {len(pcm)} bytes (total: {total_bytes} bytes, {total_bytes/32000:.1f}s)")

async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--seconds', type=int, default=10, help='Record duration')
    parser.add_argument('--output', type=str, default='helix_test.wav', help='Output WAV file')
    args = parser.parse_args()

    print("Scanning for Helix-Mic...")
    device = await BleakScanner.find_device_by_name("Helix-Mic", timeout=10)
    if not device:
        print("ERROR: Helix-Mic not found!")
        sys.exit(1)
    
    print(f"Found: {device.name} ({device.address})")
    
    disconnected = asyncio.Event()
    
    def on_disconnect(client):
        print("\n⚠️  Device disconnected!")
        disconnected.set()
    
    async with BleakClient(device, timeout=15, disconnected_callback=on_disconnect) as client:
        print(f"Connected! MTU: {client.mtu_size}")
        
        # Subscribe to audio notifications
        await client.start_notify(AUDIO_UUID, audio_callback)
        print(f"Subscribed to audio. Recording {args.seconds}s...")
        
        # Wait for duration or disconnect
        try:
            await asyncio.wait_for(disconnected.wait(), timeout=args.seconds)
        except asyncio.TimeoutError:
            pass  # Normal — recording finished
        
        try:
            if client.is_connected:
                await client.stop_notify(AUDIO_UUID)
        except Exception:
            pass
        print(f"\nDone! Received {len(packets)} packets, {total_bytes} bytes")
    
    if not packets:
        print("No audio data received!")
        sys.exit(1)
    
    # Combine all PCM data
    pcm_data = b''.join(packets)
    
    # Check if it's all zeros or constant (silence/noise indicator)
    samples = struct.unpack(f'<{len(pcm_data)//2}h', pcm_data)
    min_val = min(samples)
    max_val = max(samples)
    avg_val = sum(samples) / len(samples)
    
    # Calculate RMS
    rms = (sum(s*s for s in samples) / len(samples)) ** 0.5
    
    print(f"\nAudio stats:")
    print(f"  Samples: {len(samples)}")
    print(f"  Duration: {len(samples)/SAMPLE_RATE:.2f}s")
    print(f"  Min: {min_val}, Max: {max_val}")
    print(f"  Average: {avg_val:.1f}")
    print(f"  RMS: {rms:.1f}")
    
    if max_val - min_val < 10:
        print("  ⚠️  VERY LOW dynamic range — likely silence or dead mic")
    elif rms < 50:
        print("  ⚠️  LOW RMS — audio is very quiet")
    elif rms > 20000:
        print("  ⚠️  HIGH RMS — possibly clipping/noise")
    else:
        print("  ✅ Audio levels look reasonable")
    
    # Save WAV
    with wave.open(args.output, 'w') as wf:
        wf.setnchannels(CHANNELS)
        wf.setsampwidth(SAMPLE_WIDTH)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(pcm_data)
    
    print(f"\nSaved to {args.output}")
    print(f"Play with: aplay -f S16_LE -r 16000 -c 1 {args.output}")
    print(f"Or: ffplay {args.output}")

if __name__ == '__main__':
    asyncio.run(main())
