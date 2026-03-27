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
- **Orientation** — quaternion or euler angles (yaw/pitch/roll)
- **Acceleration** — raw accelerometer data
- **Timestamp** — for synchronization

Protocol TBD — could be JSON over WebSocket, protobuf, or a custom binary format.

## Architecture

```
┌─────────────────┐     BLE/WiFi     ┌─────────────────┐
│   HeadTracker   │ ───────────────► │   Client App    │
│   (ESP32-S3)    │                  │   (Phone/PC)    │
│   + IMU         │                  │                 │
└─────────────────┘                  └─────────────────┘
```

The device is dumb — it just streams sensor data. All the logic (gesture detection, screen mapping, etc.) lives in the client apps.

## Hardware (Tentative)

- **MCU**: ESP32-S3 (BT + WiFi, familiar stack)
- **IMU**: TBD — BNO055 (on-chip fusion) or LSM6DS3 + custom fusion
- **Battery**: Small LiPo, maybe 100-150mAh
- **Charging**: USB-C or Qi wireless
- **Enclosure**: 3D printed, behind-the-ear hook design

## Status

🧠 Idea phase — planning and architecture

## Next Steps

- [ ] Finalize IMU choice (fusion vs raw)
- [ ] Design power system (battery size, charging)
- [ ] Prototype enclosure shape (comfort test)
- [ ] Define data protocol
- [ ] Build MVP firmware (stream orientation over BLE)
- [ ] Build demo app (monitor focus switching)
