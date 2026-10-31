package com.ton.fox

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// ============================================================
// [CONSTANTS & UTILS]
// ============================================================
private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_VOICE = 9001
private const val REQ_IMPORT = 9002
private const val REQ_NOTIFY = 9003

// ============================================================
// [BACKGROUND WORKERS]
// ============================================================
object DailyWordScheduler {
    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyWordReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 441, intent, flags)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
            }
        } catch (e: Exception) {
            alarm.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
        }
    }
}

class DailyWordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val words = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam", "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak")
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        val w = words[day % words.size]
        
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "ton_fox_daily_word"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "كلمة TON FOX اليومية", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(context, 442, open, flags)
        
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, channelId) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("كلمة اليوم من TON FOX 🦊")
            .setContentText("${w.first} • ${w.second}")
            .setStyle(Notification.BigTextStyle().bigText("${w.first}\n${w.second}\nافتح TON FOX لسماع النطق"))
            .setContentIntent(pi).setAutoCancel(true).build()
            
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            manager.notify(440, notification)
        }
        DailyWordScheduler.schedule(context)
    }
}

class DailyWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            DailyWordScheduler.schedule(context)
        }
    }
}

// ============================================================
// [MAIN UI & LOGIC]
// ============================================================
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    // --- Colors (Royal Obsidian) ---
    private val colorBg = Color.parseColor("#0B0914")
    private val colorPanel = Color.parseColor("#171324")
    private val colorGold = Color.parseColor("#FFB800")
    private val colorText = Color.parseColor("#F5F5F5")
    private val colorMuted = Color.parseColor("#8A8F9C")
    private val colorBorder = Color.parseColor("#332B45")

    // --- State ---
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var shade: View
    private var sourceText: EditText? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var currentDialect = "العراقية"
    private var score = 0
    private var level = 1
    private val history = mutableListOf<JSONObject>()
    private val favorites = mutableListOf<JSONObject>()
    private val completed = mutableSetOf<Int>()
    private lateinit var vibrator: Vibrator

    private data class Lesson(val title: String, val desc: String, val lines: List<String>, val ar: String, val fr: String)

    private val lessons = listOf(
        Lesson("مقدمة في الفرانكو", "تعرف على طريقة كتابة العربية بالحروف اللاتينية والأرقام.", listOf("الفرانكو أسلوب غير رسمي لكتابة العربية.", "تختلف كتابة بعض الكلمات من شخص لآخر.", "السياق يساعد على الفهم."), "مرحبا", "mar7aba"),
        Lesson("الحروف الأساسية", "تعلم أشهر المقابلات بين الحروف.", listOf("ب = b", "ت = t", "ش = sh", "خ = kh", "ح = 7"), "كتاب", "ketab"),
        Lesson("الأرقام", "الأرقام تمثل أصواتاً عربية معينة.", listOf("2 = ء", "3 = ع", "5 = خ", "6 = ط", "7 = ح", "8 = غ", "9 = ص"), "حبيبي", "7abibi"),
        Lesson("تكوين الكلمات", "اجمع الحروف لتكوين كلمات واضحة.", listOf("اكتب الكلمة كما تنطقها.", "حافظ على نمط ثابت.", "لا توجد كتابة موحدة لكل الكلمات."), "شكرا", "shukran"),
        Lesson("المحادثات", "تدرب على العبارات اليومية.", listOf("استخدم المسافات بين الكلمات.", "اقرأ الجملة كاملة لفهم المعنى.", "اختصر باعتدال."), "كيف حالك", "keef 7alak"),
        Lesson("اللهجة العراقية", "أمثلة من المفردات العراقية.", listOf("شلونك = shlonak", "شكو ماكو = shako mako", "هسه = hassa", "زين = zain"), "شلونك", "shlonak"),
        Lesson("الاحتراف", "حسّن وضوح كتابتك وتناسقها.", listOf("اختر طريقة ثابتة للأصوات.", "راجع الكلمات الملتبسة.", "لا تعتمد على التحويل الحرفي وحده."), "اشتقتلك", "eshta2tellak")
    )

    private val dailyWords = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam", "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak", "أريد أتعلم" to "areed at3allam", "نلتقي قريباً" to "neltagi gareeban")
    
    private val dictionary = linkedMapOf(
        "مرحبا" to "mar7aba", "السلام عليكم" to "alsalam 3alaykom", "وعليكم السلام" to "w 3alaykom alsalam",
        "كيف حالك" to "keef 7alak", "شلونك" to "shlonak", "شلونج" to "shlonich", "شلونكم" to "shlonkom",
        "شكو ماكو" to "shako mako", "شنو" to "shno", "ليش" to "leesh", "وين" to "wain", "هسه" to "hassa",
        "باچر" to "bachir", "زين" to "zain", "حبيبي" to "7abibi", "حبيبتي" to "7abibti", "قلبي" to "galbi",
        "عمري" to "3omri", "أحبك" to "a7ebak", "اشتقتلك" to "eshta2tellak", "شكرا" to "shukran",
        "عفوا" to "3afwan", "نعم" to "na3am", "لا" to "la", "اي" to "ay", "مو" to "mo", "ماكو" to "mako",
        "اكو" to "ako", "الله" to "allah", "ان شاء الله" to "inshallah", "الحمد لله" to "al7amdulillah",
        "ما شاء الله" to "mashallah", "صباح الخير" to "saba7 elkhair", "مساء الخير" to "masa elkhair",
        "أنا" to "ana", "أنت" to "enta", "بيت" to "bait", "كتاب" to "ketab", "مدرسة" to "madrasa",
        "جامعة" to "game3a", "صديق" to "sadeeq", "جميل" to "jameel", "حلو" to "7elo", "كبير" to "kbeer",
        "صغير" to "s8eer", "اليوم" to "elyom", "غدا" to "8adan", "الآن" to "alan", "أريد" to "areed",
        "تعال" to "ta3al", "روح" to "roo7", "يلا" to "yalla", "خلاص" to "khalas", "تمام" to "tamam",
        "مبروك" to "mabrook", "مشكلة" to "moshkela", "وقت" to "wa2t", "شمس" to "shams", "قمر" to "qamar",
        "نور" to "noor", "عراق" to "iraq", "مصر" to "masr", "السعودية" to "alsa3odeya"
    )

    private val arabicMap = mapOf(
        'ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j",
        'ح' to "7", 'خ' to "kh", 'د' to "d", 'ذ' to "th", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh",
        'ص' to "9", 'ض' to "9'", 'ط' to "6", 'ظ' to "6'", 'ع' to "3", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
        'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "a", 'و' to "w", 'ي' to "y",
        'ى' to "a", 'ء' to "2", 'ئ' to "2", 'ؤ' to "2", 'پ' to "p", 'چ' to "ch", 'گ' to "g"
    )

    // --- Lifecycle ---
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f // Force normal font scale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        prefs = getSharedPreferences("ton_fox_data", MODE_PRIVATE)
        loadData()
        tts = TextToSpeech(this, this)
        
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("ton_fox_daily_word", "كلمة TON FOX اليومية", NotificationManager.IMPORTANCE_DEFAULT)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        
        DailyWordScheduler.schedule(this)
        buildShell()
        showHome()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady && prefs.getBoolean("speak_on_open", true)) {
            root.postDelayed({ speakDailyWord() }, 700)
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown(); super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) {
            toggleDrawer(false)
        } else if (page.findViewWithTag<View>("home_tag") == null) {
            showHome()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // --- Helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(textValue: String, size: Float = 15f, color: Int = colorText, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = textValue
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), dp(4), dp(6))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun button(textValue: String, primary: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (primary) colorBg else colorGold)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minHeight = dp(54)
            
            val bg = shape(if (primary) colorGold else colorPanel, 12, if (primary) null else colorGold)
            val mask = shape(Color.WHITE, 12)
            background = RippleDrawable(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")), bg, mask)
            
            isClickable = true
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    vibrate(20)
                    action()
                }.start()
            }
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(18))
        background = shape(colorPanel, 16, colorBorder)
        elevation = dp(4).toFloat()
    }

    private fun gap(parent: LinearLayout, h: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))
    }

    private fun addCard(block: LinearLayout.() -> Unit) {
        val c = card()
        c.block()
        page.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
    }

    private fun addAction(title: String, primary: Boolean = false, action: () -> Unit) {
        page.addView(button(title, primary, action), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    // --- Data Persistence ---
    private fun loadData() {
        score = prefs.getInt("score", 0)
        level = prefs.getInt("level", 1)
        loadArray("history", history)
        loadArray("favorites", favorites)
        try {
            val a = JSONArray(prefs.getString("lessons", "[]") ?: "[]")
            for (i in 0 until a.length()) completed.add(a.optInt(i))
        } catch (_: Exception) {}
    }

    private fun loadArray(key: String, target: MutableList<JSONObject>) {
        try {
            val a = JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { target.add(it) }
        } catch (_: Exception) {}
    }

    private fun saveData() {
        val h = JSONArray().apply { history.forEach { put(it) } }
        val f = JSONArray().apply { favorites.forEach { put(it) } }
        val l = JSONArray().apply { completed.forEach { put(it) } }
        prefs.edit().putInt("score", score).putInt("level", level)
            .putString("history", h.toString()).putString("favorites", f.toString())
            .putString("lessons", l.toString()).apply()
    }

    // --- Translation Engine ---
    private fun toFranco(s: String): String {
        dictionary[s.trim()]?.let { return it }
        return s.map { arabicMap[it] ?: it.toString() }.joinToString("")
    }

    private fun toArabic(s: String): String {
        val reverse = dictionary.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
        reverse[s.trim().lowercase(Locale.ROOT)]?.let { return it }
        return s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") {
            if (it.isBlank()) it else reverse[it.lowercase(Locale.ROOT)] ?: decodeWord(it)
        }
    }

    private fun decodeWord(value: String): String {
        val pairs = listOf(
            "sh" to "ش", "kh" to "خ", "gh" to "غ", "th" to "ث", "ch" to "چ", "aa" to "ا", "ee" to "ي", "oo" to "و",
            "2" to "ء", "3" to "ع", "5" to "خ", "6" to "ط", "7" to "ح", "8" to "غ", "9" to "ص",
            "a" to "ا", "b" to "ب", "t" to "ت", "j" to "ج", "d" to "د", "r" to "ر", "z" to "ز", "s" to "س",
            "f" to "ف", "q" to "ق", "k" to "ك", "l" to "ل", "m" to "م", "n" to "ن", "h" to "ه", "w" to "و", "y" to "ي"
        )
        val text = value.lowercase(Locale.ROOT)
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val found = pairs.firstOrNull { text.startsWith(it.first, i) }
            if (found != null) {
                out.append(found.second); i += found.first.length
            } else {
                out.append(text[i]); i++
            }
        }
        return out.toString()
    }

    // --- UI Shell (Header, Drawer, Bottom) ---
    private fun buildShell() {
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        
        root = FrameLayout(this).apply { setBackgroundColor(colorBg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        
        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(colorPanel)
        }
        header.addView(button("☰", true) { toggleDrawer(true) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        heading.addView(label("TON FOX 🦊", 22f, colorGold, true))
        heading.addView(label("مترجم الفرانكو وأكاديمية التعلم", 11f, colorMuted))
        header.addView(heading, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        
        // Page Scroll
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        
        column.addView(header)
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        
        // Drawer Overlay
        shade = View(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            visibility = View.GONE
            setOnClickListener { toggleDrawer(false) }
        }
        root.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        
        // Drawer Menu
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(colorPanel)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE
            elevation = dp(16).toFloat()
        }
        root.addView(drawer, FrameLayout.LayoutParams(dp(300), MATCH_PARENT, Gravity.RIGHT))
        buildDrawer()
        
        setContentView(root)
    }

    private fun buildDrawer() {
        drawer.removeAllViews()
        drawer.addView(label("TON FOX 🦊", 26f, colorGold, true))
        drawer.addView(label("القائمة الرئيسية", 13f, colorMuted))
        gap(drawer, 20)
        
        val entries = listOf<Pair<String, () -> Unit>>(
            "الرئيسية" to { showHome() }, "المترجم" to { showTranslator() }, "الأكاديمية" to { showAcademy() },
            "القاموس" to { showDictionary() }, "مولّد العبارات" to { showPhraseGenerator() },
            "مولّد الأسماء" to { showNameGenerator() }, "بطاقات الحفظ" to { showFlashcards() },
            "المفضلة والسجل" to { showSaved() }, "الإحصائيات" to { showStatistics() }, "الإعدادات" to { showSettings() }
        )
        entries.forEach { (name, fn) ->
            drawer.addView(button(name) { toggleDrawer(false); fn() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(54)).apply { bottomMargin = dp(8) })
        }
        
        val spacer = View(this)
        drawer.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        drawer.addView(label("المطور: N5CR4 · @N5CR4", 12f, colorMuted))
    }

    private fun toggleDrawer(show: Boolean) {
        if (show) {
            drawer.visibility = View.VISIBLE
            shade.visibility = View.VISIBLE
            drawer.translationX = dp(300).toFloat()
            drawer.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
            shade.bringToFront(); drawer.bringToFront()
        } else {
            drawer.animate().translationX(dp(300).toFloat()).setDuration(250).withEndAction {
                drawer.visibility = View.GONE; shade.visibility = View.GONE
            }.start()
        }
    }

    private fun clearPage(title: String) {
        page.removeAllViews()
        page.addView(label(title, 24f, colorGold, true))
        gap(page, 16)
    }

    // --- UI Screens ---
    private fun todaysWord(): Pair<String, String> {
        val n = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        return dailyWords[n % dailyWords.size]
    }

    private fun speakDailyWord() {
        if (!ttsReady) { toast("محرك النطق غير جاهز"); return }
        val w = todaysWord()
        tts?.language = Locale("ar")
        tts?.speak("كلمة اليوم: ${w.first}", TextToSpeech.QUEUE_FLUSH, null, "daily_ar")
        tts?.language = Locale.US
        tts?.speak("بالفرانكو: ${w.second}", TextToSpeech.QUEUE_ADD, null, "daily_fr")
    }

    private fun showHome() {
        clearPage("الرئيسية")
        page.tag = "home_tag"
        val w = todaysWord()
        addCard {
            addView(label("أهلاً بك في TON FOX V3", 20f, colorGold, true))
            addView(label("ترجم وتعلّم الفرانكو خطوة بخطوة.", 15f))
            addView(label("معالجة محلية دون إنترنت.", 12f, colorMuted))
        }
        addCard {
            addView(label("🌟 كلمة اليوم", 19f, colorGold, true))
            addView(label(w.first, 25f, colorText, true))
            addView(label(w.second, 22f, colorGold, true))
            addView(button("🔊 استمع إلى الكلمة", true) { speakDailyWord() })
            gap(this, 8)
            addView(button("نسخ الفرانكو") { copyText(w.second) })
        }
        addCard {
            addView(label("مستواك التعليمي", 18f, colorGold, true))
            addView(label("المستوى: $level"))
            addView(label("الخبرة: $score XP"))
            addView(label("الدروس: ${completed.size}/${lessons.size}"))
            addView(label("الترجمات: ${history.size}"))
        }
        addAction("📝 ابدأ الترجمة", true) { showTranslator() }
        addAction("🎓 الأكاديمية") { showAcademy() }
        addAction("📚 القاموس") { showDictionary() }
    }

    private fun showTranslator() {
        clearPage("المترجم")
        val input = EditText(this).apply {
            hint = "اكتب النص هنا..."
            textSize = 18f; setTextColor(colorText); setHintTextColor(colorMuted)
            minLines = 4; gravity = Gravity.TOP or Gravity.RIGHT
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = shape(colorBg, 12, colorGold)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        sourceText = input
        val output = TextView(this).apply {
            text = "ستظهر الترجمة هنا..."
            textSize = 19f; setTextColor(colorText)
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = shape(colorBg, 12, colorBorder)
            minHeight = dp(90); setTextIsSelectable(true)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        addCard {
            addView(label("النص المراد تحويله", 16f, colorGold, true))
            addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            gap(this, 10)
            addView(label("اللهجة / النمط", 15f, colorGold, true))
            val spinner = Spinner(this@MainActivity)
            val dialects = arrayOf("العراقية", "المصرية", "الخليجية", "العربية العامة")
            spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dialects)
            spinner.setSelection(dialects.indexOf(currentDialect).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { currentDialect = dialects[position] }
            }
            addView(spinner)
        }
        addCard {
            addView(label("النتيجة", 16f, colorGold, true))
            addView(output)
            gap(this, 8)
            addView(button("نسخ النتيجة") { copyText(output.text.toString()) })
        }
        addAction("تحويل العربية إلى فرانكو", true) {
            val s = input.text.toString().trim()
            if (s.isBlank()) toast("اكتب نصاً أولاً") else { val r = toFranco(s); output.text = r; saveHistory(s, r, "عربي ← فرانكو"); hideKeyboard(input) }
        }
        addAction("تحويل فرانكو إلى العربية") {
            val s = input.text.toString().trim()
            if (s.isBlank()) toast("اكتب نصاً أولاً") else { val r = toArabic(s); output.text = r; saveHistory(s, r, "فرانكو ← عربي"); hideKeyboard(input) }
        }
        addAction("تبديل النص والنتيجة") { val old = input.text.toString(); input.setText(output.text.toString()); output.text = old }
        addAction("إضافة للمفضلة") { if (output.text.toString() != "ستظهر الترجمة هنا...") addFavorite(input.text.toString(), output.text.toString()) }
        addAction("مشاركة النتيجة") { shareText(output.text.toString()) }
        addAction("🎙 إدخال صوتي") { voiceInput() }
    }

    private fun saveHistory(input: String, output: String, direction: String) {
        history.add(0, JSONObject().put("input", input).put("output", output).put("direction", direction))
        while (history.size > 300) history.removeAt(history.lastIndex)
        saveData()
    }

    private fun addFavorite(input: String, output: String) {
        if (favorites.any { it.optString("input") == input && it.optString("output") == output }) { toast("محفوظة مسبقاً"); return }
        favorites.add(0, JSONObject().put("input", input).put("output", output))
        saveData(); toast("تم الحفظ")
    }

    private fun showAcademy() {
        clearPage("الأكاديمية")
        page.tag = null
        addCard {
            addView(label("تعلّم الفرانكو خطوة بخطوة", 20f, colorGold, true))
            addView(label("المستوى $level · $score XP · ${completed.size}/${lessons.size} مكتملة", 14f, colorMuted))
        }
        lessons.forEachIndexed { i, l ->
            addCard {
                addView(label("${if (completed.contains(i)) "✓" else "○"} الدرس ${i + 1}: ${l.title}", 17f, colorGold, true))
                addView(label(l.desc, 14f, colorMuted))
                addView(button("فتح الدرس") { showLesson(i) })
            }
        }
        addAction("اختبار شامل", true) { startQuiz(-1) }
    }

    private fun showLesson(i: Int) {
        val l = lessons[i]
        clearPage(l.title)
        addCard {
            addView(label("الدرس ${i + 1} من ${lessons.size}", 14f, colorGold, true))
            addView(label(l.desc, 15f))
            l.lines.forEachIndexed { n, line -> addView(label("${n + 1}. $line", 15f)) }
        }
        val answer = EditText(this).apply {
            hint = "اكتب التحويل بالفرانكو"
            setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorBg, 12, colorGold)
            setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = 17f
        }
        addCard {
            addView(label("تدريب: ${l.ar}", 20f, colorGold, true))
            addView(answer); gap(this, 8)
            addView(button("تحقق من الإجابة", true) {
                val typed = answer.text.toString().replace(" ", "").lowercase(Locale.ROOT)
                val expected = l.fr.replace(" ", "").lowercase(Locale.ROOT)
                if (typed == expected || typed == toFranco(l.ar).replace(" ", "").lowercase(Locale.ROOT)) {
                    if (completed.add(i)) score += 20
                    level = score / 100 + 1; saveData(); toast("إجابة صحيحة +20 XP"); showLesson(i)
                } else toast("حاول مرة أخرى: ${l.fr}")
            })
        }
        addAction("العودة للأكاديمية") { showAcademy() }
    }

    private fun startQuiz(lessonIndex: Int) {
        val questions = if (lessonIndex >= 0) listOf(lessons[lessonIndex]) else lessons.shuffled().take(5)
        var pos = 0; var correct = 0
        fun next() {
            if (pos >= questions.size) {
                val earned = correct * 10; score += earned; level = score / 100 + 1; saveData()
                clearPage("نتيجة الاختبار")
                addCard {
                    addView(label("انتهى الاختبار!", 23f, colorGold, true))
                    addView(label("الإجابات الصحيحة: $correct/${questions.size}", 17f))
                    addView(label("+$earned XP", 18f, colorGold))
                }
                addAction("العودة للأكاديمية", true) { showAcademy() }
                return
            }
            val l = questions[pos]
            val expected = toFranco(l.ar)
            val choices = (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3)).distinct().shuffled()
            clearPage("السؤال ${pos + 1}/${questions.size}")
            addCard {
                addView(label("حوّل إلى فرانكو:", 16f, colorMuted))
                addView(label(l.ar, 24f, colorGold, true))
                choices.forEach { choice ->
                    addView(button(choice) { if (choice == expected) correct++; pos++; next() })
                    gap(this, 8)
                }
            }
        }
        next()
    }

    private fun showDictionary() {
        clearPage("القاموس")
        val search = EditText(this).apply {
            hint = "ابحث بالعربية أو الفرانكو"
            setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorPanel, 12, colorGold)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(search); gap(page); page.addView(results)
        
        fun render(q: String) {
            results.removeAllViews()
            dictionary.filter { it.key.contains(q, true) || it.value.contains(q, true) }.forEach { (ar, fr) ->
                val c = card()
                c.addView(label(ar, 19f, colorText, true))
                c.addView(label(fr, 18f, colorGold))
                c.addView(button("نسخ الفرانكو") { copyText(fr) })
                results.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
    }

    private fun showFlashcards() {
        val entries = dictionary.entries.toList()
        var i = 0; var reveal = false
        fun render() {
            clearPage("بطاقات الحفظ")
            val e = entries[i % entries.size]
            addCard {
                addView(label("بطاقة ${i + 1}/${entries.size}", 14f, colorMuted))
                addView(label(if (reveal) e.value else e.key, 29f, colorGold, true))
                addView(button(if (reveal) "إخفاء الإجابة" else "إظهار الإجابة", true) { reveal = !reveal; render() })
            }
            addAction("البطاقة التالية", true) { i = (i + 1) % entries.size; reveal = false; render() }
            addAction("نسخ الكلمة") { copyText(e.value) }
        }
        if (entries.isNotEmpty()) render()
    }

    private fun showPhraseGenerator() {
        clearPage("مولّد العبارات")
        val phrases = listOf("شلونك؟", "صباح الخير", "وينك؟", "شنو الأخبار؟", "أنا بالطريق", "اشتقت لك", "الله يسعدك", "عندي امتحان", "أحتاج مساعدة", "أشوفك على خير", "كل عام وأنت بخير", "ماكو مشكلة", "دير بالك على نفسك", "شكراً على مساعدتك", "أتمنى لك يوماً جميلاً", "خلينا نحچي بعدين")
        phrases.forEach { p ->
            addCard {
                addView(label(p, 19f, colorText, true))
                addView(label(toFranco(p), 17f, colorGold))
                addView(button("نسخ الفرانكو", true) { copyText(toFranco(p)) })
            }
        }
    }

    private fun showNameGenerator() {
        clearPage("مولّد الأسماء")
        val names = listOf("نور", "ليث", "زين", "آدم", "علي", "حسن", "حسين", "مريم", "زهراء", "فاطمة", "سارة", "ياسمين", "محمد", "يوسف", "كرار", "مصطفى", "حيدر")
        val output = label("اضغط لتوليد اسم", 27f, colorGold, true)
        addCard {
            addView(output); gap(this, 10)
            addView(button("✨ توليد اسم جديد", true) { val n = names.random(); output.text = "$n\n${toFranco(n)}" })
            addView(button("نسخ الاسم") { copyText(output.text.toString().substringBefore("\n")) })
            addView(button("نسخ الفرانكو") { copyText(output.text.toString().substringAfter("\n", "")) })
        }
    }

    private fun showSaved() {
        clearPage("المفضلة والسجل")
        addCard { addView(label("المفضلة (${favorites.size})", 19f, colorGold, true)) }
        if (favorites.isEmpty()) addCard { addView(label("لا توجد عبارات محفوظة.", 15f, colorMuted)) }
        favorites.toList().forEach { item ->
            addCard {
                addView(label(item.optString("input"), 17f, colorText, true))
                addView(label(item.optString("output"), 17f, colorGold))
                addView(button("نسخ") { copyText(item.optString("output")) })
                addView(button("حذف") { favorites.remove(item); saveData(); showSaved() })
            }
        }
        addCard { addView(label("سجل الترجمات (${history.size})", 19f, colorGold, true)) }
        history.take(80).forEach { item ->
            addCard {
                addView(label(item.optString("direction"), 13f, colorGold))
                addView(label(item.optString("input"), 16f, colorText, true))
                addView(label(item.optString("output"), 16f, colorMuted))
                addView(button("إعادة استخدام") { showTranslator(); sourceText?.setText(item.optString("input")) })
            }
        }
        addAction("مسح السجل") {
            AlertDialog.Builder(this).setTitle("مسح السجل؟").setMessage("سيتم حذف سجل الترجمات فقط.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف") { _, _ -> history.clear(); saveData(); showSaved() }.show()
        }
    }

    private fun showStatistics() {
        clearPage("الإحصائيات")
        addCard {
            addView(label("الترجمات: ${history.size}", 18f))
            addView(label("المفضلة: ${favorites.size}", 18f))
            addView(label("الدروس: ${completed.size}/${lessons.size}", 18f))
            addView(label("الخبرة: $score XP", 20f, colorGold, true))
            addView(label("المستوى: $level", 18f))
        }
    }

    private fun showSettings() {
        clearPage("الإعدادات")
        addCard {
            addView(label("TON FOX V3", 21f, colorGold, true))
            addView(label("المطور: N5CR4", 15f))
            addView(label("Telegram: @N5CR4", 15f, colorGold))
            addView(label("الإشعار اليومي: 09:00 صباحاً", 15f))
            addView(label("النطق عند الفتح: ${if (prefs.getBoolean("speak_on_open", true)) "مفعّل" else "متوقف"}", 14f, colorMuted))
            addView(button("تفعيل / إيقاف النطق عند الفتح") { prefs.edit().putBoolean("speak_on_open", !prefs.getBoolean("speak_on_open", true)).apply(); showSettings() })
            addView(button("اختبار نطق كلمة اليوم", true) { speakDailyWord() })
            addView(button("إعادة جدولة إشعار 09:00") { DailyWordScheduler.schedule(this@MainActivity); toast("تم ضبط الإشعار اليومي") })
            addView(button("تصدير نسخة احتياطية") { exportBackup() })
            addView(button("استيراد نسخة احتياطية") { importBackup() })
            addView(button("حذف جميع البيانات") { confirmClearData() })
            addView(button("التواصل مع المطور") {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/N5CR4"))) }
                catch (_: Exception) { toast("تعذر فتح تيليجرام") }
            })
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this).setTitle("حذف البيانات").setMessage("سيتم حذف السجل والمفضلة والتقدم التعليمي.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حذف") { _, _ ->
                history.clear(); favorites.clear(); completed.clear(); score = 0; level = 1; saveData(); showSettings(); toast("تم حذف البيانات")
            }.show()
    }

    private fun exportBackup() {
        val json = JSONObject().put("app", "TON FOX").put("version", 3)
            .put("history", JSONArray().apply { history.forEach { put(it) } })
            .put("favorites", JSONArray().apply { favorites.forEach { put(it) } })
            .put("lessons", JSONArray().apply { completed.forEach { put(it) } })
            .put("score", score).put("level", level)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "TON FOX V3 Backup")
            putExtra(Intent.EXTRA_TEXT, json.toString(2))
        }
        startActivity(Intent.createChooser(send, "مشاركة النسخة الاحتياطية"))
    }

    private fun importBackup() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/json" }
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMPORT) } catch (_: Exception) { toast("تعذر فتح الملفات") }
    }

    private fun importBackupFrom(uri: Uri) {
        try {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: throw IllegalStateException("تعذر قراءة الملف")
            val j = JSONObject(raw)
            if (j.optString("app") != "TON FOX") { toast("ملف غير صالح"); return }
            history.clear(); favorites.clear(); completed.clear()
            val h = j.optJSONArray("history") ?: JSONArray(); for (i in 0 until h.length()) h.optJSONObject(i)?.let { history.add(it) }
            val f = j.optJSONArray("favorites") ?: JSONArray(); for (i in 0 until f.length()) f.optJSONObject(i)?.let { favorites.add(it) }
            val l = j.optJSONArray("lessons") ?: JSONArray(); for (i in 0 until l.length()) completed.add(l.optInt(i))
            score = j.optInt("score", 0).coerceAtLeast(0); level = j.optInt("level", 1).coerceAtLeast(1)
            saveData(); showHome(); toast("تم الاستيراد")
        } catch (e: Exception) { toast("فشل الاستيراد: ${e.message}") }
    }

    private fun voiceInput() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        }
        try { @Suppress("DEPRECATION") startActivityForResult(i, REQ_VOICE) } catch (_: Exception) { toast("التعرف الصوتي غير متاح") }
    }

    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_VOICE -> {
                val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!spoken.isNullOrBlank()) { showTranslator(); sourceText?.setText(spoken) }
            }
            REQ_IMPORT -> data.data?.let { importBackupFrom(it) }
        }
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun copyText(s: String) {
        if (s.isBlank()) { toast("لا يوجد نص لنسخه"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TON FOX", s))
        toast("تم النسخ")
    }

    private fun shareText(s: String) {
        if (s.isBlank()) { toast("لا يوجد نص للمشاركة"); return }
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$s\n\nTON FOX 🦊") }
        startActivity(Intent.createChooser(i, "مشاركة"))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}