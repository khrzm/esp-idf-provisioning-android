# Debug Steps for MQTT Empty Credentials

## 1. Check Android Logcat
Run: `adb logcat -s WiFiConfigActivity ProvisionActivity`

You should see:
```
D/WiFiConfigActivity: MQTT credentials loaded from AppConstants:
D/WiFiConfigActivity:   Broker: mqtt://...
D/WiFiConfigActivity:   Username: ...
D/WiFiConfigActivity:   Password: ***
```

## 2. Expected Behavior
With current AppConstants placeholders, you should see **ERROR DIALOG** saying:
"MQTT credentials are not configured in AppConstants"

## 3. If you DON'T see the validation error dialog:
- You're running an old APK (before our changes)
- Solution: Uninstall app completely, rebuild, and reinstall

## 4. If you DO see the validation error but continue anyway:
- Something is wrong with the validation logic

## 5. What values to use:
Replace lines 48-50 in AppConstants.kt with your REAL MQTT broker:
```kotlin
@JvmField val DEFAULT_MQTT_BROKER: String = "mqtt://test.mosquitto.org:1883"  // example public broker
@JvmField val DEFAULT_MQTT_USERNAME: String = "test_user"
@JvmField val DEFAULT_MQTT_PASSWORD: String = "test_pass"
```
