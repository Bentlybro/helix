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

// BLE UUIDs - single packed characteristic (roll, pitch, yaw as 3 floats = 12 bytes)
#define SERVICE_UUID      "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
#define ORIENTATION_UUID  "917649A1-D98E-11E5-9EEC-0002A5D5C51B"

// IMU
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE - single characteristic with all 3 values packed
BLEService        headTrackerService  = BLEService(SERVICE_UUID);
BLECharacteristic orientationChar     = BLECharacteristic(ORIENTATION_UUID);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
unsigned long lastSerial = 0;
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
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("HeadTracker");
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  // Single packed orientation characteristic (12 bytes: roll, pitch, yaw)
  headTrackerService.begin();
  orientationChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  orientationChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  orientationChar.setFixedLen(12);
  orientationChar.begin();

  // Minimum connection interval for lowest latency
  Bluefruit.Periph.setConnInterval(6, 12); // 7.5ms - 15ms

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

  // IMU + BLE at 100Hz (every 10ms)
  if (now - lastUpdate >= 10) {
    float dt = (now - lastUpdate) / 1000.0f; // real elapsed time in seconds
    lastUpdate = now;
    updateOrientation(dt);

    if (Bluefruit.connected()) {
      sendBleData();
    }
  }

  // Serial debug at 5Hz (every 200ms)
  if (now - lastSerial >= 200) {
    lastSerial = now;
    Serial.print("R:"); Serial.print(roll, 1);
    Serial.print(" P:"); Serial.print(pitch, 1);
    Serial.print(" Y:"); Serial.println(yaw, 1);
  }
}

void updateOrientation(float dt) {
  float accelX = myIMU.readFloatAccelX();
  float accelY = myIMU.readFloatAccelY();
  float accelZ = myIMU.readFloatAccelZ();
  float gyroX  = myIMU.readFloatGyroX();
  float gyroY  = myIMU.readFloatGyroY();
  float gyroZ  = myIMU.readFloatGyroZ();

  // Accel angles in degrees
  float rollAccel  = atan2(accelY, accelZ) * RAD_TO_DEG;
  float pitchAccel = atan2(-accelX, sqrt(accelY * accelY + accelZ * accelZ)) * RAD_TO_DEG;

  // Integrate gyro for yaw (deg/s * s = deg)
  yaw += gyroZ * dt;
  if (yaw >  180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;

  // Complementary filter — everything in degrees
  roll  = alpha * (roll  + gyroX * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroY * dt) + (1.0 - alpha) * pitchAccel;
}

void sendBleData() {
  // Pack roll, pitch, yaw as 3 floats (12 bytes) — single atomic notify
  float data[3] = { roll, pitch, yaw };
  orientationChar.notify((uint8_t*)data, 12);
}
