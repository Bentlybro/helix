/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Required libraries:
 * - ArduinoBLE (by Arduino) - install via Library Manager
 */

#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

// LED pin (XIAO nRF52840 has built-in LED on pin 13)
#define LED_PIN LED_BUILTIN

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
const float dt = 0.01;
const float alpha = 0.98;

void blink(int times, int ms = 200) {
  for (int i = 0; i < times; i++) {
    digitalWrite(LED_PIN, LOW);  // LOW = on for XIAO
    delay(ms);
    digitalWrite(LED_PIN, HIGH);
    delay(ms);
  }
}

void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH); // off

  Serial.begin(115200);

  // Wait up to 3s for serial monitor, then continue anyway
  unsigned long t = millis();
  while (!Serial && millis() - t < 3000);

  Serial.println("HeadTracker booting...");
  blink(2); // 2 blinks = started

  // Init IMU
  Serial.println("Initializing IMU...");
  if (!IMU.begin()) {
    Serial.println("IMU init FAILED!");
    // Rapid blink = error
    while (1) { blink(5, 100); delay(500); }
  }
  Serial.println("IMU OK");
  blink(1); // 1 blink = IMU good

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
  blink(3); // 3 blinks = ready and advertising
}

void loop() {
  BLEDevice central = BLE.central();

  if (central) {
    Serial.print("Connected: ");
    Serial.println(central.address());
    digitalWrite(LED_PIN, LOW); // solid on = connected

    while (central.connected()) {
      unsigned long now = millis();
      if (now - lastUpdate >= 10) {
        lastUpdate = now;
        updateOrientation();
        sendBleData();
      }
      BLE.poll();
    }

    digitalWrite(LED_PIN, HIGH); // off = disconnected
    Serial.println("Disconnected");
  }

  BLE.poll();
}

void updateOrientation() {
  float accelX, accelY, accelZ;
  float gyroX, gyroY, gyroZ;

  if (!IMU.readAcceleration(accelX, accelY, accelZ)) return;
  if (!IMU.readGyroscope(gyroX, gyroY, gyroZ)) return;

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
