/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Uses Bluefruit BLE stack (built into Seeed nRF52 Boards package)
 * 
 * Required libraries (Library Manager):
 * - Seeed Arduino LSM6DS3 (by Seeed Studio)
 * 
 * Board: Seeed nRF52 Boards → Seeed XIAO BLE Sense - nRF52840
 * (NOT the mbed-enabled variant)
 */

#include <bluefruit.h>
#include "LSM6DS3.h"
#include "Wire.h"

// BLE UUIDs
#define SERVICE_UUID  "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
#define ROLL_UUID     "917649A1-D98E-11E5-9EEC-0002A5D5C51B"
#define PITCH_UUID    "917649A2-D98E-11E5-9EEC-0002A5D5C51B"
#define YAW_UUID      "917649A3-D98E-11E5-9EEC-0002A5D5C51B"

// IMU
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE Service and Characteristics
BLEService        headTrackerService = BLEService(SERVICE_UUID);
BLECharacteristic rollChar           = BLECharacteristic(ROLL_UUID);
BLECharacteristic pitchChar          = BLECharacteristic(PITCH_UUID);
BLECharacteristic yawChar            = BLECharacteristic(YAW_UUID);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
const float dt = 0.01;
const float alpha = 0.98;

void blink(int times, int ms = 200) {
  for (int i = 0; i < times; i++) {
    digitalWrite(LED_BUILTIN, LOW);
    delay(ms);
    digitalWrite(LED_BUILTIN, HIGH);
    delay(ms);
  }
}

void connect_callback(uint16_t conn_handle) {
  Serial.println("Connected!");
  digitalWrite(LED_BUILTIN, LOW);
}

void disconnect_callback(uint16_t conn_handle, uint8_t reason) {
  Serial.println("Disconnected");
  digitalWrite(LED_BUILTIN, HIGH);
  Bluefruit.Advertising.start(0);
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
    Serial.println("IMU init FAILED!");
    while (1) { blink(5, 100); delay(500); }
  }
  Serial.println("IMU OK");
  blink(1);

  // Init BLE
  Serial.println("Initializing BLE...");
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("HeadTracker");
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  // Setup service
  headTrackerService.begin();

  // Roll characteristic
  rollChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  rollChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  rollChar.setFixedLen(4); // float = 4 bytes
  rollChar.begin();

  // Pitch characteristic
  pitchChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  pitchChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  pitchChar.setFixedLen(4);
  pitchChar.begin();

  // Yaw characteristic
  yawChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  yawChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  yawChar.setFixedLen(4);
  yawChar.begin();

  // Start advertising
  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(headTrackerService);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  Serial.println("Advertising as 'HeadTracker'");
  blink(3);
}

void loop() {
  unsigned long now = millis();
  if (now - lastUpdate >= 10) {
    lastUpdate = now;
    updateOrientation();

    if (Bluefruit.connected()) {
      sendBleData();
    }
  }
}

void updateOrientation() {
  float accelX = myIMU.readFloatAccelX();
  float accelY = myIMU.readFloatAccelY();
  float accelZ = myIMU.readFloatAccelZ();
  float gyroX  = myIMU.readFloatGyroX();
  float gyroY  = myIMU.readFloatGyroY();
  float gyroZ  = myIMU.readFloatGyroZ();

  // accel angles in degrees
  float rollAccel  = atan2(accelY, accelZ) * RAD_TO_DEG;
  float pitchAccel = atan2(-accelX, sqrt(accelY * accelY + accelZ * accelZ)) * RAD_TO_DEG;

  // gyro is deg/s, dt is seconds — everything stays in degrees
  yaw += gyroZ * dt;
  if (yaw >  180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;

  roll  = alpha * (roll  + gyroX * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroY * dt) + (1.0 - alpha) * pitchAccel;
}

void sendBleData() {
  rollChar.notify((uint8_t*)&roll, 4);
  pitchChar.notify((uint8_t*)&pitch, 4);
  yawChar.notify((uint8_t*)&yaw, 4);

  Serial.print("R:"); Serial.print(roll, 1);
  Serial.print(" P:"); Serial.print(pitch, 1);
  Serial.print(" Y:"); Serial.println(yaw, 1);
}
