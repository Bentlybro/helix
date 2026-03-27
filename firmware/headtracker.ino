/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Streams orientation (roll, pitch, yaw) over BLE
 * 
 * Required libraries (install via Arduino Library Manager):
 * - ArduinoBLE (by Arduino)
 * - LSM6DS3 (by Seeed Studio)
 */

#include <ArduinoBLE.h>
#include "LSM6DS3.h"
#include <Wire.h>

// BLE Service and Characteristic UUIDs
const char* SERVICE_UUID = "917649A0-D98E-11E5-9EEC-0002A5D5C51B";
const char* ROLL_UUID = "917649A1-D98E-11E5-9EEC-0002A5D5C51B";
const char* PITCH_UUID = "917649A2-D98E-11E5-9EEC-0002A5D5C51B";
const char* YAW_UUID = "917649A3-D98E-11E5-9EEC-0002A5D5C51B";

// IMU - Create instance for XIAO nRF52840 Sense
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE Service
BLEService headTrackerService(SERVICE_UUID);

// BLE Characteristics - individual floats for each axis
BLEFloatCharacteristic rollChar(ROLL_UUID, BLERead | BLENotify);
BLEFloatCharacteristic pitchChar(PITCH_UUID, BLERead | BLENotify);
BLEFloatCharacteristic yawChar(YAW_UUID, BLERead | BLENotify);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
const float dt = 0.01; // 10ms = 100Hz

// Complementary filter coefficient
const float alpha = 0.98;

void setup() {
  Serial.begin(115200);
  // while (!Serial); // Comment out for standalone operation
  
  // Initialize IMU
  if (myIMU.begin() != 0) {
    Serial.println("IMU initialization failed!");
    while (1);
  }
  Serial.println("IMU initialized");
  
  // Initialize BLE
  if (!BLE.begin()) {
    Serial.println("BLE initialization failed!");
    while (1);
  }
  
  // Set up BLE service
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
    Serial.print("Connected to: ");
    Serial.println(central.address());
    
    while (central.connected()) {
      unsigned long now = millis();
      
      if (now - lastUpdate >= 10) { // 100Hz update rate
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
  // Read raw accelerometer data
  float accelX = myIMU.readFloatAccelX();
  float accelY = myIMU.readFloatAccelY();
  float accelZ = myIMU.readFloatAccelZ();
  
  // Read raw gyroscope data (degrees per second)
  float gyroX = myIMU.readFloatGyroX();
  float gyroY = myIMU.readFloatGyroY();
  float gyroZ = myIMU.readFloatGyroZ();
  
  // Calculate roll and pitch from accelerometer (gravity vector)
  float rollAccel = atan2(accelY, accelZ);
  float pitchAccel = atan2(-accelX, sqrt(accelY * accelY + accelZ * accelZ));
  
  // Integrate gyro for yaw (no magnetometer reference)
  yaw += gyroZ * dt;
  
  // Keep yaw in -180 to 180 range
  if (yaw > 180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;
  
  // Complementary filter for roll and pitch
  roll = alpha * (roll + gyroX * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroY * dt) + (1.0 - alpha) * pitchAccel;
  
  // Convert to degrees
  roll *= RAD_TO_DEG;
  pitch *= RAD_TO_DEG;
}

void sendBleData() {
  rollChar.writeValue(roll);
  pitchChar.writeValue(pitch);
  yawChar.writeValue(yaw);
  
  // Debug output (comment out for production)
  // Serial.print(roll); Serial.print(",");
  // Serial.print(pitch); Serial.print(",");
  // Serial.println(yaw);
}
