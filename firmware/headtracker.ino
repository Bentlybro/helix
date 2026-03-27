/*
 * HeadTracker Firmware
 * XIAO nRF52840 Sense - BLE IMU Streamer
 * 
 * Streams orientation and raw IMU data over BLE
 */

#include <ArduinoBLE.h>
#include <LSM6DS3.h>
#include <Wire.h>

// BLE Service and Characteristic UUIDs
#define SERVICE_UUID        "917649A0-D98E-11E5-9EEC-0002A5D5C51B"
#define ORIENTATION_UUID    "917649A1-D98E-11E5-9EEC-0002A5D5C51B"
#define RAW_IMU_UUID        "917649A2-D98E-11E5-9EEC-0002A5D5C51B"

// IMU
LSM6DS3 imu(I2C_MODE, 0x6A);

// BLE
BLEService headTrackerService(SERVICE_UUID);
BLECharacteristic orientationChar(ORIENTATION_UUID, BLERead | BLENotify, 12); // 3 floats
BLECharacteristic rawImuChar(RAW_IMU_UUID, BLERead | BLENotify, 24); // 6 floats

// Sensor fusion state
float roll = 0.0, pitch = 0.0, yaw = 0.0;
unsigned long lastUpdate = 0;
const float dt = 0.01; // 100Hz

// Complementary filter coefficient
const float alpha = 0.98;

void setup() {
  Serial.begin(115200);
  //while (!Serial); // Uncomment for debug, comment for standalone
  
  // Init IMU
  if (imu.begin() != 0) {
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
  
  headTrackerService.addCharacteristic(orientationChar);
  headTrackerService.addCharacteristic(rawImuChar);
  
  BLE.addService(headTrackerService);
  
  orientationChar.writeValue(0.0f);
  rawImuChar.writeValue(0.0f);
  
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
  // Read raw data
  float accelX = imu.readFloatAccelX();
  float accelY = imu.readFloatAccelY();
  float accelZ = imu.readFloatAccelZ();
  
  float gyroX = imu.readFloatGyroX();
  float gyroY = imu.readFloatGyroY();
  float gyroZ = imu.readFloatGyroZ();
  
  // Convert gyro from deg/s to rad/s
  float gyroXRad = gyroX * DEG_TO_RAD;
  float gyroYRad = gyroY * DEG_TO_RAD;
  float gyroZRad = gyroZ * DEG_TO_RAD;
  
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
  // Pack orientation (roll, pitch, yaw) as floats
  float orientation[3] = { roll, pitch, yaw };
  orientationChar.writeValue((byte*)orientation, 12);
  
  // Pack raw IMU (accel XYZ, gyro XYZ)
  float rawImu[6] = {
    imu.readFloatAccelX(),
    imu.readFloatAccelY(),
    imu.readFloatAccelZ(),
    imu.readFloatGyroX(),
    imu.readFloatGyroY(),
    imu.readFloatGyroZ()
  };
  rawImuChar.writeValue((byte*)rawImu, 24);
}
