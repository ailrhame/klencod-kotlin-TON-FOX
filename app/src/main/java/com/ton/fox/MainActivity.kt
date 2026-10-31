package com.ton.fox

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_VOICE = 9001
private const val REQ_IMPORT = 9002
private const val REQ_NOTIFY = 9003
private const val REQ_PICK_KB_BG = 9006 // Request code for keyboard background image
private const val CHANNEL_ID_DAILY = "ton_fox_daily_word"

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

data class Lesson(val id: Int, val title: String, val desc: String, val lines: List<String>, val ar: String, val fr: String, val difficulty: String = "beginner")
data class UserBadge(val id: String, val title: String, val desc: String, val icon: String)
data class CustomWord(val id: String, val arabic: String, val franco: String, val tags: String = "")
data class FlashcardState(val word: String, val intervalDays: Int = 0, val easeFactor: Float = 2.5f, val nextReviewAt: Long = 0L, val correctCount: Int = 0, val wrongCount: Int = 0)

// ============================================================
// [KEYBOARD SERVICE — Fully Customizable Arabic/English Keyboard]
// ============================================================
class FrancoKeyboardService : InputMethodService() {
    private var isArabic = true
    private lateinit var prefs: SharedPreferences

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreateInputView(): View {
        prefs = getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
        val root = FrameLayout(this)

        // 1. إعداد الخلفية (لون أو صورة)
        val bgType = prefs.getString("kb_bg_type", "dark") ?: "dark"
        if (bgType == "image") {
            val uriString = prefs.getString("kb_bg_uri", null)
            if (uriString != null) {
                try {
                    val uri = Uri.parse(uriString)
                    val inputStream: InputStream = contentResolver.openInputStream(uri)!!
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    root.background = BitmapDrawable(resources, bitmap)
                } catch (e: Exception) {
                    root.setBackgroundColor(Color.parseColor("#0B0914"))
                }
            }
        } else if (bgType == "light") {
            root.setBackgroundColor(Color.parseColor("#F0F0F0"))
        } else {
            root.setBackgroundColor(Color.parseColor("#0B0914"))
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(mainLayout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 2. تحديد حجم الكيبورد (ارتفاع الأزرار)
        val kbSize = prefs.getString("kb_size", "medium") ?: "medium"
        val keyHeight = when(kbSize) {
            "small" -> dp(45)
            "large" -> dp(75)
            else -> dp(60) // medium
        }

        // 3. تحديد شكل الأزرار
        val kbStyle = prefs.getString("kb_style", "rounded") ?: "rounded"
        val keyRadius = when(kbStyle) {
            "square" -> 0f
            "capsule" -> keyHeight.toFloat() / 2f
            else -> dp(8).toFloat() // rounded
        }

        // شريط الحالة العلوي + زر التبديل
        val header = TextView(this).apply {
            text = if (isArabic) "⌨️ TON FOX: العربية" else "⌨️ TON FOX: English"
            setTextColor(Color.parseColor("#FFB800"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            background = GradientDrawable().apply { setColor(Color.parseColor("#80000000")); cornerRadius = dp(8).toFloat() }
            setOnClickListener {
                isArabic = !isArabic
                setInputView(onCreateInputView())
            }
        }
        mainLayout.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)).apply { bottomMargin = dp(4) })

        val isLight = bgType == "light"
        val keyColor = if (isLight) Color.parseColor("#FFFFFF") else Color.parseColor("#171324")
        val keyTextColor = if (isLight) Color.parseColor("#0B0914") else Color.WHITE

        // بناء الأزرار
        val rows = if (isArabic) {
            listOf(
                listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "چ"),
                listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"),
                listOf("⇧", "ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "⌫")
            )
        } else {
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
            )
        }

        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, keyHeight).apply { bottomMargin = dp(4) }
            }
            for (key in row) {
                val isSpecial = key == "⇧" || key == "⌫"
                val btn = TextView(this).apply {
                    text = key
                    textSize = if(kbSize == "large") 24f else 20f
                    setTextColor(if (isSpecial) Color.parseColor("#FFB800") else keyTextColor)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { 
                        setColor(if (isSpecial) (if (isLight) Color.parseColor("#E0E0E0") else Color.parseColor("#332B45")) else keyColor)
                        cornerRadius = keyRadius
                        setStroke(dp(1), Color.parseColor("#44FFB800"))
                    }
                    isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }
                    setOnClickListener { handleKeyPress(key) }
                }
                rowLayout.addView(btn)
            }
            mainLayout.addView(rowLayout)
        }

        // الصف السفلي
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, keyHeight)
        }
        
        val spaceBtn = TextView(this).apply {
            text = "مسافة"
            textSize = 16f
            setTextColor(keyTextColor)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(keyColor); cornerRadius = keyRadius }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 4f).apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener { currentInputConnection?.commitText(" ", 1) }
        }
        
        val enterBtn = TextView(this).apply {
            text = "إرسال ⏎"
            textSize = 16f
            setTextColor(Color.parseColor("#0B0914"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Color.parseColor("#FFB800")); cornerRadius = keyRadius }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 2f).apply { setMargins(dp(2), 0, dp(2), 0) }
            setOnClickListener { 
                val ic = currentInputConnection ?: return@setOnClickListener
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            }
        }
        
        bottomRow.addView(spaceBtn)
        bottomRow.addView(enterBtn)
        mainLayout.addView(bottomRow)

        return root
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> ic.deleteSurroundingText(1, 0)
            "⇧" -> { /* Shift logic here */ }
            else -> ic.commitText(key, 1)
        }
    }
}

