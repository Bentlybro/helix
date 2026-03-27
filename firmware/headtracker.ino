/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Streams orientation (roll, pitch, yaw) over BLE
 * 
 * Required libraries:
 * - ArduinoBLE (by Arduino) - install via Library Manager
 * 
 * The LSM6DS3 IMU is pre-instantiated as 'IMU' by the board package
 */

#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

// BLE UUIDs
const char* SERVICE_UUID = "917649A0-D98E-11E5-9EEC-0002A5D5C51B";
const char* ROLL_UUID    = "917649A1-D98E-11E5-9EEC-0002A5D5C51B";
const char* PITCH_UUID   = "917649A2-D98E-11E5-9EEC-0002A5D5C51B";
const char* YAW_UUID     = "917649A3-D98E-11E5-9EEC-0002A5D5C51B";

// BLE Service and Characteristics
BLEService headTrackerService(SERVICE_UUID);
BLEFloatCharacteristic rollChar(ROLL_UUID,  BLERead | BLENotify);
BLEFloatCharacteristic pitchChar(PITCH_UUID, BLERead | BLENotify);
BLEFloatCharacteristic yawChar(YAW_UUID,   BLERead | BLENotify);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
const float dt = 0.01;      // 10ms = 100Hz
const float alpha = 0.98;   // Complementary filter coefficient

void setup() {
  Serial.begin(115200);

  // Init IMU
  if (!IMU.begin()) {
    Serial.println("IMU init failed!");
    while (1);
  }
  Serial.println("IMU initialized");

  // Init BLE
  if (!BLE.begin()) {
    Serial.println("BLE init failed!");
    while (1);
  }

  BLE.setLocalName("HeadTracker");
  BLE.setAdvertisedService(headTrackerService);
  headTrackerService.addCharacteristic(rollChar);
  headTrackerService.addCharacteristic(pitchChar);
  headTrackerService.addCharacteristic(yawChar);
  BLE.addService(headTrackerService);

  rollChar.writeValue(0.0f);
  pitchChar.writeValue(0.0f);
  yawChar.writeValue(0.0f);

  BLE.advertise();
  Serial.println("HeadTracker advertising...");
}

void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("Connected: ");
    Serial.println(central.address());

    while (central.connected()) {
      unsigned long now = millis();
      if (now - lastUpdate >= 10) {
        lastUpdate = now;
        updateOrientation();
        sendBleData();
      }
      BLE.poll();
    }

    Serial.println("Disconnected");
  }

  BLE.poll();
}

void updateOrientation() {
  float accelX, accelY, accelZ;
  float gyroX, gyroY, gyroZ;

  // Read acceleration (m/s^2) and gyroscope (deg/s)
  if (!IMU.readAcceleration(accelX, accelY, accelZ)) return;
  if (!IMU.readGyroscope(gyroX, gyroY, gyroZ)) return;

  // Accel-based roll and pitch
  float rollAccel  = atan2(accelY, accelZ);
  float pitchAccel = atan2(-accelX, sqrt(accelY * accelY + accelZ * accelZ));

  // Integrate gyro for yaw (relative only, no magnetometer)
  yaw += gyroZ * dt;
  if (yaw >  180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;

  // Complementary filter for roll and pitch
  roll  = alpha * (roll  + gyroX * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroY * dt) + (1.0 - alpha) * pitchAccel;

  // Convert to degrees
  roll  *= RAD_TO_DEG;
  pitch *= RAD_TO_DEG;
}

void sendBleData() {
  rollChar.writeValue(roll);
  pitchChar.writeValue(pitch);
  yawChar.writeValue(yaw);

  Serial.print("R:"); Serial.print(roll);
  Serial.print(" P:"); Serial.print(pitch);
  Serial.print(" Y:"); Serial.println(yaw);
}
