package com.mochi.pet

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = getSharedPreferences("mochi", MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        col.addView(TextView(this).apply { text = "Alya Mikhailovna"; textSize = 28f })
        col.addView(TextView(this).apply {
            text = "Tu mascota flotante, Alya. Para que piense con IA pega tu clave de API de Anthropic (opcional; sin ella solo responde cosas simples)."
            textSize = 14f
        })
        val key = EditText(this).apply {
            hint = "Clave de API (sk-ant-...)"
            setText(p.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        col.addView(key)
        col.addView(Button(this).apply {
            text = "Activar a Alya"
            setOnClickListener {
                p.edit().putString("key", key.text.toString().trim()).apply()
                start()
            }
        })
        col.addView(Button(this).apply {
            text = "Apagar a Alya"
            setOnClickListener { stopService(Intent(this@MainActivity, PetService::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Activa «Mostrar sobre otras apps» para Alya y vuelve a tocar Activar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val need = ArrayList<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return }
        launch()
    }

    private fun launch() {
        startForegroundService(Intent(this, PetService::class.java))
        moveTaskToBack(true)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (granted(Manifest.permission.RECORD_AUDIO)) launch()
        else Toast.makeText(this, "Necesito el micrófono para escucharte", Toast.LENGTH_LONG).show()
    }
}
