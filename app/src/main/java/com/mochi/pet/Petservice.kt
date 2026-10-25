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

        private fun buildViews() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        sym = TextView(this).apply { textSize = 26f; gravity = Gravity.CENTER }
        img = ImageView(this).apply { setImageResource(R.drawable.mochi); adjustViewBounds = true }
        root.addView(sym, LinearLayout.LayoutParams(dp(120), dp(36)))
        root.addView(img, LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT))
        img.post { img.pivotX = img.width / 2f; img.pivotY = img.height.toFloat() }

        bubble = TextView(this).apply {
            textSize = 14f; setTextColor(Color.BLACK)
            setPadding(dp(12), dp(8), dp(12), dp(8)); maxWidth = dp(230)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0xFFCCCCCC.toInt())
            }
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(40); y = sh() - dp(250) - dp(110) }
        bl = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(bubble, bl)
        wm.addView(root, lp)

        var dx0 = 0f; var dy0 = 0f; var x0 = 0; var y0 = 0; var moved = false
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx0 = e.rawX; dy0 = e.rawY; x0 = lp.x; y0 = lp.y; moved = false; walkAnim?.cancel() }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - dx0) > dp(8) || abs(e.rawY - dy0) > dp(8)) moved = true
                    if (moved) { lp.x = x0 + (e.rawX - dx0).toInt(); lp.y = y0 + (e.rawY - dy0).toInt(); upd() }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        emo("neutral", 0)
    }

    private fun upd() {
        try { wm.updateViewLayout(root, lp) } catch (e: Exception) {}
        movBubble()
    }

    private val hideB = Runnable { bubble.visibility = View.GONE }
    private fun movBubble() {
        if (bubble.visibility != View.VISIBLE) return
        val bw = bubble.measuredWidth; val bh = bubble.measuredHeight
        bl.x = (lp.x + dp(60) - bw / 2).coerceIn(0, max(0, sw() - bw))
        bl.y = max(0, lp.y - bh)
        try { wm.updateViewLayout(bubble, bl) } catch (e: Exception) {}
    }
    private fun bubbleShow(t: String) {
        bubble.text = t; bubble.visibility = View.VISIBLE
        bubble.measure(View.MeasureSpec.makeMeasureSpec(sw(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        movBubble()
        h.removeCallbacks(hideB); h.postDelayed(hideB, 5000L + t.length * 55L)
    }

        private fun resetImg() { img.translationX = 0f; img.translationY = 0f; img.rotation = 0f; img.scaleY = 1f }
    private val back = Runnable { if (mode != "sleep") emo("neutral", 0) }

    private fun emo(n: String, ms: Long = 3500) {
        anim?.cancel(); resetImg()
        sym.text = when (n) {
            "feliz" -> "♪"; "duda" -> "?"; "enojo" -> "💢"; "tristeza" -> "💧"
            "sorpresa" -> "❗"; "pensando" -> "…"; "dormida" -> "💤"; else -> ""
        }
        val a: Animator = when (n) {
            "feliz", "sorpresa" -> ObjectAnimator.ofFloat(img, "translationY", 0f, -dp(24).toFloat(), 0f).apply { duration = 450; repeatCount = 3 }
            "enojo" -> ObjectAnimator.ofFloat(img, "translationX", 0f, -dp(6).toFloat(), dp(6).toFloat(), 0f).apply { duration = 120; repeatCount = 10 }
            "tristeza" -> AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(img, "rotation", -5f), ObjectAnimator.ofFloat(img, "translationY", dp(8).toFloat()), ObjectAnimator.ofFloat(img, "scaleY", 0.93f))
                duration = 400
            }
            "duda" -> ObjectAnimator.ofFloat(img, "rotation", -10f).apply { duration = 300 }
            "pensando" -> ObjectAnimator.ofFloat(img, "rotation", 8f).apply { duration = 300 }
            "dormida" -> AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(img, "rotation", -10f), ObjectAnimator.ofFloat(img, "translationY", dp(10).toFloat()))
                duration = 600
            }
            else -> ObjectAnimator.ofFloat(img, "translationY", 0f, -dp(4).toFloat(), 0f).apply { duration = 1500; repeatCount = ValueAnimator.INFINITE }
        }
        anim = a; a.start()
        h.removeCallbacks(back)
        if (ms > 0 && n != "dormida") h.postDelayed(back, ms)
    }

    private fun speak(t: String) {
        if (ttsOk) tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "m")
    }
    private fun say(t: String, e: String) {
        lastSpoke = now()
        emo(e, 3500L + t.length * 50L); bubbleShow(t); speak(t)
    }

    private fun wander() {
        val maxX = max(1, sw() - dp(120))
        val tx = rnd.nextInt(maxX)
        facing = if (tx < lp.x) -1f else 1f
        img.scaleX = facing
        mode = "walk"
        val dur = max(1200L, abs(tx - lp.x) * 4L)
        walkAnim?.cancel()
        walkAnim = ValueAnimator.ofInt(lp.x, tx).apply {
            duration = dur; interpolator = LinearInterpolator()
            addUpdateListener { lp.x = it.animatedValue as Int; upd() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { if (mode == "walk") { mode = "idle"; emo("neutral", 0) } }
            })
            start()
        }
        anim?.cancel(); resetImg()
        anim = ObjectAnimator.ofFloat(img, "translationY", 0f, -dp(8).toFloat(), 0f).apply { duration = 350; repeatCount = (dur / 350).toInt(); start() }
    }

    private fun sleep() { mode = "sleep"; emo("dormida", 0); bubbleShow("Zzz…") }

    private fun onTap() {
        lastTouch = now(); bored = max(0f, bored - 30f)
        if (mode == "sleep") { mode = "idle"; emo("sorpresa") } else listen()
    }

    private fun listen() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("Tu teléfono no tiene reconocimiento de voz.", "duda"); return }
        tts?.stop(); rec?.destroy()
        rec = SpeechRecognizer.createSpeechRecognizer(this)
        rec?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null) ask(t) else emo("duda")
            }
            override fun onError(e: Int) { bubbleShow("No te escuché bien."); emo("duda") }
            override fun onReadyForSpeech(p: Bundle?) { bubbleShow("Te escucho…"); emo("sorpresa", 0) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        rec?.startListening(i)
    }

        private fun ctx(): String {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return "Hora local: ${hr}h. Energía: ${energy.toInt()}/100. Aburrimiento: ${bored.toInt()}/100. Recuerdos sobre tu dueño: ${mem().joinToString("; ").ifBlank { "ninguno" }}."
    }
    private val persona = "Eres Alya, una mascota virtual chibi que vive en la pantalla del celular de tu dueño. Eres algo orgullosa y seria por fuera, pero cariñosa por dentro, y a veces murmuras algo en ruso cuando te da vergüenza o te sientes tierna. Hablas en español natural, máximo 2 frases cortas. Reaccionas con emociones reales: te enojas si te insultan, te entristeces si él está mal, dudas si no sabes algo."
    private val fmt = """Responde SOLO con JSON válido, sin texto extra: {"texto":"...","emocion":"feliz|duda|enojo|tristeza|sorpresa|pensando|neutral","recordar":"dato importante nuevo sobre el dueño o cadena vacía","ruso":"a veces (no siempre) una frase muy corta en ruso que murmuras, si no cadena vacía","trad":"traducción al español de esa frase"}"""

    private fun ask(t: String) {
        lastTouch = now(); bored = max(0f, bored - 30f)
        hist.add("Usuario: $t"); while (hist.size > 8) hist.removeAt(0)
        thinking = true; emo("pensando", 0); bubbleShow("…")
        val p = "$persona\n${ctx()}\nConversación reciente:\n${hist.joinToString("\n")}\n$fmt"
        thread {
            val o = if (key().isNotBlank()) callClaude(p) else null
            h.post { thinking = false; if (o != null) apply(o) else localReply(t) }
        }
    }

    private fun thought() {
        val p = "$persona\n${ctx()}\nConversación reciente:\n${hist.joinToString("\n")}\nNadie te ha hablado en un rato. Di algo espontáneo a tu dueño según tu estado (aburrida, cansada, curiosa, recordando algo que te contó, etc).\n$fmt"
        thread { val o = callClaude(p); h.post { if (o != null) apply(o) } }
    }

    private fun apply(o: JSONObject) {
        val t = o.optString("texto", "…"); val e = o.optString("emocion", "neutral"); val r = o.optString("recordar", "")
        if (r.length > 2) addMem(r)
        hist.add("Alya: $t")
        val em = if (e in valid) e else "neutral"
        say(t, em)
        val ru = o.optString("ruso", ""); val tr = o.optString("trad", "")
        if (ru.isNotBlank() && rnd.nextFloat() < 0.7f) h.postDelayed({ murmur(ru, tr, em) }, 2500L + t.length * 75L)
    }

    private fun localReply(t: String) {
        val s = t.lowercase()
        when {
            Regex("hola|buenas|hey").containsMatchIn(s) -> say("¡Hola! Qué bueno verte por aquí.", "feliz")
            Regex("triste|mal|llor|solo|sola").containsMatchIn(s) -> say("Aquí estoy contigo. Cuéntame qué pasó.", "tristeza")
            Regex("tont|idiot|odio|cállate|callate").containsMatchIn(s) -> say("¡Oye! Eso no es nada lindo.", "enojo")
            s.contains("?") -> say("Mmm… no estoy segura. ¿Tú qué opinas?", "duda")
            else -> say("¡Vaya! No me lo esperaba.", "sorpresa")
        }
    }

    private fun callClaude(prompt: String): JSONObject? = try {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", key())
        c.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject().put("model", "claude-haiku-5-5").put("max_tokens", 300)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val s = c.inputStream.bufferedReader().readText()
        val txt = JSONObject(s).getJSONArray("content").getJSONObject(0).getString("text")
        JSONObject(Regex("\\{[\\s\\S]*\\}").find(txt)!!.value)
    } catch (e: Exception) { null }

    private val canned = listOf(
        "Me estoy quedando dormida aquí sentada…" to "duda",
        "Estoy practicando mi cara de enojada. ¿Qué tal?" to "enojo",
        "Qué día tan tranquilo. Me gusta." to "feliz",
        "Ojalá me contaras algo." to "tristeza",
        "¿Y si hoy hacemos algo nuevo?" to "sorpresa"
    )

    private fun selfTalk() {
        if (now() - lastSpoke < 20000) return
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var pick: Pair<String, String>? = null
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val lv = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (lv in 1..19 && !bm.isCharging) pick = "Tu batería está en $lv por ciento. ¡Cárgame, por favor!" to "sorpresa"
        } catch (e: Exception) {}
        if (pick == null && key().isNotBlank() && now() - lastLlm > 8 * 60000L && rnd.nextFloat() < 0.5f) {
            lastLlm = now(); thought(); return
        }
        val m = mem()
        if (pick == null && m.isNotEmpty() && rnd.nextFloat() < 0.4f) pick = "Oye, ¿cómo te fue con esto que me contaste? ${m[rnd.nextInt(m.size)]}" to "duda"
        if (pick == null && (hr >= 23 || hr < 5)) pick = "¿Sigues despierto a esta hora? Deberías descansar." to "duda"
        if (pick == null && bored > 60) pick = "¿Sigues ahí? Me estoy aburriendo muchísimo." to "tristeza"
        if (pick == null && rnd.nextFloat() < 0.3f) {
            val r = ruso[rnd.nextInt(ruso.size)]
            bored = max(0f, bored - 25f); murmur(r.first, r.second, r.third); return
        }
        if (pick == null) pick = canned[rnd.nextInt(canned.size)]
        bored = max(0f, bored - 25f)
        say(pick.first, pick.second)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!thinking && mode != "sleep") {
                energy = max(0f, energy - 0.2f)
                val idle = (now() - lastTouch) / 1000
                if (idle > 15) bored = min(100f, bored + 3f)
                if (energy < 25 && idle > 60) sleep()
                else if (idle > 50 && rnd.nextFloat() < 0.3f) selfTalk()
                else if (idle > 12 && rnd.nextFloat() < 0.4f && mode == "idle") wander()
            }
            h.postDelayed(this, 7000)
        }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        walkAnim?.cancel(); anim?.cancel()
        try { wm.removeView(root) } catch (e: Exception) {}
        try { wm.removeView(bubble) } catch (e: Exception) {}
        tts?.shutdown(); rec?.destroy()
        super.onDestroy()
    }
}
