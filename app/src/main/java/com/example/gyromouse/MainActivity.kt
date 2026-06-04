package com.example.gyromouse

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.gyromouse.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mouseService: MouseService? = null
    private var isBound = false

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatus()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MouseService.LocalBinder
            mouseService = binder.getService()
            isBound = true
            updateStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            mouseService = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()

        val intent = Intent(this, MouseService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        binding.btnBluetooth.setOnClickListener {
            val btIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            startActivity(btIntent)
        }

        binding.btnLeft.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> mouseService?.setLeftClick(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouseService?.setLeftClick(false)
            }
            true
        }

        binding.btnRight.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> mouseService?.setRightClick(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouseService?.setRightClick(false)
            }
            true
        }

        binding.btnScroll.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> mouseService?.setScrollMode(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mouseService?.setScrollMode(false)
            }
            true
        }

        registerReceiver(connectionReceiver, IntentFilter("com.example.gyromouse.CONNECTION_STATE_CHANGED"))
    }

    private fun updateStatus() {
        val isConnected = mouseService?.isConnected() ?: false
        binding.tvStatus.text = if (isConnected) getString(R.string.status_connected) else getString(R.string.status_disconnected)
        binding.tvStatus.setTextColor(if (isConnected) ContextCompat.getColor(this, R.color.accent) else ContextCompat.getColor(this, R.color.white))
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val neededPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (neededPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toTypedArray(), 100)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(connectionReceiver)
    }
}
