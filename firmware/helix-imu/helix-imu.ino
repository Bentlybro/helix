/*
 * Helix Firmware — helix-imu
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
#define SERVICE_UUID      "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
#define ORIENTATION_UUID  "917649A1-D98E-11E5-9EEC-0002A5D5C51B"
#define BATTERY_UUID      "917649A2-D98E-11E5-9EEC-0002A5D5C51B"
#define TEMPERATURE_UUID  "917649A3-D98E-11E5-9EEC-0002A5D5C51B"

// Battery pins (XIAO nRF52840 - non-mbed package)
// PIN_VBAT and VBAT_ENABLE are defined in the Seeed board variant
#ifndef PIN_VBAT
  #define PIN_VBAT A0   // fallback
#endif
#ifndef VBAT_ENABLE_PIN
  #define VBAT_ENABLE_PIN 14
#endif

// IMU
LSM6DS3 myIMU(I2C_MODE, 0x6A);

// BLE
BLEService        helixService    = BLEService(SERVICE_UUID);
BLECharacteristic orientationChar = BLECharacteristic(ORIENTATION_UUID);
BLECharacteristic batteryChar     = BLECharacteristic(BATTERY_UUID);
BLECharacteristic temperatureChar = BLECharacteristic(TEMPERATURE_UUID);

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate     = 0;
unsigned long lastSerial     = 0;
unsigned long lastBattery    = 0;
unsigned long lastTemperature = 0;
const float alpha = 0.98;

// Battery state
uint8_t batteryPercent = 0;
float temperature = 0.0;

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

uint8_t readBatteryPercent() {
  // XIAO nRF52840 battery read — Seeed documented method
  // PIN_VBAT (P0_31) via 1/2 voltage divider, 3.6V internal ref
  analogReadResolution(12);

  // Oversample for stability
  int raw = 0;
  for (int i = 0; i < 8; i++) {
    raw += analogRead(PIN_VBAT);
    delay(1);
  }
  raw /= 8;

  // 3.6V internal reference, 12-bit, divider x2
  float voltage = (float)raw * 3.6f * 2.0f / 4096.0f;

  Serial.print("BAT raw:"); Serial.print(raw);
  Serial.print(" voltage:"); Serial.println(voltage, 3);

  // LiPo: 3.2V = 0%, 4.2V = 100%
  float percent = (voltage - 3.2f) / (4.2f - 3.2f) * 100.0f;
  percent = constrain(percent, 0.0f, 100.0f);

  return (uint8_t)percent;
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);

  // Disable battery read circuit by default (saves power)
  pinMode(VBAT_ENABLE_PIN, OUTPUT);
  digitalWrite(VBAT_ENABLE_PIN, HIGH);

  Serial.begin(115200);
  unsigned long t = millis();
  while (!Serial && millis() - t < 3000);

  Serial.println("Helix booting...");
  blink(2);

  // Init IMU
  Serial.println("Initializing IMU...");
  if (myIMU.begin() != 0) {
    Serial.println("IMU init FAILED!");
    while (1) { blink(5, 100); delay(500); }
  }
  Serial.println("IMU OK");
  blink(1);

  // Initial battery read
  batteryPercent = readBatteryPercent();
  Serial.print("Battery: "); Serial.print(batteryPercent); Serial.println("%");

  // Init BLE
  Bluefruit.begin();
  Bluefruit.setTxPower(4);
  Bluefruit.setName("Helix");
  Bluefruit.Periph.setConnectCallback(connect_callback);
  Bluefruit.Periph.setDisconnectCallback(disconnect_callback);

  helixService.begin();

  // Orientation: 12 bytes (roll, pitch, yaw as float32)
  orientationChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  orientationChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  orientationChar.setFixedLen(12);
  orientationChar.begin();

  // Battery: 1 byte (0-100%)
  batteryChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  batteryChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  batteryChar.setFixedLen(1);
  batteryChar.begin();
  batteryChar.write8(batteryPercent);

  // Temperature: 4 bytes (float32, °C)
  temperatureChar.setProperties(CHR_PROPS_READ | CHR_PROPS_NOTIFY);
  temperatureChar.setPermission(SECMODE_OPEN, SECMODE_NO_ACCESS);
  temperatureChar.setFixedLen(4);
  temperatureChar.begin();
  temperatureChar.write((uint8_t*)&temperature, 4);

  // Minimum connection interval for lowest latency
  Bluefruit.Periph.setConnInterval(6, 12);

  Bluefruit.Advertising.addFlags(BLE_GAP_ADV_FLAGS_LE_ONLY_GENERAL_DISC_MODE);
  Bluefruit.Advertising.addTxPower();
  Bluefruit.Advertising.addService(helixService);
  Bluefruit.ScanResponse.addName();
  Bluefruit.Advertising.restartOnDisconnect(true);
  Bluefruit.Advertising.setInterval(32, 244);
  Bluefruit.Advertising.setFastTimeout(30);
  Bluefruit.Advertising.start(0);

  Serial.println("Advertising as 'Helix'");
  blink(3);
}

void loop() {
  unsigned long now = millis();

  // IMU + BLE at 100Hz
  if (now - lastUpdate >= 10) {
    float dt = (now - lastUpdate) / 1000.0f;
    lastUpdate = now;
    updateOrientation(dt);
    if (Bluefruit.connected()) {
      sendBleData();
    }
  }

  // Battery read every 30 seconds
  if (now - lastBattery >= 30000) {
    lastBattery = now;
    batteryPercent = readBatteryPercent();
    if (Bluefruit.connected()) {
      batteryChar.notify8(batteryPercent);
    }
  }

  // Temperature read every 5 seconds
  if (now - lastTemperature >= 5000) {
    lastTemperature = now;
    temperature = myIMU.readTempC();
    if (Bluefruit.connected()) {
      temperatureChar.notify((uint8_t*)&temperature, 4);
    }
  }

  // Serial debug at 5Hz
  if (now - lastSerial >= 200) {
    lastSerial = now;
    Serial.print("R:"); Serial.print(roll, 1);
    Serial.print(" P:"); Serial.print(pitch, 1);
    Serial.print(" Y:"); Serial.print(yaw, 1);
    Serial.print(" BAT:"); Serial.print(batteryPercent);
    Serial.print("% TEMP:"); Serial.print(temperature, 1); Serial.println("C");
  }
}

// ============================================================
// AXIS REMAP — adjust if orientation feels wrong when worn
//
// The XIAO sits behind/on the ear, so physical axes may not
// match head axes. Change the signs or swap X/Y/Z here.
//
// Current mapping assumes USB port facing DOWN on right ear:
//   Head YAW   (left/right turn) = IMU gyro Y
//   Head PITCH (nod up/down)     = IMU gyro X
//   Head ROLL  (tilt ear to shoulder) = IMU gyro Z
//
// If axes feel swapped, change which gyro/accel axis maps where.
// If direction is inverted, flip the sign (e.g. -gyroX).
// ============================================================
void updateOrientation(float dt) {
  float aX = myIMU.readFloatAccelX();
  float aY = myIMU.readFloatAccelY();
  float aZ = myIMU.readFloatAccelZ();
  float gX = myIMU.readFloatGyroX();
  float gY = myIMU.readFloatGyroY();
  float gZ = myIMU.readFloatGyroZ();

  // Remap to head space — tweak these if axes feel wrong
  float accelRoll  = aY;   // which accel axis drives roll
  float accelPitch = aX;   // which accel axis drives pitch
  float accelRef   = aZ;   // reference axis (gravity)
  float gyroRoll   = gZ;   // which gyro axis drives roll
  float gyroPitch  = gX;   // which gyro axis drives pitch
  float gyroYaw    = gY;   // which gyro axis drives yaw

  float rollAccel  = atan2(accelRoll, accelRef) * RAD_TO_DEG;
  float pitchAccel = atan2(-accelPitch, sqrt(accelRoll * accelRoll + accelRef * accelRef)) * RAD_TO_DEG;

  yaw += gyroYaw * dt;
  if (yaw >  180.0) yaw -= 360.0;
  if (yaw < -180.0) yaw += 360.0;

  roll  = alpha * (roll  + gyroRoll  * dt) + (1.0 - alpha) * rollAccel;
  pitch = alpha * (pitch + gyroPitch * dt) + (1.0 - alpha) * pitchAccel;
}

void sendBleData() {
  float data[3] = { roll, pitch, yaw };
  orientationChar.notify((uint8_t*)data, 12);
}
