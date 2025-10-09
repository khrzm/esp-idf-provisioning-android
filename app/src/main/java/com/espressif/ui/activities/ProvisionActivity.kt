// Copyright 2025 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.espressif.AppConstants
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPConstants.ProvisionFailureReason
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.provisioning.listeners.ProvisionListener
import com.espressif.provisioning.listeners.ResponseListener
import com.espressif.wifi_provisioning.R
import com.espressif.wifi_provisioning.databinding.ActivityProvisionBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class ProvisionActivity : AppCompatActivity() {

    companion object {
        private val TAG: String = ProvisionActivity::class.java.simpleName
    }

    private lateinit var binding: ActivityProvisionBinding
    private lateinit var provisionManager: ESPProvisionManager

    private var ssidValue: String? = null
    private var passphraseValue: String? = ""
    private var dataset: String? = null
    private var mqttBroker: String? = null
    private var mqttUsername: String? = null
    private var mqttPassword: String? = null
    private var deviceId: String? = null
    private var pairingKey: String? = null
    private var isProvisioningCompleted = false
    private var mqttTimeoutHandler: Handler? = null
    private var mqttTimeoutRunnable: Runnable? = null
    private var mqttRetryCount = 0
    private val MQTT_MAX_RETRIES = 3
    private val MQTT_RETRY_DELAY_MS = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProvisionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intent = intent
        ssidValue = intent.getStringExtra(AppConstants.KEY_WIFI_SSID)
        passphraseValue = intent.getStringExtra(AppConstants.KEY_WIFI_PASSWORD)
        dataset = intent.getStringExtra(AppConstants.KEY_THREAD_DATASET)
        mqttBroker = intent.getStringExtra(AppConstants.KEY_MQTT_BROKER)
        mqttUsername = intent.getStringExtra(AppConstants.KEY_MQTT_USERNAME)
        mqttPassword = intent.getStringExtra(AppConstants.KEY_MQTT_PASSWORD)

        Log.d(TAG, "=== MQTT Credentials Received from Intent ===")
        Log.d(TAG, "MQTT Broker: '$mqttBroker'")
        Log.d(TAG, "MQTT Username: '$mqttUsername'")
        Log.d(TAG, "MQTT Password: ${if (!mqttPassword.isNullOrEmpty()) "***" else "NULL/EMPTY"}")
        Log.d(TAG, "MQTT Broker length: ${mqttBroker?.length ?: 0}")
        Log.d(TAG, "MQTT Username length: ${mqttUsername?.length ?: 0}")
        Log.d(TAG, "MQTT Password length: ${mqttPassword?.length ?: 0}")

        provisionManager = ESPProvisionManager.getInstance(applicationContext)
        initViews()
        EventBus.getDefault().register(this)

        Log.d(TAG, "Selected AP -$ssidValue")
        Log.d(TAG, "MQTT Broker provided: ${!mqttBroker.isNullOrEmpty()}")
        showLoading()

        // MQTT credentials are REQUIRED - must be provided
        if (mqttBroker.isNullOrEmpty()) {
            showMqttError("MQTT credentials are required but not provided")
            return
        }

        // Send MQTT config first (REQUIRED step) - wait 1 second for BLE connection to stabilize
        mqttRetryCount = 0
        Handler(Looper.getMainLooper()).postDelayed({
            sendMqttConfig()
        }, 1000)
    }

    override fun onBackPressed() {
        provisionManager.espDevice.disconnectDevice()
        super.onBackPressed()
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: DeviceConnectionEvent) {
        Log.d(TAG, "On Device Connection Event RECEIVED : " + event.eventType)

        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_DISCONNECTED -> if (!isFinishing && !isProvisioningCompleted) {
                showAlertForDeviceDisconnected()
            }
        }
    }

    private val okBtnClickListener = View.OnClickListener {
        provisionManager.espDevice?.disconnectDevice()
        finish()
    }

    private fun initViews() {
        setToolbar()
        binding.btnOk.ivArrow.visibility = View.GONE
        binding.btnOk.textBtn.setText(R.string.btn_ok)
        binding.btnOk.layoutBtn.setOnClickListener(okBtnClickListener)
    }

    private fun setToolbar() {
        setSupportActionBar(binding.titleBar.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(false)
        supportActionBar!!.setDisplayShowHomeEnabled(false)
        supportActionBar!!.setTitle(R.string.title_activity_provisioning)
    }

    private fun doProvisioning() {
        binding.ivTick3.visibility = View.GONE
        binding.provProgress3.visibility = View.VISIBLE

        if (!TextUtils.isEmpty(dataset)) {
            provisionManager.espDevice.provision(dataset, object : ProvisionListener {
                override fun createSessionFailed(e: Exception) {
                    runOnUiThread {
                        binding.ivTick3.setImageResource(R.drawable.ic_error)
                        binding.ivTick3.visibility = View.VISIBLE
                        binding.provProgress3.visibility = View.GONE
                        binding.tvProvError3.visibility = View.VISIBLE
                        binding.tvProvError3.setText(R.string.error_session_creation)
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                }

                override fun wifiConfigSent() {
                    runOnUiThread {
                        binding.ivTick3.setImageResource(R.drawable.ic_checkbox_on)
                        binding.ivTick3.visibility = View.VISIBLE
                        binding.provProgress3.visibility = View.GONE
                        binding.ivTick4.visibility = View.GONE
                        binding.provProgress4.visibility = View.VISIBLE
                    }
                }

                override fun wifiConfigFailed(e: Exception) {
                    runOnUiThread {
                        binding.ivTick3.setImageResource(R.drawable.ic_error)
                        binding.ivTick3.visibility = View.VISIBLE
                        binding.provProgress3.visibility = View.GONE
                        binding.tvProvError3.visibility = View.VISIBLE
                        binding.tvProvError3.setText(R.string.error_prov_thread_step_1)
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                }

                override fun wifiConfigApplied() {
                    runOnUiThread {
                        binding.ivTick4.setImageResource(R.drawable.ic_checkbox_on)
                        binding.ivTick4.visibility = View.VISIBLE
                        binding.provProgress4.visibility = View.GONE
                        binding.ivTick5.visibility = View.GONE
                        binding.provProgress5.visibility = View.VISIBLE
                    }
                }

                override fun wifiConfigApplyFailed(e: Exception) {
                    runOnUiThread {
                        binding.ivTick4.setImageResource(R.drawable.ic_error)
                        binding.ivTick4.visibility = View.VISIBLE
                        binding.provProgress4.visibility = View.GONE
                        binding.tvProvError4.visibility = View.VISIBLE
                        binding.tvProvError4.setText(R.string.error_prov_thread_step_2)
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                }

                override fun provisioningFailedFromDevice(failureReason: ProvisionFailureReason) {
                    runOnUiThread {
                        when (failureReason) {
                            ProvisionFailureReason.AUTH_FAILED -> binding.tvProvError5.setText(R.string.error_dataset_invalid)
                            ProvisionFailureReason.NETWORK_NOT_FOUND -> binding.tvProvError5.setText(
                                R.string.error_network_not_found
                            )

                            ProvisionFailureReason.DEVICE_DISCONNECTED, ProvisionFailureReason.UNKNOWN -> binding.tvProvError5.setText(
                                R.string.error_prov_step_3
                            )
                        }
                        binding.ivTick5.setImageResource(R.drawable.ic_error)
                        binding.ivTick5.visibility = View.VISIBLE
                        binding.provProgress5.visibility = View.GONE
                        binding.tvProvError5.visibility = View.VISIBLE
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                }

                override fun deviceProvisioningSuccess() {
                    runOnUiThread {
                        isProvisioningCompleted = true
                        binding.ivTick5.setImageResource(R.drawable.ic_checkbox_on)
                        binding.ivTick5.visibility = View.VISIBLE
                        binding.provProgress5.visibility = View.GONE
                        hideLoading()
                    }
                }

                override fun onProvisioningFailed(e: Exception) {
                    runOnUiThread {
                        binding.ivTick5.setImageResource(R.drawable.ic_error)
                        binding.ivTick5.visibility = View.VISIBLE
                        binding.provProgress5.visibility = View.GONE
                        binding.tvProvError5.visibility = View.VISIBLE
                        binding.tvProvError5.setText(R.string.error_prov_step_3)
                        binding.tvProvError.visibility = View.VISIBLE
                        hideLoading()
                    }
                }
            })
        } else {
            provisionManager.espDevice.provision(
                ssidValue,
                passphraseValue,
                object : ProvisionListener {
                    override fun createSessionFailed(e: Exception) {
                        runOnUiThread {
                            binding.ivTick3.setImageResource(R.drawable.ic_error)
                            binding.ivTick3.visibility = View.VISIBLE
                            binding.provProgress3.visibility = View.GONE
                            binding.tvProvError3.visibility = View.VISIBLE
                            binding.tvProvError3.setText(R.string.error_session_creation)
                            binding.tvProvError.visibility = View.VISIBLE
                            hideLoading()
                        }
                    }

                    override fun wifiConfigSent() {
                        runOnUiThread {
                            binding.ivTick3.setImageResource(R.drawable.ic_checkbox_on)
                            binding.ivTick3.visibility = View.VISIBLE
                            binding.provProgress3.visibility = View.GONE
                            binding.ivTick4.visibility = View.GONE
                            binding.provProgress4.visibility = View.VISIBLE
                        }
                    }

                    override fun wifiConfigFailed(e: Exception) {
                        runOnUiThread {
                            binding.ivTick3.setImageResource(R.drawable.ic_error)
                            binding.ivTick3.visibility = View.VISIBLE
                            binding.provProgress3.visibility = View.GONE
                            binding.tvProvError3.visibility = View.VISIBLE
                            binding.tvProvError3.setText(R.string.error_prov_step_1)
                            binding.tvProvError.visibility = View.VISIBLE
                            hideLoading()
                        }
                    }

                    override fun wifiConfigApplied() {
                        runOnUiThread {
                            binding.ivTick4.setImageResource(R.drawable.ic_checkbox_on)
                            binding.ivTick4.visibility = View.VISIBLE
                            binding.provProgress4.visibility = View.GONE
                            binding.ivTick5.visibility = View.GONE
                            binding.provProgress5.visibility = View.VISIBLE
                        }
                    }

                    override fun wifiConfigApplyFailed(e: Exception) {
                        runOnUiThread {
                            binding.ivTick4.setImageResource(R.drawable.ic_error)
                            binding.ivTick4.visibility = View.VISIBLE
                            binding.provProgress4.visibility = View.GONE
                            binding.tvProvError4.visibility = View.VISIBLE
                            binding.tvProvError4.setText(R.string.error_prov_step_2)
                            binding.tvProvError.visibility = View.VISIBLE
                            hideLoading()
                        }
                    }

                    override fun provisioningFailedFromDevice(failureReason: ProvisionFailureReason) {
                        runOnUiThread {
                            when (failureReason) {
                                ProvisionFailureReason.AUTH_FAILED -> binding.tvProvError5.setText(
                                    R.string.error_authentication_failed
                                )

                                ProvisionFailureReason.NETWORK_NOT_FOUND -> binding.tvProvError5.setText(
                                    R.string.error_network_not_found
                                )

                                ProvisionFailureReason.DEVICE_DISCONNECTED, ProvisionFailureReason.UNKNOWN -> binding.tvProvError5.setText(
                                    R.string.error_prov_step_3
                                )
                            }
                            binding.ivTick5.setImageResource(R.drawable.ic_error)
                            binding.ivTick5.visibility = View.VISIBLE
                            binding.provProgress5.visibility = View.GONE
                            binding.tvProvError5.visibility = View.VISIBLE
                            binding.tvProvError.visibility = View.VISIBLE
                            hideLoading()
                        }
                    }

                    override fun deviceProvisioningSuccess() {
                        runOnUiThread {
                            isProvisioningCompleted = true
                            binding.ivTick5.setImageResource(R.drawable.ic_checkbox_on)
                            binding.ivTick5.visibility = View.VISIBLE
                            binding.provProgress5.visibility = View.GONE
                            hideLoading()
                        }
                    }

                    override fun onProvisioningFailed(e: Exception) {
                        runOnUiThread {
                            binding.ivTick5.setImageResource(R.drawable.ic_error)
                            binding.ivTick5.visibility = View.VISIBLE
                            binding.provProgress5.visibility = View.GONE
                            binding.tvProvError5.visibility = View.VISIBLE
                            binding.tvProvError5.setText(R.string.error_prov_step_3)
                            binding.tvProvError.visibility = View.VISIBLE
                            hideLoading()
                        }
                    }
                })
        }
    }

    private fun showLoading() {
        binding.btnOk.layoutBtn.isEnabled = false
        binding.btnOk.layoutBtn.alpha = 0.5f
    }

    fun hideLoading() {
        binding.btnOk.layoutBtn.isEnabled = true
        binding.btnOk.layoutBtn.alpha = 1f
    }

    private fun showAlertForDeviceDisconnected() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setTitle(R.string.error_title)
        builder.setMessage(R.string.dialog_msg_ble_device_disconnection)

        // Set up the buttons
        builder.setPositiveButton(
            R.string.btn_ok
        ) { dialog, which ->
            dialog.dismiss()
            finish()
        }
        builder.show()
    }

    private fun sendMqttConfig() {
        mqttRetryCount++
        Log.d(TAG, "Sending MQTT configuration to /config endpoint (attempt $mqttRetryCount/$MQTT_MAX_RETRIES)")

        // Show step 1 progress
        binding.ivTick1.visibility = View.GONE
        binding.provProgress1.visibility = View.VISIBLE

        // Setup 30 second timeout for each attempt
        mqttTimeoutHandler = Handler(Looper.getMainLooper())
        mqttTimeoutRunnable = Runnable {
            Log.e(TAG, "MQTT config timeout after 30 seconds")
            handleMqttFailure("Timeout: No response from device after 30 seconds")
        }
        mqttTimeoutHandler?.postDelayed(mqttTimeoutRunnable!!, 30000)

        try {
            // Build JSON with explicit value logging
            val brokerValue = mqttBroker ?: ""
            val usernameValue = mqttUsername ?: ""
            val passwordValue = mqttPassword ?: ""

            Log.d(TAG, "=== Building MQTT JSON Payload ===")
            Log.d(TAG, "  mqtt_uri: '$brokerValue' (length: ${brokerValue.length})")
            Log.d(TAG, "  mqtt_username: '$usernameValue' (length: ${usernameValue.length})")
            Log.d(TAG, "  mqtt_password: ${if (passwordValue.isNotEmpty()) "***" else "EMPTY"} (length: ${passwordValue.length})")

            val jsonPayload = org.json.JSONObject().apply {
                put("mqtt_uri", brokerValue)
                put("mqtt_username", usernameValue)
                put("mqtt_password", passwordValue)
            }.toString()

            val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "JSON payload: $jsonPayload")
            Log.d(TAG, "Payload size: ${bytes.size} bytes")

            provisionManager.espDevice.sendDataToCustomEndPoint(
                "config",
                bytes,
                object : ResponseListener {
                    override fun onSuccess(returnData: ByteArray?) {
                        runOnUiThread {
                            // Cancel timeout
                            mqttTimeoutHandler?.removeCallbacks(mqttTimeoutRunnable!!)

                            Log.d(TAG, "MQTT config sent successfully on attempt $mqttRetryCount")
                            binding.ivTick1.setImageResource(R.drawable.ic_checkbox_on)
                            binding.ivTick1.visibility = View.VISIBLE
                            binding.provProgress1.visibility = View.GONE

                            // Reset retry count
                            mqttRetryCount = 0

                            // Parse device info from /config response
                            try {
                                if (returnData != null && returnData.isNotEmpty()) {
                                    val response = String(returnData, Charsets.UTF_8)
                                    Log.d(TAG, "MQTT config response: $response")

                                    val jsonResponse = org.json.JSONObject(response)
                                    deviceId = jsonResponse.optString("device_id", "")
                                    pairingKey = jsonResponse.optString("pairing_key", "")

                                    if (!deviceId.isNullOrEmpty() && !pairingKey.isNullOrEmpty()) {
                                        Log.d(TAG, "Device info received from /config: device_id=$deviceId, pairing_key=$pairingKey")
                                        // Mark step 2 as complete
                                        binding.ivTick2.setImageResource(R.drawable.ic_checkbox_on)
                                        binding.ivTick2.visibility = View.VISIBLE
                                        // Show device info dialog
                                        binding.root.postDelayed({
                                            showDeviceInfoDialog()
                                        }, 300)
                                    } else {
                                        Log.w(TAG, "Device info not in /config response, trying /info endpoint")
                                        fetchDeviceInfo()
                                    }
                                } else {
                                    Log.w(TAG, "Empty response from /config, trying /info endpoint")
                                    fetchDeviceInfo()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse /config response", e)
                                fetchDeviceInfo()
                            }
                        }
                    }

                    override fun onFailure(e: Exception) {
                        runOnUiThread {
                            // Cancel timeout
                            mqttTimeoutHandler?.removeCallbacks(mqttTimeoutRunnable!!)

                            Log.e(TAG, "Failed to send MQTT config (attempt $mqttRetryCount/$MQTT_MAX_RETRIES): ${e.message}")
                            handleMqttFailure("${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            // Cancel timeout
            mqttTimeoutHandler?.removeCallbacks(mqttTimeoutRunnable!!)

            Log.e(TAG, "Error building MQTT config", e)
            handleMqttFailure("${e.message}")
        }
    }

    private fun handleMqttFailure(errorMessage: String) {
        if (mqttRetryCount < MQTT_MAX_RETRIES) {
            Log.d(TAG, "Retrying MQTT config in ${MQTT_RETRY_DELAY_MS}ms (attempt $mqttRetryCount/$MQTT_MAX_RETRIES)")

            // Show retry status
            binding.tvProvError1.visibility = View.VISIBLE
            binding.tvProvError1.text = "Attempt $mqttRetryCount failed, retrying..."

            // Wait 1 second and retry
            Handler(Looper.getMainLooper()).postDelayed({
                sendMqttConfig()
            }, MQTT_RETRY_DELAY_MS)
        } else {
            // All retries exhausted
            Log.e(TAG, "All MQTT config attempts failed")
            showMqttError("Failed after $MQTT_MAX_RETRIES attempts: $errorMessage")
        }
    }

    private fun showMqttError(errorMessage: String) {
        binding.ivTick1.setImageResource(R.drawable.ic_error)
        binding.ivTick1.visibility = View.VISIBLE
        binding.provProgress1.visibility = View.GONE
        binding.tvProvError1.visibility = View.VISIBLE
        binding.tvProvError1.text = errorMessage
        binding.tvProvError.visibility = View.VISIBLE
        hideLoading()

        AlertDialog.Builder(this)
            .setTitle("MQTT Configuration Failed")
            .setMessage("Failed to send MQTT credentials to device.\n\n$errorMessage\n\nProvisioning cannot continue without MQTT configuration.")
            .setPositiveButton("Retry") { _, _ ->
                // Retry from the beginning
                binding.tvProvError.visibility = View.GONE
                binding.tvProvError1.visibility = View.GONE
                showLoading()
                mqttRetryCount = 0
                sendMqttConfig()
            }
            .setNegativeButton("Cancel") { _, _ ->
                provisionManager.espDevice.disconnectDevice()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun fetchDeviceInfo() {
        Log.d(TAG, "Fetching device info from /info endpoint")

        // Show step 2 progress
        binding.ivTick2.visibility = View.GONE
        binding.provProgress2.visibility = View.VISIBLE

        try {
            provisionManager.espDevice.sendDataToCustomEndPoint(
                "info",
                ByteArray(0), // Empty request
                object : ResponseListener {
                    override fun onSuccess(returnData: ByteArray?) {
                        runOnUiThread {
                            Log.d(TAG, "Device info received")

                            try {
                                if (returnData != null && returnData.isNotEmpty()) {
                                    val response = String(returnData, Charsets.UTF_8)
                                    Log.d(TAG, "Device info response: $response")

                                    val jsonResponse = org.json.JSONObject(response)
                                    deviceId = jsonResponse.optString("device_id", "")
                                    pairingKey = jsonResponse.optString("pairing_key", "")

                                    if (!deviceId.isNullOrEmpty() && !pairingKey.isNullOrEmpty()) {
                                        binding.ivTick2.setImageResource(R.drawable.ic_checkbox_on)
                                        binding.ivTick2.visibility = View.VISIBLE
                                        binding.provProgress2.visibility = View.GONE
                                        showDeviceInfoDialog()
                                    } else {
                                        showDeviceInfoError("Invalid response: missing device_id or pairing_key")
                                    }
                                } else {
                                    showDeviceInfoError("Empty response from device")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse device info", e)
                                showDeviceInfoError("Failed to parse response: ${e.message}")
                            }
                        }
                    }

                    override fun onFailure(e: Exception) {
                        runOnUiThread {
                            Log.e(TAG, "Failed to fetch device info", e)
                            showDeviceInfoError("Failed to communicate with device: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching device info", e)
            showDeviceInfoError("Error: ${e.message}")
        }
    }

    private fun showDeviceInfoError(errorMessage: String) {
        binding.ivTick2.setImageResource(R.drawable.ic_error)
        binding.ivTick2.visibility = View.VISIBLE
        binding.provProgress2.visibility = View.GONE
        binding.tvProvError2.visibility = View.VISIBLE
        binding.tvProvError2.text = errorMessage
        binding.tvProvError.visibility = View.VISIBLE
        hideLoading()

        AlertDialog.Builder(this)
            .setTitle("Provisioning Failed")
            .setMessage("Failed to retrieve device information.\n\n$errorMessage\n\nProvisioning cannot continue without device info.")
            .setPositiveButton("Retry") { _, _ ->
                // Retry from the beginning (MQTT step)
                binding.tvProvError.visibility = View.GONE
                binding.tvProvError2.visibility = View.GONE
                showLoading()
                sendMqttConfig()
            }
            .setNegativeButton("Cancel") { _, _ ->
                provisionManager.espDevice.disconnectDevice()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun sendWifiConfig() {
        Log.d(TAG, "Sending WiFi configuration to /wifi endpoint")

        // Show step 3 progress
        binding.ivTick3.visibility = View.GONE
        binding.provProgress3.visibility = View.VISIBLE

        try {
            // Build JSON: {"ssid":"...", "password":"..."}
            val jsonPayload = org.json.JSONObject().apply {
                put("ssid", ssidValue ?: "")
                put("password", passphraseValue ?: "")
            }.toString()

            val bytes = jsonPayload.toByteArray(Charsets.UTF_8)
            Log.d(TAG, "WiFi JSON payload: {\"ssid\":\"$ssidValue\", \"password\":\"***\"}")
            Log.d(TAG, "Payload size: ${bytes.size} bytes")

            provisionManager.espDevice.sendDataToCustomEndPoint(
                "wifi",
                bytes,
                object : ResponseListener {
                    override fun onSuccess(returnData: ByteArray?) {
                        runOnUiThread {
                            Log.d(TAG, "WiFi config sent successfully")

                            try {
                                if (returnData != null && returnData.isNotEmpty()) {
                                    val response = String(returnData, Charsets.UTF_8)
                                    Log.d(TAG, "WiFi config response: $response")

                                    val jsonResponse = org.json.JSONObject(response)
                                    val status = jsonResponse.optString("status", "")

                                    if (status == "success") {
                                        // WiFi connected successfully
                                        binding.ivTick3.setImageResource(R.drawable.ic_checkbox_on)
                                        binding.ivTick3.visibility = View.VISIBLE
                                        binding.provProgress3.visibility = View.GONE

                                        // Show step 4 (WiFi connection verified)
                                        binding.ivTick4.setImageResource(R.drawable.ic_checkbox_on)
                                        binding.ivTick4.visibility = View.VISIBLE

                                        // Show step 5 (provisioning complete)
                                        binding.ivTick5.setImageResource(R.drawable.ic_checkbox_on)
                                        binding.ivTick5.visibility = View.VISIBLE

                                        isProvisioningCompleted = true
                                        hideLoading()

                                        // Disconnect after 3 seconds to allow ESP32 to start cleanup
                                        // ESP32 will then trigger MQTT initialization after BLE cleanup
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            provisionManager.espDevice.disconnectDevice()
                                            Log.d(TAG, "BLE disconnected - ESP32 will start MQTT")
                                        }, 3000)
                                    } else {
                                        // WiFi connection failed
                                        val message = jsonResponse.optString("message", "Unknown error")
                                        showWifiError("WiFi connection failed: $message")
                                    }
                                } else {
                                    showWifiError("Empty response from device")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse WiFi config response", e)
                                showWifiError("Failed to parse response: ${e.message}")
                            }
                        }
                    }

                    override fun onFailure(e: Exception) {
                        runOnUiThread {
                            Log.e(TAG, "Failed to send WiFi config", e)
                            showWifiError("Failed to communicate with device: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error building WiFi config", e)
            showWifiError("Error: ${e.message}")
        }
    }

    private fun showWifiError(errorMessage: String) {
        binding.ivTick3.setImageResource(R.drawable.ic_error)
        binding.ivTick3.visibility = View.VISIBLE
        binding.provProgress3.visibility = View.GONE
        binding.tvProvError3.visibility = View.VISIBLE
        binding.tvProvError3.text = errorMessage
        binding.tvProvError.visibility = View.VISIBLE
        hideLoading()

        AlertDialog.Builder(this)
            .setTitle("WiFi Configuration Failed")
            .setMessage("Failed to configure WiFi on device.\n\n$errorMessage")
            .setPositiveButton("Retry") { _, _ ->
                binding.tvProvError.visibility = View.GONE
                binding.tvProvError3.visibility = View.GONE
                showLoading()
                sendWifiConfig()
            }
            .setNegativeButton("Cancel") { _, _ ->
                provisionManager.espDevice.disconnectDevice()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showDeviceInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("Device Information Received")
            .setMessage("Device ID: $deviceId\nPairing Key: $pairingKey\n\nPress OK to continue with WiFi provisioning.")
            .setPositiveButton("OK") { _, _ ->
                // Now start WiFi provisioning via JSON
                sendWifiConfig()
            }
            .setCancelable(false)
            .show()
    }
}
