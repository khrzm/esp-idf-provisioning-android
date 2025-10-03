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

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.espressif.AppConstants
import com.espressif.provisioning.DeviceConnectionEvent
import com.espressif.provisioning.ESPConstants
import com.espressif.provisioning.ESPProvisionManager
import com.espressif.wifi_provisioning.R
import com.espressif.wifi_provisioning.databinding.ActivityWifiConfigBinding
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class WiFiConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiConfigBinding

    private lateinit var provisionManager: ESPProvisionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        provisionManager = ESPProvisionManager.getInstance(applicationContext)
        initViews()
        EventBus.getDefault().register(this)
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    override fun onBackPressed() {
        provisionManager.espDevice.disconnectDevice()
        super.onBackPressed()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: DeviceConnectionEvent) {
        Log.d(TAG, "On Device Connection Event RECEIVED : " + event.eventType)

        when (event.eventType) {
            ESPConstants.EVENT_DEVICE_DISCONNECTED -> if (!isFinishing) {
                showAlertForDeviceDisconnected()
            }
        }
    }

    private val nextBtnClickListener = View.OnClickListener {
        val ssid = binding.layoutWifiConfig.etSsidInput.text.toString()
        val password = binding.layoutWifiConfig.etPasswordInput.text.toString()

        // Load MQTT credentials from AppConstants
        val mqttBroker = AppConstants.DEFAULT_MQTT_BROKER
        val mqttUsername = AppConstants.DEFAULT_MQTT_USERNAME
        val mqttPassword = AppConstants.DEFAULT_MQTT_PASSWORD

        // ALWAYS log what we got from AppConstants
        Log.e(TAG, "========================================")
        Log.e(TAG, "MQTT VALUES FROM APPCONSTANTS:")
        Log.e(TAG, "  Broker: '$mqttBroker'")
        Log.e(TAG, "  Username: '$mqttUsername'")
        Log.e(TAG, "  Password: '$mqttPassword'")
        Log.e(TAG, "  Broker length: ${mqttBroker.length}")
        Log.e(TAG, "  Username length: ${mqttUsername.length}")
        Log.e(TAG, "  Password length: ${mqttPassword.length}")
        Log.e(TAG, "========================================")

        if (TextUtils.isEmpty(ssid)) {
            binding.layoutWifiConfig.etSsidInput.error = getString(R.string.error_ssid_empty)
            return@OnClickListener
        }

        // Validate MQTT credentials are not empty or placeholders
        if (mqttBroker.isEmpty() || mqttBroker.contains("your.broker") ||
            mqttUsername.isEmpty() || mqttUsername.contains("your_username") ||
            mqttPassword.isEmpty() || mqttPassword.contains("your_password")) {

            Log.e(TAG, "❌ VALIDATION FAILED: MQTT credentials contain placeholders!")

            showAlertDialog(
                getString(R.string.error_title),
                "MQTT credentials are not configured in AppConstants. Please update:\n\n" +
                "DEFAULT_MQTT_BROKER\nDEFAULT_MQTT_USERNAME\nDEFAULT_MQTT_PASSWORD\n\n" +
                "Current broker: $mqttBroker"
            )
            return@OnClickListener
        }

        Log.d(TAG, "✅ MQTT credentials validation PASSED")
        Log.d(TAG, "  Broker: $mqttBroker")
        Log.d(TAG, "  Username: $mqttUsername")
        Log.d(TAG, "  Password: ***")

        goToProvisionActivity(ssid, password, mqttBroker, mqttUsername, mqttPassword)
    }

    private fun initViews() {
        setToolbar()
        val deviceName = provisionManager.espDevice.deviceName
        if (!TextUtils.isEmpty(deviceName)) {
            val msg = String.format(getString(R.string.setup_instructions), deviceName)
            val tvInstructionMsg = findViewById<TextView>(R.id.setup_instructions_view)
            tvInstructionMsg.text = msg
        }

        binding.layoutWifiConfig.btnNext.textBtn.setText(R.string.btn_next)
        binding.layoutWifiConfig.btnNext.layoutBtn.setOnClickListener(nextBtnClickListener)
    }

    private fun setToolbar() {
        setSupportActionBar(binding.titleBar.toolbar)
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        supportActionBar!!.setDisplayShowHomeEnabled(true)
        supportActionBar!!.setTitle(R.string.title_activity_wifi_config)
        binding.titleBar.toolbar.navigationIcon =
            AppCompatResources.getDrawable(this, R.drawable.ic_arrow_left)
        binding.titleBar.toolbar.setNavigationOnClickListener {
            provisionManager.espDevice?.disconnectDevice()
            finish()
        }
    }

    private fun goToProvisionActivity(ssid: String, password: String, mqttBroker: String, mqttUsername: String, mqttPassword: String) {
        finish()
        val provisionIntent = Intent(applicationContext, ProvisionActivity::class.java)
        // Copy extras from incoming intent first
        provisionIntent.putExtras(intent)
        // Then add our values (these will overwrite any conflicting keys from incoming intent)
        provisionIntent.putExtra(AppConstants.KEY_WIFI_SSID, ssid)
        provisionIntent.putExtra(AppConstants.KEY_WIFI_PASSWORD, password)
        provisionIntent.putExtra(AppConstants.KEY_MQTT_BROKER, mqttBroker)
        provisionIntent.putExtra(AppConstants.KEY_MQTT_USERNAME, mqttUsername)
        provisionIntent.putExtra(AppConstants.KEY_MQTT_PASSWORD, mqttPassword)
        startActivity(provisionIntent)
    }

    private fun showAlertDialog(title: String, message: String) {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(false)
        builder.setTitle(title)
        builder.setMessage(message)
        builder.setPositiveButton(R.string.btn_ok) { dialog, _ ->
            dialog.dismiss()
        }
        builder.show()
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

    companion object {
        private val TAG: String = WiFiConfigActivity::class.java.simpleName
    }
}