// ============================================================
// [CUSTOM VIEW: Fox Avatar]
// ============================================================
class FoxAvatarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    var level = 1
        set(value) { field = value; invalidate() }
    var accessory = "none"
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val cy = h / 2

        paint.color = Color.parseColor("#332B45")
        canvas.drawCircle(cx, cy, w * 0.45f, paint)

        val size = w * (0.6f + (level.coerceAtMost(20) * 0.01f))
        textPaint.textSize = size
        textPaint.color = Color.WHITE
        canvas.drawText("🦊", cx, cy + (size / 3), textPaint)

        when (accessory) {
            "hat" -> { textPaint.textSize = size * 0.4f; canvas.drawText("🎩", cx, cy - (size * 0.4f), textPaint) }
            "glasses" -> { textPaint.textSize = size * 0.4f; canvas.drawText("🕶️", cx, cy - (size * 0.1f), textPaint) }
            "crown" -> { textPaint.textSize = size * 0.4f; canvas.drawText("👑", cx, cy - (size * 0.45f), textPaint) }
        }
    }
}

// ============================================================
// [BACKGROUND WORKERS]
// ============================================================
object DailyWordScheduler {
    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyWordReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pending = PendingIntent.getBroadcast(context, 441, intent, flags)
        val next = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
            else alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
        } catch (e: Exception) { alarm.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending) }
    }
}

class DailyWordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val words = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran")
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        val w = words[day % words.size]
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_DAILY, "TON FOX Word", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(context, 442, open, flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID_DAILY) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("TON FOX 🦊 · كلمة اليوم").setContentText("${w.first} • ${w.second}").setContentIntent(pi).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) manager.notify(440, notification)
        DailyWordScheduler.schedule(context)
    }
}

class DailyWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) DailyWordScheduler.schedule(context)
    }
}

class TonFoxWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_2)
            val prefs = context.getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
            views.setTextViewText(android.R.id.text1, prefs.getString("widget_today", "افتح التطبيق") ?: "")
            views.setTextViewText(android.R.id.text2, prefs.getString("widget_today_fr", "") ?: "")
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(android.R.id.text1, open)
            manager.updateAppWidget(id, views)
        }
    }
}

