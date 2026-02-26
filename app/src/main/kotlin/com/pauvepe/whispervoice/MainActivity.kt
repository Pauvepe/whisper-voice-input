package com.pauvepe.whispervoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
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
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var whisperEngine: WhisperEngine
    private lateinit var statusText: TextView
    private lateinit var downloadButton: Button
    private lateinit var startButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val REQUEST_MIC = 100
        private const val REQUEST_OVERLAY = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        whisperEngine = (application as App).whisperEngine

        statusText = findViewById(R.id.status_text)
        downloadButton = findViewById(R.id.download_button)
        startButton = findViewById(R.id.start_button)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)

        downloadButton.setOnClickListener { downloadModel() }
        startButton.setOnClickListener { startOverlay() }

        requestMicPermission()
        updateUI()

        // Check for updates silently
        scope.launch {
            try {
                UpdateManager(this@MainActivity).checkAndUpdate()
            } catch (_: Exception) {}
        }
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            updateUI()
        }
    }

    private fun updateUI() {
        when {
            !whisperEngine.isModelDownloaded() -> {
                statusText.text = "Modelo no descargado"
                downloadButton.isEnabled = true
                downloadButton.visibility = View.VISIBLE
                startButton.isEnabled = false
            }

            !whisperEngine.isLoaded -> {
                statusText.text = "Cargando modelo..."
                downloadButton.visibility = View.GONE
                startButton.isEnabled = false
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { whisperEngine.loadModel() }
                    if (ok) {
                        statusText.text = "Listo"
                        startButton.isEnabled = true
                    } else {
                        statusText.text = "Error al cargar modelo"
                        downloadButton.visibility = View.VISIBLE
                        downloadButton.isEnabled = true
                    }
                }
            }

            else -> {
                statusText.text = "Listo"
                downloadButton.visibility = View.GONE
                startButton.isEnabled = true
            }
        }
    }

    private fun downloadModel() {
        downloadButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressText.visibility = View.VISIBLE
        statusText.text = "Descargando modelo..."

        scope.launch {
            val ok = whisperEngine.downloadModel { progress ->
                runOnUiThread {
                    progressBar.progress = progress
                    progressText.text = "$progress%"
                }
            }

            progressBar.visibility = View.GONE
            progressText.visibility = View.GONE

            if (ok) {
                Toast.makeText(this@MainActivity, "Modelo descargado!", Toast.LENGTH_SHORT).show()
                updateUI()
            } else {
                Toast.makeText(this@MainActivity, "Error. Verifica tu internet.", Toast.LENGTH_LONG).show()
                downloadButton.isEnabled = true
                statusText.text = "Error de descarga"
            }
        }
    }

    private fun startOverlay() {
        // Overlay permission
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

        // Accessibility (optional but recommended)
        if (InputAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "Activa el servicio de accesibilidad para auto-pegar (opcional)",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Start service
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
