package com.example.gyromouse

import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class MouseService : Service(), SensorEventListener {

    private val TAG = "MouseService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "MouseServiceChannel"

    private var bluetoothHidDevice: BluetoothHidDevice? = null
    private var connectedDevice: BluetoothDevice? = null
    private var sensorManager: SensorManager? = null
    private var gyroscope: Sensor? = null

    private var isScrollMode = false
    private var currentButtons = 0

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): MouseService = this@MouseService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val hidDeviceCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.d(TAG, "onAppStatusChanged: registered=$registered")
            if (registered) {
                tryAutoConnect()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            Log.d(TAG, "onConnectionStateChanged: state=$state")
            val statusText = when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevice = device
                    "Connected to ${device?.name ?: "Device"}"
                }
                BluetoothProfile.STATE_CONNECTING -> "Connecting..."
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevice = null
                    "Disconnected"
                }
                else -> "Ready to connect"
            }
            
            updateNotification(statusText)
            sendBroadcast(Intent("com.example.gyromouse.CONNECTION_STATE_CHANGED"))
            
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                tryAutoConnect()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun tryAutoConnect() {
        if (connectedDevice != null) return
        
        val pairedDevices = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.bondedDevices
        for (device in pairedDevices) {
            // We can't easily know if a device supports HID host, but we can try to connect
            // to the most recently used or all paired devices.
            Log.d(TAG, "Attempting auto-connect to: ${device.name}")
            bluetoothHidDevice?.connect(device)
            // Break after first attempt for simplicity, or implement more robust logic
            break
        }
    }

    private fun updateNotification(content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(content))
    }

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Ready to connect"))

        val bluetoothAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = proxy as BluetoothHidDevice
                    val sdp = BluetoothHidDeviceAppSdpSettings(
                        "GyroMouse",
                        "Android Gyro Mouse",
                        "Manus",
                        BluetoothHidDevice.SUBCLASS1_MOUSE,
                        HidUtils.MOUSE_REPORT_DESCRIPTOR
                    )
                    bluetoothHidDevice?.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), hidDeviceCallback)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                if (profile == BluetoothProfile.HID_DEVICE) {
                    bluetoothHidDevice = null
                }
            }
        }, BluetoothProfile.HID_DEVICE)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager?.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        bluetoothHidDevice?.unregisterApp()
        super.onDestroy()
    }

    // SensorEventListener methods
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_GYROSCOPE) {
            processGyroData(event.values[0], event.values[1], event.values[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private var lastX = 0f
    private var lastY = 0f
    private val alpha = 0.8f // Low pass filter alpha

    private fun processGyroData(pitch: Float, yaw: Float, roll: Float) {
        if (connectedDevice == null) return

        // Sensitivity factor
        val sensitivity = 20.0f
        
        // Portrait orientation: 
        // roll (around Y axis) -> X movement
        // pitch (around X axis) -> Y movement
        
        // Apply a simple low-pass filter to smooth the movement
        val filteredX = alpha * lastX + (1 - alpha) * roll
        val filteredY = alpha * lastY + (1 - alpha) * pitch
        
        lastX = filteredX
        lastY = filteredY

        // Deadzone to prevent drifting
        val threshold = 0.01f
        val dx = if (Math.abs(roll) > threshold) (-roll * sensitivity).toInt() else 0
        val dy = if (Math.abs(pitch) > threshold) (-pitch * sensitivity).toInt() else 0

        if (dx == 0 && dy == 0 && !isScrollMode) return

        if (isScrollMode) {
            // Send wheel report (inverted dy for natural scrolling)
            if (Math.abs(dy) > 1) {
                sendMouseReport(currentButtons, 0, 0, if (dy > 0) -1 else 1)
            }
        } else {
            sendMouseReport(currentButtons, dx, dy, 0)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendMouseReport(buttons: Int, x: Int, y: Int, wheel: Int) {
        val report = HidUtils.mouseReport(buttons, x, y, wheel)
        connectedDevice?.let {
            bluetoothHidDevice?.sendReport(it, HidUtils.ID_MOUSE, report)
        }
    }

    fun setLeftClick(pressed: Boolean) {
        currentButtons = if (pressed) currentButtons or 0x01 else currentButtons and 0x01.inv()
        sendMouseReport(currentButtons, 0, 0, 0)
    }

    fun setRightClick(pressed: Boolean) {
        currentButtons = if (pressed) currentButtons or 0x02 else currentButtons and 0x02.inv()
        sendMouseReport(currentButtons, 0, 0, 0)
    }

    fun setScrollMode(enabled: Boolean) {
        isScrollMode = enabled
    }

    fun isConnected(): Boolean = connectedDevice != null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Gyro Mouse Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gyro Mouse")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
    }
}