// ============================================================
// [MAIN ACTIVITY]
// ============================================================
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var themeMode = "dark_gold"
    private var colorBg = Color.parseColor("#0B0914")
    private var colorPanel = Color.parseColor("#171324")
    private var colorGold = Color.parseColor("#FFB800")
    private var colorText = Color.parseColor("#F5F5F5")
    private var colorMuted = Color.parseColor("#8A8F9C")
    private var colorBorder = Color.parseColor("#332B45")

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var drawerContent: LinearLayout
    private lateinit var shade: View
    private var sourceText: EditText? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsSpeed = 1.0f
    
    private var score = 0
    private var level = 1
    private var streakCount = 0
    private var lastOpenDate = ""
    private var streakFreeze = 0
    private var currentAccessory = "none"
    private var currentDialect = "العراقية"
    
    private val history = mutableListOf<JSONObject>()
    private val completed = mutableSetOf<Int>()
    private val unlockedBadges = mutableSetOf<String>()
    private val personalDictionary = mutableListOf<CustomWord>()
    private val flashcardStates = mutableMapOf<String, FlashcardState>()
    private var quizAttempts = 0
    private var quizCorrect = 0
    
    private lateinit var vibrator: Vibrator
    private var toneGen: ToneGenerator? = null

    // Dictionaries
    private val dialectDictIraqi = linkedMapOf("شلونك" to "shlonak", "شكو ماكو" to "shako mako", "هسه" to "hassa", "زين" to "zain", "باچر" to "bachir", "وينك" to "wainak", "أريد" to "areed")
    private val dialectDictEgyptian = linkedMapOf("ازيك" to "ezayak", "عامل ايه" to "3amel eh", "كده" to "kda", "معلش" to "ma3lesh", "يعني" to "ya3ni", "كمان" to "kman", "برضه" to "bardu")
    private val dialectDictGulf = linkedMapOf("شلونك" to "shloonk", "وش أخبارك" to "wesh akhbarak", "زين" to "zain", "باجر" to "bacher", "وناسة" to "wnasa", "قواك الله" to "qwak allah")
    private val dialectDictLevantine = linkedMapOf("كيفك" to "keefak", "شو الأخبار" to "shu lakhbar", "منيح" to "mnee7", "هلأ" to "halla2", "بكرا" to "bukra", "شغل" to "shughul")

    private val dailyWords = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam", "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak", "أريد أتعلم" to "areed at3allam", "نلتقي قريباً" to "neltagi gareeban", "كل شيء بخير" to "kol shay bekhair", "تصبح على خير" to "tesba7 3ala khair", "ماكو مشكلة" to "mako moshkela")

    private val dictionary = linkedMapOf(
        "مرحبا" to "mar7aba", "السلام عليكم" to "alsalam 3alaykom", "كيف حالك" to "keef 7alak", "شلونك" to "shlonak", "شلونج" to "shlonich", "شلونكم" to "shlonkom", "شكو ماكو" to "shako mako", "شنو" to "shno", "ليش" to "leesh", "وين" to "wain", "هسه" to "hassa", "باچر" to "bachir", "زين" to "zain", "حبيبي" to "7abibi", "حبيبتي" to "7abibti", "قلبي" to "galbi", "عمري" to "3omri", "أحبك" to "a7ebak", "اشتقتلك" to "eshta2tellak", "شكرا" to "shukran", "عفوا" to "3afwan", "نعم" to "na3am", "لا" to "la", "اي" to "ay", "مو" to "mo", "ماكو" to "mako", "اكو" to "ako", "الله" to "allah", "ان شاء الله" to "inshallah", "الحمد لله" to "al7amdulillah", "ما شاء الله" to "mashallah", "صباح الخير" to "saba7 elkhair", "مساء الخير" to "masa elkhair", "أنا" to "ana", "أنت" to "enta", "بيت" to "bait", "كتاب" to "ketab", "مدرسة" to "madrasa", "جامعة" to "game3a", "صديق" to "sadeeq", "جميل" to "jameel", "حلو" to "7elo", "كبير" to "kbeer", "صغير" to "s8eer", "اليوم" to "elyom", "غدا" to "8adan", "الآن" to "alan", "أريد" to "areed", "تعال" to "ta3al", "روح" to "roo7", "يلا" to "yalla", "خلاص" to "khalas", "تمام" to "tamam", "مبروك" to "mabrook", "مشكلة" to "moshkela", "وقت" to "wa2t", "شمس" to "shams", "قمر" to "qamar", "نور" to "noor", "عراق" to "iraq", "مصر" to "masr", "السعودية" to "alsa3odeya"
    )

    private val arabicMap = mapOf('ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "7", 'خ' to "kh", 'د' to "d", 'ذ' to "th", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "9", 'ض' to "9'", 'ط' to "6", 'ظ' to "6'", 'ع' to "3", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "a", 'و' to "w", 'ي' to "y", 'ى' to "a", 'ء' to "2", 'ئ' to "2", 'ؤ' to "2", 'پ' to "p", 'چ' to "ch", 'گ' to "g")

    private val lessons = listOf(
        Lesson(0, "مقدمة في الفرانكو", "تعرف على طريقة كتابة العربية.", listOf("الفرانكو أسلوب غير رسمي.", "تختلف كتابة الكلمات.", "السياق يساعد على الفهم."), "مرحبا", "mar7aba", "beginner"),
        Lesson(1, "الحروف الأساسية", "تعلم أشهر المقابلات.", listOf("ب = b", "ت = t", "ش = sh", "خ = kh", "ح = 7"), "كتاب", "ketab", "beginner"),
        Lesson(2, "الأرقام", "الأرقام تمثل أصواتاً.", listOf("2 = ء", "3 = ع", "5 = خ", "6 = ط", "7 = ح", "8 = غ", "9 = ص"), "حبيبي", "7abibi", "beginner"),
        Lesson(3, "تكوين الكلمات", "اجمع الحروف.", listOf("اكتب الكلمة كما تنطقها.", "حافظ على نمط ثابت.", "لا توجد كتابة موحدة."), "شكرا", "shukran", "intermediate"),
        Lesson(4, "المحادثات", "تدرب على العبارات.", listOf("استخدم المسافات.", "اقرأ الجملة كاملة.", "اختصر باعتدال."), "كيف حالك", "keef 7alak", "intermediate"),
        Lesson(5, "اللهجة العراقية", "مفردات عراقية.", listOf("شلونك = shlonak", "شكو ماكو = shako mako", "هسه = hassa", "زين = zain"), "شلونك", "shlonak", "intermediate"),
        Lesson(6, "الاحتراف", "حسّن وضوح كتابتك.", listOf("اختر طريقة ثابتة.", "راجع الكلمات الملتبسة.", "لا تعتمد على الحرفي."), "اشتقتلك", "eshta2tellak", "advanced")
    )

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 50) } catch (_: Exception) {}
        
        prefs = getSharedPreferences("ton_fox_data", MODE_PRIVATE)
        loadSettings()
        applyTheme()
        loadData()
        
        tts = TextToSpeech(this, this)
        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
        DailyWordScheduler.schedule(this)
        updateWidgetData()
        updateStreakOnOpen()
        buildShell()
        showHome()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.setSpeechRate(ttsSpeed)
            if (prefs.getBoolean("speak_on_open", true)) root.postDelayed({ speakDailyWord() }, 1000)
        }
    }

    override fun onDestroy() { tts?.stop(); tts?.shutdown(); toneGen?.release(); super.onDestroy() }

    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) toggleDrawer(false)
        else if (page.tag?.toString() != "home_tag") showHome()
        else super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vibrate(ms: Long) {
        try { if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) else @Suppress("DEPRECATION") vibrator.vibrate(ms) } catch (_: Exception) {}
    }

    private fun playSound(type: Int) {
        if (!prefs.getBoolean("sound", true) || toneGen == null) return
        try {
            when (type) {
                1 -> toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 50)
                2 -> toneGen?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 100)
                3 -> toneGen?.startTone(ToneGenerator.TONE_PROP_NACK, 100)
            }
        } catch (_: Exception) {}
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun label(t: String, size: Float = 15f, color: Int = colorText, bold: Boolean = false, rtl: Boolean = true): TextView =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(6), dp(4), dp(6))
            if (rtl) layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

    private fun button(t: String, primary: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = t; textSize = 15f; gravity = Gravity.CENTER
            setTextColor(if (primary) colorBg else colorGold)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(12)); minHeight = dp(54)
            val bg = shape(if (primary) colorGold else colorPanel, 12, if (primary) null else colorGold)
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 0.28f)), bg, shape(Color.WHITE, 12))
            isClickable = true
            setOnClickListener {
                animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    playSound(1); vibrate(15); action()
                }.start()
            }
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18))
        background = shape(colorPanel, 16, colorBorder); elevation = dp(4).toFloat()
    }

    private fun gap(parent: LinearLayout, h: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }

    private fun addCard(block: LinearLayout.() -> Unit) {
        val c = card(); c.block()
        page.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
    }

    private fun addAction(t: String, primary: Boolean = false, action: () -> Unit) {
        page.addView(button(t, primary, action), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copyText(s: String) {
        if (s.isBlank()) { toast("لا يوجد نص لنسخه"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TON FOX", s))
        toast("تم النسخ")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID_DAILY, "TON FOX Word", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun updateWidgetData() {
        val w = todaysWord()
        prefs.edit().putString("widget_today", w.first).putString("widget_today_fr", w.second).apply()
        sendBroadcast(Intent(this, TonFoxWidget::class.java).apply { action = AppWidgetManager.ACTION_APPWIDGET_UPDATE })
    }

    private fun loadSettings() {
        themeMode = prefs.getString("theme_mode", "dark_gold") ?: "dark_gold"
        ttsSpeed = prefs.getFloat("tts_speed", 1.0f).coerceIn(0.5f, 2.0f)
        currentDialect = prefs.getString("dialect", "العراقية") ?: "العراقية"
    }

    private fun applyTheme() {
        when (themeMode) {
            "light" -> {
                colorBg = Color.parseColor("#F9F7F2"); colorPanel = Color.parseColor("#FFFFFF")
                colorText = Color.parseColor("#171324"); colorMuted = Color.parseColor("#5D5A66")
                colorBorder = Color.parseColor("#E0DBC8"); colorGold = Color.parseColor("#C58A00")
            }
            "amoled" -> {
                colorBg = Color.parseColor("#000000"); colorPanel = Color.parseColor("#0A0A0A")
                colorText = Color.parseColor("#FFFFFF"); colorMuted = Color.parseColor("#888888")
                colorBorder = Color.parseColor("#222222"); colorGold = Color.parseColor("#FFB800")
            }
            else -> {
                colorBg = Color.parseColor("#0B0914"); colorPanel = Color.parseColor("#171324")
                colorText = Color.parseColor("#F5F5F5"); colorMuted = Color.parseColor("#8A8F9C")
                colorBorder = Color.parseColor("#332B45"); colorGold = Color.parseColor("#FFB800")
            }
        }
    }

    private fun saveSetting(k: String, v: Any) {
        val e = prefs.edit()
        when (v) { is String -> e.putString(k, v); is Int -> e.putInt(k, v); is Float -> e.putFloat(k, v); is Boolean -> e.putBoolean(k, v) }
        e.apply()
    }

    private fun loadData() {
        score = prefs.getInt("score", 0); level = prefs.getInt("level", 1)
        streakCount = prefs.getInt("streak", 0); lastOpenDate = prefs.getString("last_open", "") ?: ""
        streakFreeze = prefs.getInt("streak_freeze", 0)
        currentAccessory = prefs.getString("accessory", "none") ?: "none"
        loadArray("history", history)
        try { val a = JSONArray(prefs.getString("lessons", "[]") ?: "[]"); for (i in 0 until a.length()) completed.add(a.optInt(i)) } catch (_: Exception) {}
        try { val a = JSONArray(prefs.getString("badges", "[]") ?: "[]"); for (i in 0 until a.length()) unlockedBadges.add(a.optString(i)) } catch (_: Exception) {}
        try {
            val a = JSONArray(prefs.getString("personal_dict", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                personalDictionary.add(CustomWord(o.optString("id"), o.optString("ar"), o.optString("fr"), o.optString("tags")))
            }
        } catch (_: Exception) {}
    }

    private fun loadArray(key: String, target: MutableList<JSONObject>) {
        try { val a = JSONArray(prefs.getString(key, "[]") ?: "[]"); for (i in 0 until a.length()) a.optJSONObject(i)?.let { target.add(it) } } catch (_: Exception) {}
    }

    private fun saveData() {
        val h = JSONArray().apply { history.forEach { put(it) } }
        val l = JSONArray().apply { completed.forEach { put(it) } }
        val b = JSONArray().apply { unlockedBadges.forEach { put(it) } }
        val pd = JSONArray().apply { personalDictionary.forEach { put(JSONObject().put("id", it.id).put("ar", it.arabic).put("fr", it.franco)) } }
        
        prefs.edit().putInt("score", score).putInt("level", level).putInt("streak", streakCount).putString("last_open", lastOpenDate)
            .putInt("streak_freeze", streakFreeze).putString("accessory", currentAccessory)
            .putString("history", h.toString()).putString("lessons", l.toString())
            .putString("badges", b.toString()).putString("personal_dict", pd.toString()).apply()
    }

    private fun updateStreakOnOpen() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastOpenDate == today) return
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        
        if (lastOpenDate == yesterday) {
            streakCount++
        } else if (lastOpenDate != "" && streakFreeze > 0) {
            streakFreeze--
            streakCount++
            toast("🧊 تم استخدام تجميد السلسلة! سلسلتك محفوظة.")
        } else {
            streakCount = 1
        }
        lastOpenDate = today
        saveData()
    }

    private fun checkLevelUp() {
        val old = level; level = score / 100 + 1
        if (level > old) { 
            playSound(2); vibrate(200)
            AlertDialog.Builder(this).setTitle("⭐ ارتقيت للمستوى $level!").setMessage("ثعلبك أصبح أقوى وأكبر!").setPositiveButton("رائع", null).show() 
        }
    }

    private fun toFranco(s: String): String {
        personalDictionary.firstOrNull { it.arabic.trim() == s.trim() }?.let { return it.franco }
        val dict = when(currentDialect) { "المصرية" -> dialectDictEgyptian; "الخليجية" -> dialectDictGulf; "الشامية" -> dialectDictLevantine; else -> dialectDictIraqi }
        dict[s.trim()]?.let { return it }
        dictionary[s.trim()]?.let { return it }
        return s.map { arabicMap[it] ?: it.toString() }.joinToString("")
    }

    private fun toArabic(s: String): String {
        val reverse = dictionary.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
        val revPersonal = personalDictionary.associate { it.franco.lowercase(Locale.ROOT) to it.arabic }
        val lookup = revPersonal + reverse
        lookup[s.trim().lowercase(Locale.ROOT)]?.let { return it }
        return s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { if (it.isBlank()) it else lookup[it.lowercase(Locale.ROOT)] ?: decodeWord(it) }
    }

    private fun decodeWord(value: String): String {
        val pairs = listOf("sh" to "ش", "kh" to "خ", "gh" to "غ", "th" to "ث", "ch" to "چ", "aa" to "ا", "ee" to "ي", "oo" to "و", "2" to "ء", "3" to "ع", "5" to "خ", "6" to "ط", "7" to "ح", "8" to "غ", "9" to "ص", "a" to "ا", "b" to "ب", "t" to "ت", "j" to "ج", "d" to "د", "r" to "ر", "z" to "ز", "s" to "س", "f" to "ف", "q" to "ق", "k" to "ك", "l" to "ل", "m" to "م", "n" to "ن", "h" to "ه", "w" to "و", "y" to "ي")
        val text = value.lowercase(Locale.ROOT); val out = StringBuilder(); var i = 0
        while (i < text.length) {
            val found = pairs.firstOrNull { text.startsWith(it.first, i) }
            if (found != null) { out.append(found.second); i += found.first.length } else { out.append(text[i]); i++ }
        }
        return out.toString()
    }

    private fun buildShell() {
        window.statusBarColor = colorBg; window.navigationBarColor = colorBg
        root = FrameLayout(this).apply { setBackgroundColor(colorBg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(colorPanel) }
        
        header.addView(button("☰", true) { toggleDrawer(true) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        heading.addView(label("TON FOX 🦊", 22f, colorGold, true))
        heading.addView(label(if (streakCount > 0) "🔥 $streakCount يوم متواصل" else "مترجم الفرانكو وأكاديمية التعلم", 11f, colorMuted))
        header.addView(heading)
        
        page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        column.addView(header); column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        
        shade = View(this).apply { setBackgroundColor(withAlpha(Color.BLACK, 0.8f)); visibility = View.GONE; setOnClickListener { toggleDrawer(false) } }
        root.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colorPanel); layoutDirection = View.LAYOUT_DIRECTION_RTL; visibility = View.GONE; elevation = dp(16).toFloat() }
        val drawerScroll = ScrollView(this).apply { isFillViewport = true }
        drawerContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(24)) }
        drawerScroll.addView(drawerContent, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        drawer.addView(drawerScroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        
        root.addView(drawer, FrameLayout.LayoutParams(dp(300), MATCH_PARENT, Gravity.RIGHT))
        buildDrawer(); setContentView(root)
    }

    private fun buildDrawer() {
        drawerContent.removeAllViews()
        drawerContent.addView(label("TON FOX 🦊", 26f, colorGold, true)); drawerContent.addView(label("القائمة", 13f, colorMuted)); gap(drawerContent, 20)
        val entries = listOf<Pair<String, () -> Unit>>(
            "الرئيسية" to { showHome() }, "المترجم" to { showTranslator() }, "المحادثة الحية 💬" to { showChat() },
            "الألعاب المصغرة 🎮" to { showGames() }, "الأكاديمية" to { showAcademy() },
            "قاموس اللهجات 🗣️" to { showDialects() }, "بطاقات SRS 🧠" to { showFlashcards() },
            "بروفايل الثعلب 🦊" to { showFoxProfile() }, "الإعدادات" to { showSettings() },
            "💬 تواصل مع المطور (@N5CR4)" to { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/N5CR4"))) } catch (_: Exception) { toast("تعذر فتح تيليجرام") } }
        )
        entries.forEach { (name, fn) -> drawerContent.addView(button(name) { toggleDrawer(false); fn() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { bottomMargin = dp(6) }) }
        drawerContent.addView(View(this), LinearLayout.LayoutParams(1, dp(20)))
        drawerContent.addView(label("TON FOX V5 · N5CR4", 12f, colorMuted))
    }

    private fun toggleDrawer(show: Boolean) {
        if (show) {
            drawer.visibility = View.VISIBLE; shade.visibility = View.VISIBLE
            drawer.translationX = dp(300).toFloat(); drawer.animate().translationX(0f).setDuration(250).setInterpolator(DecelerateInterpolator()).start()
            shade.bringToFront(); drawer.bringToFront()
        } else {
            drawer.animate().translationX(dp(300).toFloat()).setDuration(250).withEndAction { drawer.visibility = View.GONE; shade.visibility = View.GONE }.start()
        }
    }

    private fun clearPage(title: String) { page.removeAllViews(); page.addView(label(title, 24f, colorGold, true)); gap(page, 16) }
    private fun todaysWord(): Pair<String, String> { val n = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt(); return dailyWords[n % dailyWords.size] }
    private fun speak(s: String) { if (!ttsReady || s.isBlank()) return; tts?.language = Locale.US; tts?.setSpeechRate(ttsSpeed); tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sp") }
    
    private fun speakDailyWord() {
        if (!ttsReady) { toast("محرك النطق غير جاهز"); return }
        val w = todaysWord()
        val result = tts?.setLanguage(Locale("ar"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.language = Locale.US; tts?.speak(w.second, TextToSpeech.QUEUE_FLUSH, null, "daily_en")
        } else {
            tts?.speak(w.first, TextToSpeech.QUEUE_FLUSH, null, "daily_ar")
            Handler(Looper.getMainLooper()).postDelayed({ tts?.language = Locale.US; tts?.speak(w.second, TextToSpeech.QUEUE_ADD, null, "daily_fr") }, 1500)
        }
    }

    // --- SCREENS ---
    private fun showHome() {
        clearPage("الرئيسية"); page.tag = "home_tag"; val w = todaysWord()
        
        // تصغير حجم الثعلب في الرئيسية (120dp)
        val fox = FoxAvatarView(this)
        fox.level = level
        fox.accessory = currentAccessory
        page.addView(fox, LinearLayout.LayoutParams(dp(120), dp(120)).apply { 
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12) 
        })
        
        addCard {
            addView(label("أهلاً بك يا صديقي!", 20f, colorGold, true))
            addView(label("مستواك $level · $score XP · لديك $streakFreeze 🧊 تجميد سلسلة.", 14f, colorMuted))
        }
        
        addCard {
            addView(label("🌟 كلمة اليوم", 19f, colorGold, true))
            addView(label(w.first, 25f, colorText, true)); addView(label(w.second, 22f, colorGold, true))
            addView(button("🔊 استمع", true) { speakDailyWord() }); gap(this, 8)
            addView(button("📋 نسخ الفرانكو") { copyText(w.second) })
        }
        
        val grid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        grid.addView(button("💬 محادثة", true) { showChat() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        grid.addView(button("🎮 ألعاب") { showGames() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        page.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        
        addAction("📝 المترجم") { showTranslator() }
        addAction("🎓 الأكاديمية") { showAcademy() }
    }

    private fun showTranslator() {
        clearPage("المترجم")
        val input = EditText(this).apply { hint = "اكتب النص هنا..."; textSize = 18f; setTextColor(colorText); minLines = 3; background = shape(colorBg, 12, colorGold); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val output = TextView(this).apply { text = "ستظهر الترجمة هنا..."; textSize = 19f; setTextColor(colorText); background = shape(colorBg, 12, colorBorder); minHeight = dp(90); setPadding(dp(16), dp(16), dp(16), dp(16)) }
        
        addCard {
            addView(label("النص", 16f, colorGold, true)); addView(input); gap(this, 8)
            addView(button("تحويل إلى فرانكو", true) { output.text = toFranco(input.text.toString()); saveData() })
        }
        addCard {
            addView(label("النتيجة", 16f, colorGold, true)); addView(output); gap(this, 8)
            addView(button("نسخ") { copyText(output.text.toString()) })
        }
    }

    private fun showChat() {
        clearPage("محادثة الثعلب 💬")
        val chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scrollChat = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(400)); addView(chatContainer) }
        page.addView(scrollChat)

        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = "اكتب بالفرانكو..."; setTextColor(colorText); background = shape(colorBg, 24, colorGold); setPadding(dp(16), dp(12), dp(16), dp(12)); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) } }
        val sendBtn = button("إرسال", true) {
            val msg = input.text.toString().trim()
            if (msg.isNotBlank()) {
                addChatBubble(chatContainer, msg, isFox = false); input.setText(""); scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
                Handler(Looper.getMainLooper()).postDelayed({ addChatBubble(chatContainer, generateFoxReply(msg), isFox = true); scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }; score += 5; saveData(); playSound(1) }, 1000)
            }
        }
        inputRow.addView(input); inputRow.addView(sendBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        page.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        addChatBubble(chatContainer, "أهلاً! أنا الثعلب 🦊. كيف حالك؟ (اكتب shlonak)", isFox = true)
    }

    private fun addChatBubble(parent: LinearLayout, text: String, isFox: Boolean) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = if (isFox) Gravity.START else Gravity.END; setPadding(0, dp(4), 0, dp(4)) }
        val bubble = TextView(this).apply { this.text = text; textSize = 16f; setTextColor(if (isFox) colorBg else colorText); background = shape(if (isFox) colorGold else colorPanel, 16, if (isFox) null else colorBorder); setPadding(dp(16), dp(12), dp(16), dp(12)); maxWidth = resources.displayMetrics.widthPixels - dp(100) }
        row.addView(bubble); parent.addView(row)
    }

    private fun generateFoxReply(userMsg: String): String {
        val msg = userMsg.lowercase(Locale.ROOT)
        return when {
            msg.contains("shlonak") || msg.contains("شلونك") -> "أنا بخير، شكراً! (shukran!) +5 XP"
            msg.contains("shukran") || msg.contains("شكرا") -> "عفواً! (3afwan) استمر! +5 XP"
            else -> { val correct = toFranco(userMsg); if (correct == userMsg) "أحسنت! (mumtaz!) +5 XP" else "محاولة جيدة! الأصح: $correct +5 XP" }
        }
    }

    private fun showGames() {
        clearPage("الألعاب المصغرة 🎮")
        addCard { addView(label("اختر لعبة لتكسب XP:", 16f, colorText)) }
        addAction("🔤 مطابقة الحروف بالأرقام", true) { playGameMatch() }
        addAction("⏱️ سباق الكتابة", true) { playGameRace() }
        addAction("📝 الكلمة الناقصة", true) { playGameMissing() }
    }

    private fun playGameMatch() {
        val pair = listOf("ع" to "3", "ح" to "7", "خ" to "5", "ط" to "6", "ص" to "9").random()
        clearPage("لعبة المطابقة")
        addCard {
            addView(label("الرقم المقابل لـ «${pair.first}»؟", 22f, colorGold, true))
            listOf(pair.second, "2", "4", "8").shuffled().forEach { c -> addView(button(c) { if (c == pair.second) { score += 15; saveData(); playSound(2); toast("✔ +15 XP"); showGames() } else { playSound(3); toast("✗ الإجابة: ${pair.second}"); showGames() } }) }
        }
    }

    private fun playGameRace() {
        clearPage("سباق الكتابة")
        val target = listOf("shlonak", "saba7 elkhair", "shukran", "7abibi", "eshta2tellak").random()
        addCard {
            addView(label("اكتب الكلمة بأسرع وقت:", 16f, colorMuted)); addView(label(target, 28f, colorGold, true))
            val input = EditText(this@MainActivity).apply { background = shape(colorBg, 10, colorBorder); setTextColor(colorText) }; addView(input)
            addView(button("تحقق", true) { if (input.text.toString().trim() == target) { score += 20; saveData(); playSound(2); toast("⚡ +20 XP"); showGames() } else { playSound(3); toast("✗ خطأ") } })
        }
    }

    private fun playGameMissing() {
        clearPage("الكلمة الناقصة")
        addCard {
            addView(label("أكمل الفراغ:", 16f, colorMuted)); addView(label("ش_ونك (كيف حالك)", 24f, colorGold, true))
            listOf("ل", "ب", "م", "ت").shuffled().forEach { c -> addView(button(c) { if (c == "ل") { score += 10; saveData(); playSound(2); toast("✔ +10 XP"); showGames() } else { playSound(3); toast("✗ خطأ") } }) }
        }
    }

    private fun showDialects() {
        clearPage("قاموس اللهجات 🗣️")
        val search = EditText(this).apply { hint = "ابحث..."; setTextColor(colorText); background = shape(colorPanel, 12, colorGold); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        page.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.addView(results)
        fun render(q: String) {
            results.removeAllViews()
            (dialectDictIraqi.keys + dialectDictEgyptian.keys + dialectDictGulf.keys + dialectDictLevantine.keys).filter { it.contains(q, true) }.forEach { word ->
                val c = card(); c.addView(label(word, 20f, colorGold, true))
                c.addView(label("🇮🇶 ${dialectDictIraqi[word] ?: "—"}", 15f)); c.addView(label("🇪🇬 ${dialectDictEgyptian[word] ?: "—"}", 15f))
                c.addView(label("🇸🇦 ${dialectDictGulf[word] ?: "—"}", 15f)); c.addView(label("🇱🇧 ${dialectDictLevantine[word] ?: "—"}", 15f))
                c.addView(button("📋 نسخ") { copyText(dialectDictIraqi[word] ?: "") }); results.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
    }

    private fun showAcademy() {
        clearPage("الأكاديمية")
        addCard { addView(label("المستوى $level · $score XP · ${completed.size}/${lessons.size}", 14f, colorMuted)) }
        lessons.forEach { l ->
            addCard {
                addView(label("${if (completed.contains(l.id)) "✓" else "○"} الدرس ${l.id + 1}: ${l.title}", 17f, colorGold, true))
                addView(label(l.desc, 14f, colorMuted)); addView(button("فتح") { showLesson(l.id) })
            }
        }
        addAction("🧪 اختبار شامل", true) { startQuiz(-1) }
    }

    private fun showLesson(i: Int) {
        val l = lessons[i]; clearPage(l.title)
        addCard { addView(label("الدرس ${i + 1}", 14f, colorGold, true)); addView(label(l.desc, 15f)); l.lines.forEach { addView(label(it, 15f)) } }
        val answer = EditText(this).apply { hint = "اكتب التحويل"; setTextColor(colorText); background = shape(colorBg, 12, colorGold); setPadding(dp(14), dp(14), dp(14), dp(14)) }
        addCard {
            addView(label("تدريب: ${l.ar}", 20f, colorGold, true)); addView(answer)
            addView(button("تحقق", true) {
                if (answer.text.toString().replace(" ", "").lowercase() == l.fr.replace(" ", "").lowercase()) {
                    if (completed.add(i)) { score += 20; saveData(); playSound(2); toast("✔ +20 XP") }
                    showLesson(i)
                } else { playSound(3); toast("حاول مجدداً: ${l.fr}") }
            })
        }
        addAction("العودة", true) { showAcademy() }
    }

    private fun startQuiz(lessonIndex: Int) {
        val qs = if (lessonIndex >= 0) listOf(lessons[lessonIndex]) else lessons.shuffled().take(5)
        var pos = 0; var correct = 0; quizAttempts++
        fun next() {
            if (pos >= qs.size) {
                score += correct * 10; quizCorrect += correct; checkLevelUp(); saveData(); playSound(2)
                clearPage("النتيجة"); addCard { addView(label("انتهى الاختبار!", 22f, colorGold, true)); addView(label("صحيحة: $correct/${qs.size}", 16f)) }
                addAction("العودة", true) { showAcademy() }; return
            }
            val l = qs[pos]; val expected = toFranco(l.ar)
            clearPage("السؤال ${pos + 1}/${qs.size}")
            addCard {
                addView(label(l.ar, 24f, colorGold, true))
                (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3)).distinct().shuffled().forEach { c ->
                    addView(button(c) { if (c == expected) { correct++; playSound(2) } else playSound(3); pos++; next() })
                }
            }
        }
        next()
    }

    private fun showFoxProfile() {
        clearPage("بروفايل الثعلب 🦊")
        // تصغير حجم الثعلب في البروفايل (150dp)
        val fox = FoxAvatarView(this)
        fox.level = level
        fox.accessory = currentAccessory
        page.addView(fox, LinearLayout.LayoutParams(dp(150), dp(150)).apply { 
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16) 
        })
        
        addCard {
            addView(label("مستوى الثعلب: $level", 20f, colorGold, true))
            addView(label("الخبرة: $score XP", 16f))
            addView(label("تجميد السلسلة: $streakFreeze 🧊", 16f))
        }
        
        addCard {
            addView(label("🛍️ متجر الإكسسوارات", 18f, colorGold, true))
            fun buyAccessory(id: String, name: String, cost: Int) {
                if (currentAccessory == id) { toast("مُجهز بالفعل"); return }
                if (score >= cost) {
                    AlertDialog.Builder(this@MainActivity).setTitle("شراء $name").setMessage("سيتم خصم $cost XP.").setPositiveButton("شراء") { _, _ ->
                        score -= cost; currentAccessory = id; saveData(); playSound(2); showFoxProfile()
                    }.setNegativeButton("إلغاء", null).show()
                } else toast("لا تملك نقاط كافية")
            }
            addView(button("🎩 قبعة (500 XP)") { buyAccessory("hat", "قبعة", 500) })
            addView(button("🕶️ نظارات (1000 XP)") { buyAccessory("glasses", "نظارات", 1000) })
            addView(button("👑 تاج (2500 XP)") { buyAccessory("crown", "تاج", 2500) })
            if (currentAccessory != "none") addView(button("❌ إزالة") { currentAccessory = "none"; saveData(); showFoxProfile() })
        }
        
        addCard {
            addView(label("🧊 تجميد السلسلة", 18f, colorGold, true))
            addView(label("يحمي سلسلتك ليوم واحد. (لديك $streakFreeze)", 14f, colorMuted))
            addView(button("شراء تجميد (200 XP)", true) {
                if (score >= 200) { score -= 200; streakFreeze++; saveData(); playSound(2); showFoxProfile() }
                else toast("تحتاج 200 XP")
            })
        }
    }

    private fun showSettings() {
        clearPage("الإعدادات")
        
        // إعدادات الكيبورد
        addCard {
            addView(label("⌨️ إعدادات لوحة المفاتيح", 18f, colorGold, true))
            
            // الحجم
            addView(label("حجم الكيبورد:", 14f, colorMuted))
            val sizeRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
            val currentSize = prefs.getString("kb_size", "medium") ?: "medium"
            listOf("small" to "صغير", "medium" to "متوسط", "large" to "كبير").forEach { (id, name) ->
                sizeRow.addView(button(if (currentSize == id) "✓ $name" else name) {
                    saveSetting("kb_size", id); toast("تم تغيير حجم الكيبورد")
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            }
            addView(sizeRow)

            // الشكل
            addView(label("شكل الأزرار:", 14f, colorMuted))
            val styleRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
            val currentStyle = prefs.getString("kb_style", "rounded") ?: "rounded"
            listOf("square" to "مربع", "rounded" to "دائري", "capsule" to "كبسولة").forEach { (id, name) ->
                styleRow.addView(button(if (currentStyle == id) "✓ $name" else name) {
                    saveSetting("kb_style", id); toast("تم تغيير شكل الكيبورد")
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            }
            addView(styleRow)

            // الخلفية
            addView(label("خلفية الكيبورد:", 14f, colorMuted))
            val bgRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
            val currentBg = prefs.getString("kb_bg_type", "dark") ?: "dark"
            listOf("dark" to "داكن", "light" to "فاتح").forEach { (id, name) ->
                bgRow.addView(button(if (currentBg == id) "✓ $name" else name) {
                    saveSetting("kb_bg_type", id); toast("تم تغيير خلفية الكيبورد")
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            }
            bgRow.addView(button("🖼️ صورة من المعرض", true) {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                @Suppress("DEPRECATION") startActivityForResult(intent, REQ_PICK_KB_BG)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 2f))
            addView(bgRow)
        }

        addCard {
            addView(label("🎨 المظهر والثيم", 17f, colorGold, true))
            listOf("dark_gold" to "داكن ذهبي", "light" to "فاتح", "amoled" to "AMOLED أسود").forEach { (id, name) ->
                addView(button(if (themeMode == id) "✓ $name" else name) {
                    themeMode = id; saveSetting("theme_mode", id); applyTheme(); buildShell(); showSettings()
                })
            }
        }
        
        addCard {
            addView(label("💾 البيانات", 17f, colorGold, true))
            addView(button("🗑️ حذف جميع البيانات") {
                AlertDialog.Builder(this@MainActivity).setTitle("تأكيد").setMessage("سيتم مسح كل شيء!").setPositiveButton("حذف") { _, _ ->
                    prefs.edit().clear().apply(); score = 0; level = 1; streakCount = 0; history.clear(); saveData(); showHome()
                }.setNegativeButton("إلغاء", null).show()
            })
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PICK_KB_BG -> {
                data?.data?.let { uri ->
                    try {
                        // نأخذ صلاحية دائمة للصورة
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        saveSetting("kb_bg_uri", uri.toString())
                        saveSetting("kb_bg_type", "image")
                        toast("✔ تم تعيين صورة الخلفية للكيبورد")
                    } catch (e: Exception) {
                        toast("فشل تحميل الصورة")
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) toast("✔ تم تفعيل الإشعارات") else toast("⚠️ الإشعارات معطلة")
        }
    }
}