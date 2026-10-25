package com.mochi.pet

import android.animation.*
import android.app.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PetService : Service(), TextToSpeech.OnInitListener {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var bubble: TextView
    private lateinit var sym: TextView
    private lateinit var img: ImageView
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bl: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private val h = Handler(Looper.getMainLooper())
    private val rnd = Random()
    private var tts: TextToSpeech? = null
    private var ttsOk = false
    private var rec: SpeechRecognizer? = null
    private var anim: Animator? = null
    private var walkAnim: ValueAnimator? = null
    private var mode = "idle"
    private var thinking = false
    private var facing = 1f
    private var lastTouch = System.currentTimeMillis()
    private var lastSpoke = 0L
    private var lastLlm = 0L
    private var energy = 80f
    private var bored = 0f
    private val hist = ArrayList<String>()
    private val valid = setOf("feliz", "duda", "enojo", "tristeza", "sorpresa", "pensando", "neutral")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun sw() = resources.displayMetrics.widthPixels
    private fun sh() = resources.displayMetrics.heightPixels
    private fun now() = System.currentTimeMillis()
    private fun key() = prefs.getString("key", "") ?: ""
    private fun mem(): MutableList<String> =
        (prefs.getString("mem", "") ?: "").split("|").filter { it.isNotBlank() }.toMutableList()
    private fun addMem(s: String) {
        val m = mem(); m.add(s.replace("|", " "))
        prefs.edit().putString("mem", m.takeLast(8).joinToString("|")).apply()
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("mochi", MODE_PRIVATE)
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildViews()
        tts = TextToSpeech(this, this)
        h.postDelayed(tick, 7000)
        h.postDelayed({ say("¡Hola! Soy Alya. Tócame para hablar conmigo.", "feliz") }, 800)
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mochi", "Alya", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "mochi")
            .setContentTitle("Alya está contigo")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, n)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("es", "MX")
            tts?.setPitch(1.35f)
            tts?.setSpeechRate(0.92f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onError(id: String?) { backToEs() }
                override fun onDone(id: String?) { if (id == "ru") backToEs() }
            })
            ttsOk = true
        }
    }

    private fun backToEs() {
        h.post { tts?.language = Locale("es", "MX"); tts?.setPitch(1.35f); tts?.setSpeechRate(0.92f) }
    }

    private fun murmur(ru: String, es: String, e: String) {
        lastSpoke = now()
        emo(e, 4500); bubbleShow(if (es.isBlank()) ru else "$ru\n($es)")
        val t = tts ?: return
        if (ttsOk && t.setLanguage(Locale("ru", "RU")) >= TextToSpeech.LANG_AVAILABLE) {
            t.setPitch(1.4f); t.setSpeechRate(0.85f)
            t.speak(ru, TextToSpeech.QUEUE_FLUSH, null, "ru")
        } else backToEs()
    }

    private val ruso = listOf(
        Triple("Ты такой глупый…", "Eres tan tonto…", "sorpresa"),
        Triple("Мне немного стыдно.", "Me da un poco de vergüenza.", "duda"),
        Triple("Не смотри на меня так.", "No me mires así.", "enojo"),
        Triple("Я рада, что ты здесь.", "Me alegra que estés aquí.", "feliz"),
        Triple("Мне скучно…", "Me aburro…", "tristeza"),
        Triple("Ну и ладно.", "Bueno, como sea.", "enojo")
    )
