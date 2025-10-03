# BLE Android Provisioning Flow for ESP32

## Overview
This is a BLE-based WiFi + MQTT provisioning system for ESP32 devices using the Espressif Android Provisioning Library.

## Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                   ANDROID APP FLOW                           │
└─────────────────────────────────────────────────────────────┘

1. APP LAUNCH
   ├─> BLEProvisionLanding Activity
   ├─> Check BLE support & permissions
   └─> Enable Bluetooth if needed

2. DEVICE DISCOVERY (BLE Scan)
   ├─> Scan for BLE devices with prefix "HEATSIG_" or custom
   ├─> Display list of discovered ESP32 devices
   └─> User selects target device

3. BLE CONNECTION
   ├─> Connect to selected ESP32 via BLE GATT
   ├─> Establish secure session (Security 0/1/2)
   ├─> Exchange capabilities (wifi_scan, custom endpoints, etc.)
   └─> Connection timeout: 20 seconds

4. WiFi CONFIGURATION
   ├─> WiFiScanActivity: Scan available WiFi networks via ESP32
   ├─> User selects WiFi network
   ├─> User enters WiFi password
   └─> Pass credentials to ProvisionActivity

5. PROVISIONING (ProvisionActivity)
   ┌─────────────────────────────────────────────────┐
   │ Step 1: MQTT Configuration (Custom Endpoint)    │
   ├─────────────────────────────────────────────────┤
   │ • Wait 1s for BLE to stabilize                  │
   │ • Send JSON to "/config" endpoint:              │
   │   {                                             │
   │     "mqtt_uri": "mqtt://broker:1883",          │
   │     "mqtt_username": "user",                    │
   │     "mqtt_password": "pass"                     │
   │   }                                             │
   │ • Retry 3 times with 3s delay if failed        │
   │ • ESP32 responds with device_id & pairing_key   │
   └─────────────────────────────────────────────────┘
   
   ┌─────────────────────────────────────────────────┐
   │ Step 2: Display Device Info                     │
   ├─────────────────────────────────────────────────┤
   │ • Show dialog with:                             │
   │   - Device ID (MAC address)                     │
   │   - Pairing Key                                 │
   │ • User clicks OK to continue                    │
   └─────────────────────────────────────────────────┘
   
   ┌─────────────────────────────────────────────────┐
   │ Step 3: WiFi Provisioning (Standard Protocol)   │
   ├─────────────────────────────────────────────────┤
   │ • Send WiFi SSID + Password via protobuf        │
   │ • ESP32 connects to WiFi                        │
   │ • ESP32 returns connection status               │
   │ • Success → BLE disconnects                     │
   └─────────────────────────────────────────────────┘

6. COMPLETION
   ├─> Show success message
   ├─> Disconnect BLE
   └─> ESP32 now has WiFi + MQTT configured

┌─────────────────────────────────────────────────────────────┐
│                    ESP32 FIRMWARE FLOW                       │
└─────────────────────────────────────────────────────────────┘

1. STARTUP
   ├─> Check if already provisioned (NVS storage)
   ├─> If not provisioned → Start BLE provisioning
   └─> Advertise as "HEATSIG_XXYY" (last 2 MAC bytes)

2. BLE PROVISIONING MODE
   ├─> Start wifi_prov_mgr with BLE transport
   ├─> Security: SEC0 (no encryption for simplicity)
   ├─> Standard UUID: 021a9004-0382-4aea-bff4-6b3f1c5adfb4
   └─> Register custom endpoints: "/config", "/info"

3. CUSTOM ENDPOINT: /config
   ├─> Receives JSON with MQTT credentials
   ├─> Validates: mqtt_uri, mqtt_username, mqtt_password not empty
   ├─> Saves to NVS storage
   ├─> Generates device_id from MAC (format: aa:bb:cc:dd:ee:ff)
   ├─> Generates pairing_key (format: KEY_XXYYZZ)
   └─> Responds with: {"status":"success", "device_id":"...", "pairing_key":"..."}

4. WIFI PROVISIONING
   ├─> Receives WiFi credentials via standard protobuf
   ├─> Attempts to connect to WiFi
   └─> Returns success/failure status

5. POST-PROVISIONING
   ├─> Connects to WiFi
   ├─> Connects to MQTT broker using saved credentials
   ├─> Starts normal operation
   └─> BLE provisioning exits

┌─────────────────────────────────────────────────────────────┐
│                  TECHNICAL DETAILS                           │
└─────────────────────────────────────────────────────────────┘

BLE COMMUNICATION:
- Transport: BLE GATT
- Service UUID: 021a9004-0382-4aea-bff4-6b3f1c5adfb4 (Espressif standard)
- Security: SEC0 (no encryption), SEC1 (PoP), or SEC2 (SRP6a)
- MTU: 256 bytes
- Custom endpoints use JSON over BLE

DATA FORMAT:
- WiFi credentials: Protobuf (standard Espressif format)
- MQTT credentials: JSON (custom implementation)
- Device info: JSON response

RETRY LOGIC:
- MQTT send: 3 retries with 3-second intervals
- Each attempt has 30-second timeout
- Automatic retry on "Write to BLE failed" error

CREDENTIALS STORAGE (ESP32):
- MQTT_URI → NVS key "MQTT_URI"
- MQTT_USERNAME → NVS key "HOST_NAME"
- MQTT_PASSWORD → NVS key "HOST_PASSWORD"
- WiFi SSID & Password → Standard wifi_prov_mgr storage

┌─────────────────────────────────────────────────────────────┐
│                    KEY CLASSES                               │
└─────────────────────────────────────────────────────────────┘

ANDROID:
- BLEProvisionLanding: Scan & connect to BLE devices
- WiFiScanActivity: Scan WiFi networks via ESP32
- WiFiConfigActivity: Enter WiFi password + load MQTT from AppConstants
- ProvisionActivity: Send MQTT config, receive device info, provision WiFi
- ESPProvisionManager: Core provisioning library wrapper
- AppConstants: Stores hardcoded MQTT credentials

ESP32:
- prov_controller.c: Main provisioning controller
- config_handler(): Handles /config endpoint (MQTT creds)
- info_handler(): Handles /info endpoint (device info)
- wifi_prov_mgr: ESP-IDF provisioning manager
- credentials.c: NVS storage helpers

┌─────────────────────────────────────────────────────────────┐
│                    ERROR HANDLING                            │
└─────────────────────────────────────────────────────────────┘

COMMON ISSUES:
1. "Write to BLE failed" → Retry 3 times (BLE not stable)
2. Empty MQTT credentials → Validation error, must configure AppConstants
3. BLE disconnection → Show error dialog, return to device list
4. WiFi connection failed → Show error with reason (auth failed, network not found)
5. Timeout (30s) → Retry or show error dialog

SUCCESS INDICATORS:
✓ MQTT config sent successfully
✓ Device info received (device_id, pairing_key)
✓ WiFi provisioning success
✓ BLE gracefully disconnected
