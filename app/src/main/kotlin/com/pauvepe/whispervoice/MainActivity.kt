package com.pauvepe.whispervoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val REQUEST_MIC = 100
        private const val REQUEST_OVERLAY = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status_text)
        startButton = findViewById(R.id.start_button)

        startButton.setOnClickListener { startOverlay() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }

        updateUI()

        scope.launch {
            try {
                UpdateManager(this@MainActivity).checkAndUpdate()
            } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC) updateUI()
    }

    private fun updateUI() {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            statusText.text = "Necesita permiso de micrófono"
            startButton.isEnabled = false
        } else {
            statusText.text = "Listo"
            startButton.isEnabled = true
        }
    }

    private fun startOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa el permiso de superposición", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OVERLAY)
            return
        }

        if (InputAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "Activa el servicio de accesibilidad para auto-pegar (opcional)",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        moveTaskToBack(true)
    }

    @Deprecated("Use ActivityResult API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && Settings.canDrawOverlays(this)
        ) {
            startOverlay()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
