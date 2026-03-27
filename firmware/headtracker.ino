/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Required libraries (install via Library Manager):
 * - ArduinoBLE (by Arduino)
 * - Seeed Arduino LSM6DS3 (by Seeed Studio)
 * 
 * Board: "Seeed XIAO BLE Sense - nRF52840" (NOT the plain "Seeed XIAO BLE")
 */

#include <ArduinoBLE.h>
#include "LSM6DS3.h"
#include "Wire.h"

// BLE UUIDs
const char* SERVICE_UUID = "917649A0-D98E-11E5-9EEC-0002A5D5C51B";
const char* ROLL_UUID    = "917649A1-D98E-11E5-9EEC-0002A5D5C51B";
const char* PITCH_UUID   = "917649A2-D98E-11E5-9EEC-0002A5D5C51B";
const char* YAW_UUID     = "917649A3-D98E-11E5-9EEC-0002A5D5C51B";

// Create IMU instance - Seeed LSM6DS3 library
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE Service and Characteristics
BLEService headTrackerService(SERVICE_UUID);
BLEFloatCharacteristic rollChar(ROLL_UUID,  BLERead | BLENotify);
BLEFloatCharacteristic pitchChar(PITCH_UUID, BLERead | BLENotify);
BLEFloatCharacteristic yawChar(YAW_UUID,   BLERead | BLENotify);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
const float dt = 0.01;
const float alpha = 0.98;

void blink(int times, int ms = 200) {
  for (int i = 0; i < times; i++) {
    digitalWrite(LED_BUILTIN, LOW);  // LOW = on for XIAO
    delay(ms);
    digitalWrite(LED_BUILTIN, HIGH);
    delay(ms);
  }
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);

  Serial.begin(115200);
  unsigned long t = millis();
  while (!Serial && millis() - t < 3000);

  Serial.println("HeadTracker booting...");
  blink(2);

  // Init IMU
  Serial.println("Initializing IMU...");
  if (myIMU.begin() != 0) {
    Serial.println("IMU init FAILED! Check board selection and library.");
    while (1) { blink(5, 100); delay(500); }
  }
  Serial.println("IMU OK");
  blink(1);

  // Init BLE
  Serial.println("Initializing BLE...");
  if (!BLE.begin()) {
    Serial.println("BLE init FAILED!");
    while (1) { blink(3, 100); delay(500); }
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
  Serial.println("Advertising as 'HeadTracker'");
  blink(3);
}

void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("Connected: ");
    Serial.println(central.address());
    digitalWrite(LED_BUILTIN, LOW);

    while (central.connected()) {
      unsigned long now = millis();
      if (now - lastUpdate >= 10) {
        lastUpdate = now;
        updateOrientation();
        sendBleData();
      }
      BLE.poll();
    }

    digitalWrite(LED_BUILTIN, HIGH);
    Serial.println("Disconnected");
  }

  BLE.poll();
}

void updateOrientation() {
  float accelX = myIMU.readFloatAccelX();
  float accelY = myIMU.readFloatAccelY();
  float accelZ = myIMU.readFloatAccelZ();
  float gyroX  = myIMU.readFloatGyroX();
  float gyroY  = myIMU.readFloatGyroY();
  float gyroZ  = myIMU.readFloatGyroZ();

  float rollAccel  = atan2(accelY, accelZ);
  float pitchAccel = atan2(-accelX, sqrt(accelY * accelY + accelZ * accelZ));

  yaw += gyroZ * dt;
  if (yaw >  180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;

  roll  = alpha * (roll  + gyroX * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroY * dt) + (1.0 - alpha) * pitchAccel;

  roll  *= RAD_TO_DEG;
  pitch *= RAD_TO_DEG;
}

void sendBleData() {
  rollChar.writeValue(roll);
  pitchChar.writeValue(pitch);
  yawChar.writeValue(yaw);

  Serial.print("R:"); Serial.print(roll, 1);
  Serial.print(" P:"); Serial.print(pitch, 1);
  Serial.print(" Y:"); Serial.println(yaw, 1);
}
