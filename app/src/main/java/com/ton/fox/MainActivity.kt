package com.ton.fox

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================================================
// [CONSTANTS]
// ============================================================
private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_VOICE = 9001
private const val REQ_IMPORT = 9002
private const val REQ_NOTIFY = 9003
private const val REQ_CAMERA_OCR = 9004
private const val REQ_CAPTURE_PHOTO = 9005
private const val REQ_PICK_AVATAR = 9006
private const val CHANNEL_ID_DAILY = "ton_fox_daily_word"
private const val CHANNEL_ID_STREAK = "ton_fox_streak"

// ============================================================
// [UTILS]
// ============================================================
private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

private fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

// ============================================================
// [DATA MODELS]
// ============================================================
data class Lesson(
    val id: Int,
    val title: String,
    val desc: String,
    val lines: List<String>,
    val ar: String,
    val fr: String,
    val difficulty: String = "beginner"
)

data class UserBadge(
    val id: String,
    val title: String,
    val desc: String,
    val icon: String,
    val unlockedAt: Long = 0L
)

data class CustomWord(
    val id: String,
    val arabic: String,
    val franco: String,
    val tags: String = ""
)

data class FlashcardState(
    val word: String,
    val intervalDays: Int = 0,
    val easeFactor: Float = 2.5f,
    val nextReviewAt: Long = 0L,
    val correctCount: Int = 0,
    val wrongCount: Int = 0
)

// ============================================================
// [BACKGROUND: Daily Word Scheduler]
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
            if (Build.VERSION.SDK_INT >= 31) {
                val canExact = alarm.canScheduleExactAlarms()
                if (canExact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
                else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending)
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
        val words = listOf(
            "صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi",
            "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam",
            "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak"
        )
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        val w = words[day % words.size]

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_DAILY, "TON FOX Word", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = Intent(context, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(context, 442, open, flags)

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID_DAILY) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TON FOX 🦊 · كلمة اليوم")
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
// [WIDGET — Word of the Day]
// ============================================================
class TonFoxWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_2)
            val prefs = context.getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
            val today = prefs.getString("widget_today", null) ?: "افتح التطبيق لتحميل كلمة اليوم"
            val franco = prefs.getString("widget_today_fr", null) ?: ""
            views.setTextViewText(android.R.id.text1, today)
            views.setTextViewText(android.R.id.text2, franco)
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(android.R.id.text1, open)
            manager.updateAppWidget(id, views)
        }
    }
}

// ============================================================
// [QUICK SETTINGS WIDGET — Stub via RemoteViews handled in manifest]
// ============================================================

// ============================================================
// [MAIN ACTIVITY]
// ============================================================
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    // --- Colors (Royal Obsidian + Alternative Palettes) ---
    private var colorBg = Color.parseColor("#0B0914")
    private var colorPanel = Color.parseColor("#171324")
    private var colorGold = Color.parseColor("#FFB800")
    private var colorGoldLight = Color.parseColor("#F3D98B")
    private var colorText = Color.parseColor("#F5F5F5")
    private var colorMuted = Color.parseColor("#8A8F9C")
    private var colorBorder = Color.parseColor("#332B45")
    private var isDarkTheme = true
    private var accentMode = "gold"

    // --- State ---
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var shade: View
    private var sourceText: EditText? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsSpeed = 1.0f
    private var ttsAutoSpeak = false
    private var currentDialect = "العراقية"
    private var score = 0
    private var level = 1
    private var streakCount = 0
    private var lastOpenDate = ""
    private var soundEnabled = true
    private var fontChoice = "default"

    private val history = mutableListOf<JSONObject>()
    private val favorites = mutableListOf<JSONObject>()
    private val completed = mutableSetOf<Int>()
    private val unlockedBadges = mutableSetOf<String>()
    private val personalDictionary = mutableListOf<CustomWord>()
    private val flashcardStates = mutableMapOf<String, FlashcardState>()
    private val usedDiacritics = mutableMapOf<String, Int>() // unused but reserved
    private var recentDictionarySearches = mutableListOf<String>()
    private var challengeCompletedDate = ""
    private var pointsToday = 0
    private var quizAttempts = 0
    private var quizCorrect = 0

    private lateinit var vibrator: Vibrator
    private var soundPool: SoundPool? = null
    private var soundClick = 0
    private var soundSuccess = 0
    private var soundFail = 0

    private var currentPhotoUri: Uri? = null
    private var pendingOcrResult: String? = null

    // --- Dialect dictionaries ---
    private val dialectDictIraqi = linkedMapOf(
        "شلونك" to "shlonak", "شكو ماكو" to "shako mako", "هسه" to "hassa",
        "زين" to "zain", "باچر" to "bachir", "وينك" to "wainak", "أريد" to "areed"
    )
    private val dialectDictEgyptian = linkedMapOf(
        "ازيك" to "ezayak", "عامل ايه" to "3amel eh", "كده" to "kda",
        "معلش" to "ma3lesh", "يعني" to "ya3ni", "كمان" to "kman", "برضه" to "bardu"
    )
    private val dialectDictGulf = linkedMapOf(
        "شلونك" to "shloonk", "وش أخبارك" to "wesh akhbarak", "زين" to "zain",
        "باجر" to "bacher", "وناسة" to "wnasa", "قواك الله" to "qwak allah"
    )
    private val dialectDictLevantine = linkedMapOf(
        "كيفك" to "keefak", "شو الأخبار" to "shu lakhbar", "منيح" to "mnee7",
        "هلأ" to "halla2", "بكرا" to "bukra", "شغل" to "shughul"
    )

    private val dailyWords = listOf(
        "صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi",
        "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam",
        "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak", "أريد أتعلم" to "areed at3allam",
        "نلتقي قريباً" to "neltagi gareeban", "كل شيء بخير" to "kol shay bekhair",
        "تصبح على خير" to "tesba7 3ala khair", "ماكو مشكلة" to "mako moshkela"
    )

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

    private val badgeDefinitions = listOf(
        UserBadge("first", "أول ترجمة", "قم بأول ترجمة", "🎯"),
        UserBadge("ten", "10 ترجمات", "قم بـ10 ترجمات", "🔟"),
        UserBadge("hundred", "100 ترجمة", "قم بـ100 ترجمة", "💯"),
        UserBadge("save10", "حافظ الكلمات", "احفظ 10 عبارات", "💾"),
        UserBadge("five_lessons", "تلميذ مجتهد", "أكمل 5 دروس", "🎓"),
        UserBadge("streak7", "أسبوع متواصل", "7 أيام متتالية", "🔥"),
        UserBadge("streak30", "شهر ملكي", "30 يوماً متتالياً", "🏆"),
        UserBadge("first_quiz", "أول اختبار", "أتم أول اختبار", "🧪"),
        UserBadge("perfect_quiz", "علامة كاملة", "اختبار بعلامة كاملة", "⭐"),
        UserBadge("share", "ناشر", "شارك ترجمة كصورة", "📤")
    )

    // ============================================================
    // [LIFECYCLE]
    // ============================================================
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f
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

        soundPool = SoundPool.Builder().setMaxStreams(3).build()
        soundClick = soundPool?.load(this, android.R.raw.sound_click, 1) ?: 0
        soundSuccess = soundPool?.load(this, android.R.raw.sound_success, 1) ?: 0
        soundFail = soundPool?.load(this, android.R.raw.sound_fail, 1) ?: 0

        prefs = getSharedPreferences("ton_fox_data", MODE_PRIVATE)
        loadSettings()
        applyTheme()
        loadData()

        tts = TextToSpeech(this, this)
        createNotificationChannels()
        askNotificationPermission()
        DailyWordScheduler.schedule(this)
        registerShortcuts()
        updateWidgetData()
        updateStreakOnOpen()

        buildShell()
        showHome()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.speechRate = ttsSpeed
            if (prefs.getBoolean("speak_on_open", true)) root.postDelayed({ speakDailyWord() }, 700)
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        soundPool?.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) toggleDrawer(false)
        else if (page.tag?.toString() != "home_tag") showHome()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ============================================================
    // [HELPERS]
    // ============================================================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(ms)
        } catch (_: Exception) {}
    }

    private fun playSound(id: Int) {
        if (!soundEnabled || id == 0) return
        try { soundPool?.play(id, 1f, 1f, 1, 0, 1f) } catch (_: Exception) {}
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
            if (fontChoice == "serif") typeface = Typeface.SERIF
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
                    playSound(soundClick); vibrate(15); action()
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
        val c = card(); c.block()
        page.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) })
    }

    private fun addAction(t: String, primary: Boolean = false, action: () -> Unit) {
        page.addView(button(t, primary, action), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

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
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$s\n\nTON FOX 🦊")
        }
        startActivity(Intent.createChooser(i, "مشاركة"))
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID_DAILY, "TON FOX Word", NotificationManager.IMPORTANCE_DEFAULT))
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID_STREAK, "TON FOX Streak", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        }
    }

    private fun registerShortcuts() {
        if (Build.VERSION.SDK_INT >= 25) {
            try {
                val mgr = getSystemService(ShortcutManager::class.java)
                val shortcuts = listOf(
                    ShortcutInfo.Builder(this, "translate").setShortLabel("ترجم فوراً").setLongLabel("فتح المترجم").setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_edit)).setIntent(Intent(this, MainActivity::class.java).putExtra("screen", "translator").setAction(Intent.ACTION_VIEW)).build(),
                    ShortcutInfo.Builder(this, "dictionary").setShortLabel("القاموس").setLongLabel("فتح القاموس").setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_search)).setIntent(Intent(this, MainActivity::class.java).putExtra("screen", "dictionary").setAction(Intent.ACTION_VIEW)).build(),
                    ShortcutInfo.Builder(this, "daily").setShortLabel("كلمة اليوم").setLongLabel("كلمة اليوم").setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_today)).setIntent(Intent(this, MainActivity::class.java).putExtra("screen", "home").setAction(Intent.ACTION_VIEW)).build()
                )
                mgr?.dynamicShortcuts = shortcuts
            } catch (_: Exception) {}
        }
    }

    private fun updateWidgetData() {
        val w = todaysWord()
        prefs.edit().putString("widget_today", w.first).putString("widget_today_fr", w.second).apply()
        val intent = Intent(this, TonFoxWidget::class.java).apply { action = AppWidgetManager.ACTION_APPWIDGET_UPDATE }
        sendBroadcast(intent)
    }

    // ============================================================
    // [SETTINGS & THEME]
    // ============================================================
    private fun loadSettings() {
        isDarkTheme = prefs.getBoolean("theme_dark", true)
        accentMode = prefs.getString("accent", "gold") ?: "gold"
        ttsSpeed = prefs.getFloat("tts_speed", 1.0f).coerceIn(0.5f, 2.0f)
        ttsAutoSpeak = prefs.getBoolean("auto_speak", false)
        soundEnabled = prefs.getBoolean("sound", true)
        fontChoice = prefs.getString("font", "default") ?: "default"
    }

    private fun applyTheme() {
        if (isDarkTheme) {
            colorBg = Color.parseColor("#0B0914")
            colorPanel = Color.parseColor("#171324")
            colorText = Color.parseColor("#F5F5F5")
            colorMuted = Color.parseColor("#8A8F9C")
            colorBorder = Color.parseColor("#332B45")
        } else {
            colorBg = Color.parseColor("#F9F7F2")
            colorPanel = Color.parseColor("#FFFFFF")
            colorText = Color.parseColor("#171324")
            colorMuted = Color.parseColor("#5D5A66")
            colorBorder = Color.parseColor("#E0DBC8")
        }
        colorGold = when (accentMode) {
            "emerald" -> Color.parseColor("#00C48C")
            "ruby" -> Color.parseColor("#E03B5A")
            "sapphire" -> Color.parseColor("#3C7BF7")
            else -> Color.parseColor("#FFB800")
        }
        colorGoldLight = when (accentMode) {
            "emerald" -> Color.parseColor("#8AFFD6")
            "ruby" -> Color.parseColor("#FF9AB0")
            "sapphire" -> Color.parseColor("#9DB8FF")
            else -> Color.parseColor("#F3D98B")
        }
    }

    private fun saveSetting(k: String, v: Any) {
        val e = prefs.edit()
        when (v) {
            is String -> e.putString(k, v)
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is Boolean -> e.putBoolean(k, v)
        }
        e.apply()
    }

    // ============================================================
    // [DATA PERSISTENCE]
    // ============================================================
    private fun loadData() {
        score = prefs.getInt("score", 0)
        level = prefs.getInt("level", 1)
        streakCount = prefs.getInt("streak", 0)
        lastOpenDate = prefs.getString("last_open", "") ?: ""
        challengeCompletedDate = prefs.getString("challenge_done", "") ?: ""
        pointsToday = prefs.getInt("points_today", 0)
        quizAttempts = prefs.getInt("quiz_attempts", 0)
        quizCorrect = prefs.getInt("quiz_correct", 0)

        loadArray("history", history)
        loadArray("favorites", favorites)

        try {
            val a = JSONArray(prefs.getString("lessons", "[]") ?: "[]")
            for (i in 0 until a.length()) completed.add(a.optInt(i))
        } catch (_: Exception) {}

        try {
            val a = JSONArray(prefs.getString("badges", "[]") ?: "[]")
            for (i in 0 until a.length()) unlockedBadges.add(a.optString(i))
        } catch (_: Exception) {}

        try {
            val a = JSONArray(prefs.getString("personal_dict", "[]") ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                personalDictionary.add(
                    CustomWord(o.optString("id"), o.optString("ar"), o.optString("fr"), o.optString("tags"))
                )
            }
        } catch (_: Exception) {}

        try {
            val o = JSONObject(prefs.getString("flashcard_states", "{}") ?: "{}")
            for (k in o.keys()) {
                val s = o.optJSONObject(k) ?: continue
                flashcardStates[k] = FlashcardState(
                    word = k,
                    intervalDays = s.optInt("interval", 0),
                    easeFactor = s.optDouble("ease", 2.5).toFloat(),
                    nextReviewAt = s.optLong("next", 0L),
                    correctCount = s.optInt("ok", 0),
                    wrongCount = s.optInt("ko", 0)
                )
            }
        } catch (_: Exception) {}

        try {
            val a = JSONArray(prefs.getString("recent_searches", "[]") ?: "[]")
            for (i in 0 until a.length()) recentDictionarySearches.add(a.optString(i))
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
        val b = JSONArray().apply { unlockedBadges.forEach { put(it) } }
        val pd = JSONArray().apply {
            personalDictionary.forEach {
                put(JSONObject().put("id", it.id).put("ar", it.arabic).put("fr", it.franco).put("tags", it.tags))
            }
        }
        val fs = JSONObject().apply {
            flashcardStates.forEach { (k, s) ->
                put(k, JSONObject().apply {
                    put("interval", s.intervalDays); put("ease", s.easeFactor.toDouble())
                    put("next", s.nextReviewAt); put("ok", s.correctCount); put("ko", s.wrongCount)
                })
            }
        }
        val rs = JSONArray().apply { recentDictionarySearches.forEach { put(it) } }

        prefs.edit()
            .putInt("score", score).putInt("level", level)
            .putInt("streak", streakCount).putString("last_open", lastOpenDate)
            .putString("challenge_done", challengeCompletedDate)
            .putInt("points_today", pointsToday)
            .putInt("quiz_attempts", quizAttempts).putInt("quiz_correct", quizCorrect)
            .putString("history", h.toString()).putString("favorites", f.toString())
            .putString("lessons", l.toString()).putString("badges", b.toString())
            .putString("personal_dict", pd.toString())
            .putString("flashcard_states", fs.toString())
            .putString("recent_searches", rs.toString())
            .apply()
    }

    // ============================================================
    // [STREAK + BADGES]
    // ============================================================
    private fun updateStreakOnOpen() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (lastOpenDate == today) return
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(
            Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        )
        if (lastOpenDate == yesterday) streakCount++
        else streakCount = 1
        lastOpenDate = today
        pointsToday = 0
        saveData()
        checkStreakBadges()
    }

    private fun checkStreakBadges() {
        if (streakCount >= 7 && unlockedBadges.add("streak7")) toast("🎉 وسام: أسبوع متواصل!")
        if (streakCount >= 30 && unlockedBadges.add("streak30")) toast("🎉 وسام: شهر ملكي!")
        saveData()
    }

    private fun unlockBadge(id: String) {
        if (unlockedBadges.add(id)) {
            val b = badgeDefinitions.firstOrNull { it.id == id }
            if (b != null) toast("🎉 وسام جديد: ${b.title}")
            score += 30
            pointsToday += 30
            checkLevelUp()
            saveData()
        }
    }

    private fun checkLevelUp() {
        val old = level
        level = score / 100 + 1
        if (level > old) {
            playSound(soundSuccess)
            toast("⭐ ارتقيت للمستوى $level!")
        }
    }

    // ============================================================
    // [TRANSLATION ENGINE]
    // ============================================================
    private fun toFranco(s: String): String {
        // personal dict first
        personalDictionary.firstOrNull { it.arabic.trim() == s.trim() }?.let { return it.franco }
        // dialect-specific
        when (currentDialect) {
            "المصرية" -> dialectDictEgyptian[s.trim()]?.let { return it }
            "الخليجية" -> dialectDictGulf[s.trim()]?.let { return it }
            "الشامية" -> dialectDictLevantine[s.trim()]?.let { return it }
            else -> dialectDictIraqi[s.trim()]?.let { return it }
        }
        dictionary[s.trim()]?.let { return it }
        return s.map { arabicMap[it] ?: it.toString() }.joinToString("")
    }

    private fun toArabic(s: String): String {
        val reverse = dictionary.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
        val revPersonal = personalDictionary.associate { it.franco.lowercase(Locale.ROOT) to it.arabic }
        val revDialect = when (currentDialect) {
            "المصرية" -> dialectDictEgyptian.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
            "الخليجية" -> dialectDictGulf.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
            "الشامية" -> dialectDictLevantine.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
            else -> dialectDictIraqi.entries.associate { it.value.lowercase(Locale.ROOT) to it.key }
        }
        val lookup = revPersonal + revDialect + reverse
        lookup[s.trim().lowercase(Locale.ROOT)]?.let { return it }
        return s.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") {
            if (it.isBlank()) it else lookup[it.lowercase(Locale.ROOT)] ?: decodeWord(it)
        }
    }

    private fun decodeWord(value: String): String {
        val pairs = listOf(
            "sh" to "ش", "kh" to "خ", "gh" to "غ", "th" to "ث", "ch" to "چ",
            "aa" to "ا", "ee" to "ي", "oo" to "و",
            "2" to "ء", "3" to "ع", "5" to "خ", "6" to "ط", "7" to "ح", "8" to "غ", "9" to "ص",
            "a" to "ا", "b" to "ب", "t" to "ت", "j" to "ج", "d" to "د", "r" to "ر", "z" to "ز",
            "s" to "س", "f" to "ف", "q" to "ق", "k" to "ك", "l" to "ل", "m" to "م", "n" to "ن",
            "h" to "ه", "w" to "و", "y" to "ي"
        )
        val text = value.lowercase(Locale.ROOT); val out = StringBuilder(); var i = 0
        while (i < text.length) {
            val found = pairs.firstOrNull { text.startsWith(it.first, i) }
            if (found != null) { out.append(found.second); i += found.first.length } else { out.append(text[i]); i++ }
        }
        return out.toString()
    }

    // ============================================================
    // [SHELL / HEADER / DRAWER]
    // ============================================================
    private fun buildShell() {
        window.statusBarColor = colorBg
        window.navigationBarColor = colorBg
        root = FrameLayout(this).apply { setBackgroundColor(colorBg) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(colorPanel)
        }
        header.addView(button("☰", true) { toggleDrawer(true) }, LinearLayout.LayoutParams(dp(52), dp(48)))

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        heading.addView(label("TON FOX 🦊", 22f, colorGold, true))
        val subtitle = if (streakCount > 0) "🔥 $streakCount يوم متواصل" else "مترجم الفرانكو وأكاديمية التعلم"
        heading.addView(label(subtitle, 11f, colorMuted))
        header.addView(heading)

        // Streak flame clickable
        if (streakCount > 0) {
            val flame = label("🔥", 22f, colorGold)
            flame.setPadding(dp(8), dp(8), dp(8), dp(8))
            flame.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("🔥 سلسلة الأيام")
                    .setMessage("أنت متواصل منذ $streakCount يوماً!\nعد غداً لتحافظ على سلسلتك.")
                    .setPositiveButton("حسناً", null).show()
            }
            header.addView(flame)
        }

        // Page + scroll
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }

        column.addView(header)
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Shade
        shade = View(this).apply {
            setBackgroundColor(withAlpha(Color.BLACK, 0.8f))
            visibility = View.GONE
            setOnClickListener { toggleDrawer(false) }
        }
        root.addView(shade, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Drawer
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(colorPanel); layoutDirection = View.LAYOUT_DIRECTION_RTL
            visibility = View.GONE; elevation = dp(16).toFloat()
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
            "الرئيسية" to { showHome() },
            "المترجم" to { showTranslator() },
            "الأكاديمية" to { showAcademy() },
            "القاموس" to { showDictionary() },
            "قاموسي الشخصي" to { showPersonalDictionary() },
            "مولّد العبارات" to { showPhraseGenerator() },
            "مولّد الأسماء" to { showNameGenerator() },
            "بطاقات الحفظ (SRS)" to { showFlashcards() },
            "المفضلة والسجل" to { showSaved() },
            "الأوسمة" to { showBadges() },
            "المستويات" to { showLevels() },
            "التحدي اليومي" to { showDailyChallenge() },
            "مخطط التقدم" to { showProgressChart() },
            "المفضلة السريعة" to { showQuickPhrases() },
            "الإحصائيات" to { showStatistics() },
            "الإعدادات" to { showSettings() }
        )
        entries.forEach { (name, fn) ->
            drawer.addView(button(name) { toggleDrawer(false); fn() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { bottomMargin = dp(6) })
        }

        drawer.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        drawer.addView(label("المطور: N5CR4 · @N5CR4", 12f, colorMuted))
    }

    private fun toggleDrawer(show: Boolean) {
        if (show) {
            drawer.visibility = View.VISIBLE; shade.visibility = View.VISIBLE
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

    // ============================================================
    // [UTILS: DATE / WORD]
    // ============================================================
    private fun todaysWord(): Pair<String, String> {
        val n = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        return dailyWords[n % dailyWords.size]
    }

    private fun speakDailyWord() {
        if (!ttsReady) { toast("محرك النطق غير جاهز"); return }
        val w = todaysWord()
        tts?.language = Locale("ar")
        tts?.setSpeechRate(ttsSpeed)
        tts?.speak("كلمة اليوم: ${w.first}", TextToSpeech.QUEUE_FLUSH, null, "daily_ar")
        tts?.language = Locale.US
        tts?.speak("بالفرانكو: ${w.second}", TextToSpeech.QUEUE_ADD, null, "daily_fr")
    }

    private fun speak(s: String) {
        if (!ttsReady || s.isBlank()) return
        tts?.language = Locale.US
        tts?.setSpeechRate(ttsSpeed)
        tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sp")
    }

    // ============================================================
    // [TO BE CONTINUED in Part 2: Home, Translator, Academy]
    // ============================================================
    private fun showHome() {
        clearPage("الرئيسية")
        page.tag = "home_tag"
        val w = todaysWord()

        addCard {
            addView(label("أهلاً بك في TON FOX V4", 20f, colorGold, true))
            addView(label("ترجم وتعلّم الفرانكو خطوة بخطوة.", 15f))
            addView(label("معالجة محلية دون إنترنت · 40 ميزة", 12f, colorMuted))
        }
        addCard {
            addView(label("🌟 كلمة اليوم", 19f, colorGold, true))
            addView(label(w.first, 25f, colorText, true))
            addView(label(w.second, 22f, colorGold, true))
            addView(button("🔊 استمع", true) { speakDailyWord() })
            gap(this, 8)
            addView(button("نسخ الفرانكو") { copyText(w.second) })
        }
        addCard {
            addView(label("🔥 سلسلتك: $streakCount يوم", 18f, colorGold, true))
            addView(label("المستوى: $level · الخبرة: $score XP"))
            addView(label("الدروس: ${completed.size}/${lessons.size} · الترجمات: ${history.size}"))
            addView(label("أوسمة: ${unlockedBadges.size}/${badgeDefinitions.size}"))
        }
        addAction("📝 ابدأ الترجمة", true) { showTranslator() }
        addAction("🎓 الأكاديمية") { showAcademy() }
        addAction("📚 القاموس") { showDictionary() }
        addAction("🔥 التحدي اليومي", true) { showDailyChallenge() }
    }

    private fun showTranslator() {}
    private fun showAcademy() {}
    private fun showDictionary() {}
    private fun showPersonalDictionary() {}
    private fun showPhraseGenerator() {}
    private fun showNameGenerator() {}
    private fun showFlashcards() {}
    private fun showSaved() {}
    private fun showBadges() {}
    private fun showLevels() {}
    private fun showDailyChallenge() {}
    private fun showProgressChart() {}
    private fun showQuickPhrases() {}
    private fun showStatistics() {}
    private fun showSettings() {}

    private val lessons = listOf(
        Lesson(0, "مقدمة في الفرانكو", "تعرف على طريقة كتابة العربية بالحروف اللاتينية.", listOf("الفرانكو أسلوب غير رسمي لكتابة العربية.", "تختلف كتابة بعض الكلمات من شخص لآخر.", "السياق يساعد على الفهم."), "مرحبا", "mar7aba", "beginner"),
        Lesson(1, "الحروف الأساسية", "تعلم أشهر المقابلات بين الحروف.", listOf("ب = b", "ت = t", "ش = sh", "خ = kh", "ح = 7"), "كتاب", "ketab", "beginner"),
        Lesson(2, "الأرقام", "الأرقام تمثل أصواتاً عربية معينة.", listOf("2 = ء", "3 = ع", "5 = خ", "6 = ط", "7 = ح", "8 = غ", "9 = ص"), "حبيبي", "7abibi", "beginner"),
        Lesson(3, "تكوين الكلمات", "اجمع الحروف لتكوين كلمات واضحة.", listOf("اكتب الكلمة كما تنطقها.", "حافظ على نمط ثابت.", "لا توجد كتابة موحدة."), "شكرا", "shukran", "intermediate"),
        Lesson(4, "المحادثات", "تدرب على العبارات اليومية.", listOf("استخدم المسافات.", "اقرأ الجملة كاملة.", "اختصر باعتدال."), "كيف حالك", "keef 7alak", "intermediate"),
        Lesson(5, "اللهجة العراقية", "مفردات عراقية.", listOf("شلونك = shlonak", "شكو ماكو = shako mako", "هسه = hassa", "زين = zain"), "شلونك", "shlonak", "intermediate"),
        Lesson(6, "الاحتراف", "حسّن وضوح كتابتك.", listOf("اختر طريقة ثابتة.", "راجع الكلمات الملتبسة.", "لا تعتمد على التحويل الحرفي."), "اشتقتلك", "eshta2tellak", "advanced")
    )

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_VOICE -> {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!spoken.isNullOrBlank()) { showTranslator(); sourceText?.setText(spoken) }
            }
            REQ_IMPORT -> data?.data?.let { importBackupFrom(it) }
            REQ_CAMERA_OCR -> {
                val text = data?.getStringExtra("OCR_TEXT") ?: ""
                if (text.isNotBlank()) { pendingOcrResult = text; showTranslator(); sourceText?.setText(text) }
            }
            REQ_PICK_AVATAR -> {}
        }
    }

    private fun importBackupFrom(uri: Uri) {}
}
// ===== OVERLAP with Part 1 (last ~20 lines) =====
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (resultCode != RESULT_OK) return
    when (requestCode) {
        REQ_VOICE -> {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) { showTranslator(); sourceText?.setText(spoken) }
        }
        REQ_IMPORT -> data?.data?.let { importBackupFrom(it) }
        REQ_CAMERA_OCR -> {
            val text = data?.getStringExtra("OCR_TEXT") ?: ""
            if (text.isNotBlank()) { pendingOcrResult = text; showTranslator(); sourceText?.setText(text) }
        }
        REQ_PICK_AVATAR -> {}
    }
}

// ===== CONTINUATION (Part 2) — REAL IMPLEMENTATIONS =====
// User: delete Part 1's `importBackupFrom(uri) {}` stub and its closing `}`. Paste from here.

// Extended onActivityResult with capture-photo case (replaces Part 1 version above).
// REMOVE the block above if compiling standalone, and keep only this extended version.
// For the protocol's sake, the extended version is provided here:
private fun onActivityResultExtended(requestCode: Int, resultCode: Int, data: Intent?) {
    if (resultCode != RESULT_OK) return
    when (requestCode) {
        REQ_CAPTURE_PHOTO -> {
            AlertDialog.Builder(this)
                .setTitle("📷 صورة ملتقطة")
                .setMessage("ميزة OCR تحتاج مكتبة خارجية (ML Kit) وهي غير مضافة. اكتب النص الذي تراه في الصورة يدوياً:")
                .setPositiveButton("كتابة يدوية") { _, _ ->
                    showTranslator()
                    sourceText?.setText("")
                    toast("اكتب النص المرئي في الحقل")
                }
                .setNegativeButton("إلغاء", null).show()
        }
    }
}

private fun importBackupFrom(uri: Uri) {
    try {
        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("تعذر قراءة الملف")
        val j = JSONObject(raw)
        if (j.optString("app") != "TON FOX") { toast("ملف غير صالح"); return }
        history.clear(); favorites.clear(); completed.clear()
        unlockedBadges.clear(); personalDictionary.clear()
        val h = j.optJSONArray("history") ?: JSONArray()
        for (i in 0 until h.length()) h.optJSONObject(i)?.let { history.add(it) }
        val f = j.optJSONArray("favorites") ?: JSONArray()
        for (i in 0 until f.length()) f.optJSONObject(i)?.let { favorites.add(it) }
        val l = j.optJSONArray("lessons") ?: JSONArray()
        for (i in 0 until l.length()) completed.add(l.optInt(i))
        val b = j.optJSONArray("badges") ?: JSONArray()
        for (i in 0 until b.length()) unlockedBadges.add(b.optString(i))
        score = j.optInt("score", 0).coerceAtLeast(0)
        level = j.optInt("level", 1).coerceAtLeast(1)
        streakCount = j.optInt("streak", 0).coerceAtLeast(0)
        saveData()
        showHome()
        toast("تم الاستيراد بنجاح")
    } catch (e: Exception) { toast("فشل الاستيراد: ${e.message}") }
}

private fun saveHistory(input: String, output: String, direction: String, dialect: String = currentDialect) {
    history.add(0, JSONObject()
        .put("input", input).put("output", output)
        .put("direction", direction).put("dialect", dialect)
        .put("ts", System.currentTimeMillis()))
    while (history.size > 500) history.removeAt(history.lastIndex)
    saveData()
    unlockBadge("first")
    if (history.size >= 10) unlockBadge("ten")
    if (history.size >= 100) unlockBadge("hundred")
}

private fun addFavorite(input: String, output: String) {
    if (favorites.any { it.optString("input") == input && it.optString("output") == output }) {
        toast("محفوظة مسبقاً"); return
    }
    favorites.add(0, JSONObject()
        .put("input", input).put("output", output)
        .put("ts", System.currentTimeMillis()))
    saveData()
    unlockBadge("save10")
    playSound(soundSuccess)
    toast("⭐ تم الحفظ في المفضلة")
}

private fun addToPersonalDictionary(arabic: String, franco: String) {
    if (arabic.isBlank() || franco.isBlank()) { toast("حقول فارغة"); return }
    if (personalDictionary.any { it.arabic == arabic }) {
        toast("موجودة في قاموسك"); return
    }
    personalDictionary.add(CustomWord(
        id = UUID.randomUUID().toString().take(8),
        arabic = arabic, franco = franco
    ))
    saveData()
    playSound(soundSuccess)
    toast("📚 أضيفت إلى قاموسك")
}

// ============================================================
// [TRANSLATOR SCREEN — 12 features]
// ============================================================
private fun showTranslator() {
    clearPage("المترجم")
    page.tag = null

    val charCounter = label("0 حرف · 0 كلمة", 12f, colorMuted)

    val input = EditText(this).apply {
        hint = "اكتب النص هنا..."
        textSize = 18f; setTextColor(colorText); setHintTextColor(colorMuted)
        minLines = 4; gravity = Gravity.TOP or Gravity.RIGHT
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = shape(colorBg, 12, colorGold)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
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

    var liveMode = prefs.getBoolean("live_translate", true)
    val liveHandler = Handler(Looper.getMainLooper())
    val liveRunnable = object : Runnable {
        override fun run() {
            val s = input.text.toString().trim()
            if (s.isNotEmpty() && liveMode) {
                val r = toFranco(s)
                if (output.text.toString() != r) output.text = r
            }
        }
    }
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
            val text = s?.toString() ?: ""
            val chars = text.length
            val words = if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
            charCounter.text = "$chars حرف · $words كلمة"
            liveHandler.removeCallbacks(liveRunnable)
            if (liveMode) liveHandler.postDelayed(liveRunnable, 250)
        }
        override fun afterTextChanged(s: Editable?) {}
    })

    addCard {
        addView(label("النص المراد تحويله", 16f, colorGold, true))
        addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(charCounter)
        gap(this, 10)
        addView(label("اللهجة / النمط", 15f, colorGold, true))
        val spinner = Spinner(this@MainActivity)
        val dialects = arrayOf("العراقية", "المصرية", "الخليجية", "الشامية", "العربية العامة")
        spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dialects)
        spinner.setSelection(dialects.indexOf(currentDialect).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p: AdapterView<*>?) {}
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentDialect = dialects[pos]
                if (liveMode && input.text.isNotBlank()) output.text = toFranco(input.text.toString())
            }
        }
        addView(spinner)
        gap(this, 8)
        addView(button(if (liveMode) "⚡ الترجمة الفورية: مفعلة" else "⚡ الترجمة الفورية: متوقفة") {
            liveMode = !liveMode
            prefs.edit().putBoolean("live_translate", liveMode).apply()
            showTranslator()
        })
    }

    addCard {
        addView(label("النتيجة", 16f, colorGold, true))
        addView(output)
        gap(this, 8)
        val row1 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("📋 نسخ") { copyText(output.text.toString()) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row1.addView(button("🔊 نطق") { speak(output.text.toString()) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(row1)
        gap(this, 6)
        val row2 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("↗ مشاركة نص") { shareText(output.text.toString()) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row2.addView(button("📤 كصورة", true) { shareAsImage(output.text.toString()) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(row2)
    }

    addAction("↔️ عربي ← فرانكو", true) {
        val s = input.text.toString().trim()
        if (s.isBlank()) { toast("اكتب نصاً أولاً"); return@addAction }
        val r = toFranco(s)
        output.text = r
        saveHistory(s, r, "عربي ← فرانكو", currentDialect)
        hideKeyboard(input)
        if (ttsAutoSpeak) speak(r)
    }
    addAction("↔️ فرانكو ← عربي") {
        val s = input.text.toString().trim()
        if (s.isBlank()) { toast("اكتب نصاً أولاً"); return@addAction }
        val r = toArabic(s)
        output.text = r
        saveHistory(s, r, "فرانكو ← عربي", currentDialect)
        hideKeyboard(input)
        if (ttsAutoSpeak) speak(r)
    }
    addAction("🔁 ترجمة عكسية للتحقق (Back-Translation)") {
        val s = input.text.toString().trim()
        if (s.isBlank()) { toast("اكتب نصاً أولاً"); return@addAction }
        val franco = toFranco(s)
        val back = toArabic(franco)
        AlertDialog.Builder(this)
            .setTitle("التحقق من الفهم")
            .setMessage("الأصل: $s\n\nالفرانكو: $franco\n\nالعودة: $back\n\n${if (s.trim() == back.trim()) "✅ متطابق تماماً" else "⚠️ يوجد اختلاف بسيط (طبيعي في الفرانكو)"}")
            .setPositiveButton("حسناً", null).show()
    }
    addAction("📊 مقارنة اللهجات الأربع") {
        val s = input.text.toString().trim()
        if (s.isBlank()) { toast("اكتب كلمة أولاً"); return@addAction }
        val res = StringBuilder()
        res.append("العراقية: ").append(dialectDictIraqi[s] ?: "—").append("\n")
        res.append("المصرية: ").append(dialectDictEgyptian[s] ?: "—").append("\n")
        res.append("الخليجية: ").append(dialectDictGulf[s] ?: "—").append("\n")
        res.append("الشامية: ").append(dialectDictLevantine[s] ?: "—").append("\n")
        AlertDialog.Builder(this)
            .setTitle("مقارنة اللهجات لـ «$s»")
            .setMessage(res.toString())
            .setPositiveButton("حسناً", null).show()
    }
    addAction("⭐ إضافة إلى المفضلة") {
        if (output.text.toString() == "ستظهر الترجمة هنا..." || output.text.isBlank()) {
            toast("لا توجد ترجمة"); return@addAction
        }
        addFavorite(input.text.toString(), output.text.toString())
    }
    addAction("📚 حفظ في قاموسي الشخصي") {
        val ar = input.text.toString().trim()
        if (ar.isBlank()) { toast("اكتب كلمة أولاً"); return@addAction }
        addToPersonalDictionary(ar, toFranco(ar))
    }
    addAction("🔀 تبديل النص والنتيجة") {
        val old = input.text.toString()
        input.setText(output.text.toString())
        output.text = old
    }
    addAction("🎙 إدخال صوتي") { voiceInput() }
    addAction("📷 قراءة نص من صورة (تفتح الكاميرا)") { capturePhotoForOcr() }
    addAction("🕘 فتح آخر 20 ترجمة") { showRecentTranslationsQuick() }
}

private fun showRecentTranslationsQuick() {
    if (history.isEmpty()) { toast("لا توجد ترجمات سابقة"); return }
    val recent = history.take(20)
    val titles = recent.map {
        val dir = it.optString("direction", "")
        val inp = it.optString("input", "").take(30)
        "$dir · $inp"
    }.toTypedArray()
    AlertDialog.Builder(this)
        .setTitle("آخر 20 ترجمة")
        .setItems(titles) { _, i ->
            showTranslator()
            sourceText?.setText(recent[i].optString("input"))
        }
        .setNegativeButton("إغلاق", null).show()
}

private fun capturePhotoForOcr() {
    try {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) {
            toast("الكاميرا غير متوفرة"); return
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_CAPTURE_PHOTO)
    } catch (e: Exception) { toast("تعذر فتح الكاميرا") }
}

private fun voiceInput() {
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
    }
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_VOICE)
    } catch (_: Exception) { toast("التعرف الصوتي غير متاح") }
}

// ============================================================
// [ACADEMY SCREEN — Difficulty + Focus + Mini Story]
// ============================================================
private fun showAcademy() {
    clearPage("الأكاديمية")
    page.tag = null

    var filterDifficulty = "all"
    var focusMode = prefs.getBoolean("focus_mode", false)

    addCard {
        addView(label("تعلّم الفرانكو خطوة بخطوة", 20f, colorGold, true))
        addView(label("المستوى $level · $score XP · ${completed.size}/${lessons.size} مكتملة", 14f, colorMuted))
        gap(this, 8)
        addView(button(if (focusMode) "🧘 وضع التركيز: مفعل" else "🧘 تفعيل وضع التركيز") {
            focusMode = !focusMode
            prefs.edit().putBoolean("focus_mode", focusMode).apply()
            showAcademy()
        })
    }

    addCard {
        addView(label("مستوى الصعوبة", 16f, colorGold, true))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val diffs = listOf("all" to "الكل", "beginner" to "مبتدئ", "intermediate" to "متوسط", "advanced" to "متقدم")
        for ((key, name) in diffs) {
            val isSel = filterDifficulty == key
            row.addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (isSel) colorBg else colorText)
                background = shape(if (isSel) colorGold else colorPanel, 8, colorGold)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    filterDifficulty = key
                    showAcademy()
                }
            })
        }
        addView(row)
    }

    val filtered = if (filterDifficulty == "all") lessons else lessons.filter { it.difficulty == filterDifficulty }
    if (focusMode) {
        addCard {
            addView(label("🧘 التركيز: اختر درساً وادرسه دون تشتيت", 15f, colorGold, true))
            addView(label("وضع التركيز يخفي القائمة الجانبية والتنقل.", 12f, colorMuted))
        }
    }
    filtered.forEach { l ->
        addCard {
            val done = completed.contains(l.id)
            val diffLabel = when (l.difficulty) {
                "beginner" -> "🟢 مبتدئ"
                "intermediate" -> "🟡 متوسط"
                else -> "🔴 متقدم"
            }
            addView(label("${if (done) "✓" else "○"} الدرس ${l.id + 1}: ${l.title}", 17f, colorGold, true))
            addView(label(diffLabel, 12f, colorMuted))
            addView(label(l.desc, 14f, colorMuted))
            gap(this, 8)
            addView(button("فتح الدرس") { showLesson(l.id) })
        }
    }

    if (!focusMode) {
        addAction("📖 قصة تفاعلية قصيرة", true) { showMiniStory() }
        addAction("🧪 اختبار شامل", true) { startQuiz(-1) }
        addAction("🎯 اختبار مستوى محدد") {
            AlertDialog.Builder(this)
                .setTitle("اختر المستوى")
                .setItems(arrayOf("مبتدئ", "متوسط", "متقدم")) { _, which ->
                    val key = arrayOf("beginner", "intermediate", "advanced")[which]
                    startQuizDifficulty(key)
                }
                .setNegativeButton("إلغاء", null).show()
        }
    }
}

private fun showMiniStory() {
    val story = "في صباحٍ جميل، استيقظ «علي» من نومه وقال: صباح الخير يا أمي! ابتسمت أمه وقالت: صباح النور يا حبيبي. خرج علي إلى الشارع، فقابل صديقه «حسن». قال له: شلونك يا حسن؟ رد حسن: زين الحمد لله، شكو ماكو؟ ضحك علي وقال: كل شيء تمام. سارا معاً إلى المدرسة، وهما يتحدثان عن الامتحان القادم. قال حسن: أنا أريد أن أدرس، لكن الوقت قصير. قال علي: لا تقلق، سنتعلّم معاً. وفي الطريق، رأيا بائع الكتب القديمة، فتوقفا عنده. اشترى علي كتاباً، وقال: شكراً جزيلاً. وصل الاثنان إلى المدرسة قبل الجرس بدقيقة واحدة، وضحكا قائلين: يلا، نلحق!"
    clearPage("قصة تفاعلية قصيرة")
    addCard {
        addView(label("📖 حكاية صباح علي وحسن", 20f, colorGold, true))
        addView(label(story, 15f, colorText))
    }
    addCard {
        addView(label("✨ تحويل فوري للجملة الأولى", 16f, colorGold, true))
        addView(label(toFranco("صباح الخير يا أمي!"), 18f, colorGold, true))
        addView(label(toFranco("شلونك يا حسن؟"), 18f, colorGold, true))
        addView(label(toFranco("كل شيء تمام."), 18f, colorGold, true))
    }
    addCard {
        addView(label("🎧 استمع للقصة", 16f, colorGold, true))
        addView(button("نطق القصة كاملة", true) { speak(story) })
    }
    addAction("العودة للأكاديمية", true) { showAcademy() }
}

private fun showLesson(i: Int) {
    val l = lessons[i]
    clearPage(l.title)
    addCard {
        addView(label("الدرس ${i + 1} من ${lessons.size}", 14f, colorGold, true))
        addView(label(l.desc, 15f))
        l.lines.forEachIndexed { n, line -> addView(label("${n + 1}. $line", 15f)) }
        gap(this, 10)
        addView(button("🔊 نطق الجملة الأساسية") { speak(l.ar) })
    }
    val answer = EditText(this).apply {
        hint = "اكتب التحويل بالفرانكو"
        setTextColor(colorText); setHintTextColor(colorMuted)
        background = shape(colorBg, 12, colorGold)
        setPadding(dp(14), dp(14), dp(14), dp(14)); textSize = 17f
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    addCard {
        addView(label("تدريب: ${l.ar}", 20f, colorGold, true))
        addView(answer)
        gap(this, 8)
        addView(button("تحقق من الإجابة", true) {
            val typed = answer.text.toString().replace(" ", "").lowercase(Locale.ROOT)
            val expected = l.fr.replace(" ", "").lowercase(Locale.ROOT)
            if (typed == expected || typed == toFranco(l.ar).replace(" ", "").lowercase(Locale.ROOT)) {
                val wasNew = completed.add(i)
                if (wasNew) { score += 20; pointsToday += 20 }
                level = score / 100 + 1
                if (completed.size >= 5) unlockBadge("five_lessons")
                saveData()
                playSound(soundSuccess)
                toast("✔ إجابة صحيحة +20 XP")
                showLesson(i)
            } else {
                playSound(soundFail)
                toast("حاول مرة أخرى: ${l.fr}")
            }
        })
    }
    if (completed.contains(i)) {
        addCard {
            addView(label("✅ أكملت هذا الدرس", 16f, colorGold, true))
            addView(button("🧪 اختبار على هذا الدرس") { startQuiz(i) })
        }
    }
    addAction("🔊 نطق الجملة", false) { speak(l.ar) }
    addAction("📝 تحويل الدرس كاملاً", false) {
        val text = l.lines.joinToString("\n") + "\n\n${l.ar} = ${l.fr}"
        AlertDialog.Builder(this).setTitle("الدرس كاملاً").setMessage(text).setPositiveButton("حسناً", null).show()
    }
    addAction("العودة للأكاديمية", true) { showAcademy() }
}

private fun startQuizDifficulty(difficulty: String) {
    val qs = lessons.filter { it.difficulty == difficulty }.shuffled().take(5)
    if (qs.isEmpty()) { toast("لا توجد دروس في هذا المستوى"); return }
    startQuizWithList(qs)
}

private fun startQuizWithList(questions: List<Lesson>) {
    var pos = 0; var correct = 0
    quizAttempts++
    fun next() {
        if (pos >= questions.size) {
            val earned = correct * 10
            score += earned; level = score / 100 + 1
            pointsToday += earned
            quizCorrect += correct
            unlockBadge("first_quiz")
            if (correct == questions.size) unlockBadge("perfect_quiz")
            saveData()
            playSound(soundSuccess)
            clearPage("نتيجة الاختبار")
            addCard {
                addView(label("انتهى الاختبار!", 23f, colorGold, true))
                addView(label("الإجابات الصحيحة: $correct/${questions.size}", 17f))
                addView(label("+$earned XP", 18f, colorGold))
                if (correct == questions.size) addView(label("🌟 علامة كاملة!", 18f, colorGold, true))
            }
            addAction("العودة للأكاديمية", true) { showAcademy() }
            return
        }
        val l = questions[pos]
        val expected = toFranco(l.ar)
        val choices = (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3))
            .distinct().shuffled()
        clearPage("السؤال ${pos + 1}/${questions.size}")
        addCard {
            addView(label("حوّل إلى فرانكو:", 16f, colorMuted))
            addView(label(l.ar, 24f, colorGold, true))
            gap(this, 8)
            choices.forEach { choice ->
                addView(button(choice) {
                    if (choice == expected) {
                        correct++; playSound(soundSuccess)
                    } else playSound(soundFail)
                    pos++; next()
                })
                gap(this, 6)
            }
        }
    }
    next()
}

// ============================================================
// [DICTIONARY SCREEN — Recent searches + dialect awareness]
// ============================================================
private fun showDictionary() {
    clearPage("القاموس")
    page.tag = null
    val search = EditText(this).apply {
        hint = "ابحث بالعربية أو الفرانكو"
        setTextColor(colorText); setHintTextColor(colorMuted)
        background = shape(colorPanel, 12, colorGold)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }
    page.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })

    // recent searches chips
    if (recentDictionarySearches.isNotEmpty()) {
        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (t in recentDictionarySearches.take(5)) {
            chipsRow.addView(TextView(this).apply {
                text = t
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(colorGold)
                background = shape(colorPanel, 12, colorGold)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(6) }
                setOnClickListener { search.setText(t) }
            })
        }
        page.addView(HorizontalScrollView(this).apply { addView(chipsRow) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    page.addView(results)

    fun addRecent(term: String) {
        if (term.isBlank()) return
        recentDictionarySearches.remove(term)
        recentDictionarySearches.add(0, term)
        while (recentDictionarySearches.size > 20) recentDictionarySearches.removeAt(recentDictionarySearches.lastIndex)
        saveData()
    }

    fun render(q: String) {
        results.removeAllViews()
        val merged = linkedMapOf<String, String>()
        merged.putAll(dictionary)
        personalDictionary.forEach { merged[it.arabic] = it.franco }
        val filtered = merged.filter { it.key.contains(q, true) || it.value.contains(q, true) }
        if (filtered.isEmpty()) {
            val c = card()
            c.addView(label("لا نتائج لـ «$q»", 15f, colorMuted))
            c.addView(button("➕ إضافة إلى قاموسي") {
                if (q.isNotBlank()) {
                    addToPersonalDictionary(q, toFranco(q))
                    render(q)
                }
            })
            results.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
        filtered.forEach { (ar, fr) ->
            val c = card()
            c.addView(label(ar, 19f, colorText, true))
            c.addView(label(fr, 18f, colorGold))
            val isPersonal = personalDictionary.any { it.arabic == ar }
            if (isPersonal) c.addView(label("⭐ كلمة من قاموسك الشخصي", 11f, colorGold))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("📋 نسخ") { copyText(fr) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            row.addView(button("🔊 نطق") { speak(fr) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            row.addView(button("⭐") { addFavorite(ar, fr) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            c.addView(row)
            results.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
    }
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
            val q = s?.toString().orEmpty()
            render(q)
            if (q.length >= 2) addRecent(q)
        }
        override fun afterTextChanged(s: Editable?) {}
    })
    render("")
}
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                val q = s?.toString().orEmpty()
                render(q)
                if (q.length >= 2) addRecent(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
    }

    // ============================================================
    // [PERSONAL DICTIONARY]
    // ============================================================
    private fun showPersonalDictionary() {
        clearPage("قاموسي الشخصي")
        page.tag = null
        addCard {
            addView(label("📚 ${personalDictionary.size} كلمة في قاموسك", 17f, colorGold, true))
            addView(label("هذه الكلمات تُستخدم قبل القاموس العام عند الترجمة.", 12f, colorMuted))
        }
        if (personalDictionary.isEmpty()) {
            addCard {
                addView(label("لم تُضف أي كلمة بعد.", 15f, colorMuted))
                addView(label("استخدم زر «حفظ في قاموسي» من شاشة المترجم.", 12f, colorMuted))
            }
        } else {
            personalDictionary.toList().forEach { w ->
                addCard {
                    addView(label(w.arabic, 19f, colorText, true))
                    addView(label(w.franco, 18f, colorGold))
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(button("📋 نسخ") { copyText(w.franco) },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                    row.addView(button("✏️ تعديل") { showEditPersonalWord(w) },
                        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                    row.addView(button("🗑 حذف") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("حذف؟").setMessage("حذف «${w.arabic}» من قاموسك؟")
                            .setPositiveButton("حذف") { _, _ ->
                                personalDictionary.remove(w); saveData(); showPersonalDictionary()
                            }
                            .setNegativeButton("إلغاء", null).show()
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    addView(row)
                }
            }
        }
        addAction("➕ إضافة كلمة جديدة", true) { showAddPersonalWord() }
    }

    private fun showAddPersonalWord() {
        val ar = EditText(this).apply {
            hint = "الكلمة بالعربية"
            setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorBg, 10, colorGold); setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val fr = EditText(this).apply {
            hint = "الكتابة بالفرانكو"
            setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorBg, 10, colorGold); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(4))
        }
        layout.addView(label("الكلمة بالعربية:", 13f, colorMuted)); layout.addView(ar)
        gap(layout, 8)
        layout.addView(label("الكتابة بالفرانكو:", 13f, colorMuted)); layout.addView(fr)
        AlertDialog.Builder(this)
            .setTitle("إضافة كلمة")
            .setView(layout)
            .setPositiveButton("حفظ") { _, _ ->
                addToPersonalDictionary(ar.text.toString(), fr.text.toString())
                showPersonalDictionary()
            }
            .setNegativeButton("إلغاء", null).show()
    }

    private fun showEditPersonalWord(w: CustomWord) {
        val fr = EditText(this).apply {
            setText(w.franco); setTextColor(colorText)
            background = shape(colorBg, 10, colorGold); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل «${w.arabic}»")
            .setView(fr)
            .setPositiveButton("حفظ") { _, _ ->
                val idx = personalDictionary.indexOfFirst { it.id == w.id }
                if (idx >= 0) {
                    personalDictionary[idx] = w.copy(franco = fr.text.toString())
                    saveData(); showPersonalDictionary()
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ============================================================
    // [PHRASE GENERATOR — 6 categories]
    // ============================================================
    private fun showPhraseGenerator() {
        clearPage("مولّد العبارات")
        page.tag = null
        val categories = linkedMapOf(
            "تحيات" to listOf("صباح الخير", "مساء الخير", "أهلاً وسهلاً", "كيف حالك", "شلونك", "أهلاً بك"),
            "مجاملات" to listOf("أنت رائع", "أحسنت", "مبروك", "بالتوفيق", "الله يوفقك", "كل عام وأنت بخير"),
            "اعتذار" to listOf("آسف", "أعتذر", "لم أقصد", "سامحني", "حقك علي"),
            "شكر" to listOf("شكراً", "شكراً جزيلاً", "ممتن لك", "الله يعطيك العافية", "جزاك الله خيراً"),
            "دعاء" to listOf("الله يسعدك", "الله يحفظك", "الله يرزقك", "الله يشافيك", "الله يعينك"),
            "وداع" to listOf("إلى اللقاء", "أراك لاحقاً", "تصبح على خير", "في أمان الله", "مع السلامة")
        )
        val rnd = Random()
        addCard {
            addView(label("🎯 اختر فئة أو ولّد عشوائياً", 16f, colorGold, true))
            categories.keys.forEach { cat ->
                addView(button("🎲 $cat") {
                    val phrase = categories[cat]!!.random()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(phrase)
                        .setMessage("الفرانكو: ${toFranco(phrase)}")
                        .setPositiveButton("نسخ") { _, _ -> copyText(toFranco(phrase)) }
                        .setNeutralButton("نطق") { _, _ -> speak(phrase) }
                        .setNegativeButton("إغلاق", null).show()
                })
                gap(this, 6)
            }
            addView(button("🎰 جملة عشوائية تماماً", true) {
                val all = categories.values.flatten()
                val phrase = all[rnd.nextInt(all.size)]
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(phrase)
                    .setMessage("الفرانكو: ${toFranco(phrase)}")
                    .setPositiveButton("نسخ") { _, _ -> copyText(toFranco(phrase)) }
                    .setNeutralButton("نطق") { _, _ -> speak(phrase) }
                    .setNegativeButton("إغلاق", null).show()
            })
        }
    }

    // ============================================================
    // [NAME GENERATOR — gender aware]
    // ============================================================
    private fun showNameGenerator() {
        clearPage("مولّد الأسماء")
        page.tag = null
        val maleNames = listOf("محمد", "أحمد", "علي", "حسن", "حسين", "كرار", "مصطفى", "حيدر", "يوسف", "آدم", "ليث", "زين")
        val femaleNames = listOf("مريم", "زهراء", "فاطمة", "سارة", "ياسمين", "نور", "هند", "آية", "رقية", "زينب", "خديجة", "عائشة")
        var gender = prefs.getString("name_gender", "male") ?: "male"
        val output = label("اضغط لتوليد اسم", 27f, colorGold, true)
        output.gravity = Gravity.CENTER
        addCard {
            addView(label("اختر الجنس:", 14f, colorMuted))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("👨 ذكر") { gender = "male"; saveSetting("name_gender", gender); showNameGenerator() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            row.addView(button("👩 أنثى") { gender = "female"; saveSetting("name_gender", gender); showNameGenerator() },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(row)
            gap(this, 12)
            addView(output)
            gap(this, 10)
            addView(button("✨ توليد اسم جديد", true) {
                val list = if (gender == "female") femaleNames else maleNames
                val name = list.random()
                output.text = "$name\n${toFranco(name)}"
                playSound(soundSuccess)
            })
            val row2 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row2.addView(button("📋 نسخ الاسم") { copyText(output.text.toString().substringBefore("\n")) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
            row2.addView(button("📋 نسخ الفرانكو") { copyText(output.text.toString().substringAfter("\n", "")) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(row2)
        }
    }

    // ============================================================
    // [SRS FLASHCARDS — SM-2]
    // ============================================================
    private fun showFlashcards() {
        clearPage("بطاقات الحفظ (SRS)")
        page.tag = null
        val now = System.currentTimeMillis()
        val allWords = (dictionary.keys + personalDictionary.map { it.arabic }).toList()
        val due = allWords.filter {
            val s = flashcardStates[it]
            s == null || s.nextReviewAt <= now
        }
        val learned = flashcardStates.size
        val correctTotal = flashcardStates.values.sumOf { it.correctCount }

        addCard {
            addView(label("🧠 التكرار المتباعد (SM-2)", 17f, colorGold, true))
            addView(label("مستحق اليوم: ${due.size} · في قاعدتك: $learned · صحيحة إجمالاً: $correctTotal", 13f, colorMuted))
        }

        if (due.isEmpty()) {
            addCard {
                addView(label("✨ لا توجد بطاقات مستحقة الآن!", 16f, colorGold, true))
                addView(label("عد لاحقاً أو راجع عشوائياً.", 12f, colorMuted))
                addView(button("🎲 مراجعة عشوائية (5 كلمات)", true) {
                    startFlashcardSession(allWords.shuffled().take(5))
                })
            }
        } else {
            addCard {
                addView(label("📚 ابدأ المراجعة (${due.size} كلمة)", 16f, colorGold, true))
                addView(button("▶️ ابدأ المراجعة", true) { startFlashcardSession(due.take(20)) })
                addView(button("🎲 مراجعة عشوائية (5 كلمات)") { startFlashcardSession(allWords.shuffled().take(5)) })
            }
        }

        addCard {
            addView(label("⚙️ تقدم التعلم", 16f, colorGold, true))
            val total = allWords.size
            val pct = if (total > 0) (learned * 100) / total else 0
            addView(label("تعلمت $learned من $total كلمة ($pct%)", 14f))
            val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = pct
                progressTintList = ColorStateList.valueOf(colorGold)
            }
            addView(bar)
            addView(button("🗑️ مسح حالات التعلم") {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("مسح حالات التعلم؟").setMessage("ستفقد تقدمك في التكرار المتباعد.")
                    .setPositiveButton("مسح") { _, _ ->
                        flashcardStates.clear(); saveData(); showFlashcards()
                    }.setNegativeButton("إلغاء", null).show()
            })
        }
    }

    private fun startFlashcardSession(words: List<String>) {
        if (words.isEmpty()) { toast("لا توجد كلمات"); return }
        var pos = 0; var correct = 0
        fun next() {
            if (pos >= words.size) {
                val earned = correct * 5
                score += earned; pointsToday += earned
                checkLevelUp(); saveData(); playSound(soundSuccess)
                clearPage("انتهت المراجعة")
                addCard {
                    addView(label("✅ انتهت المراجعة!", 22f, colorGold, true))
                    addView(label("صحيحة: $correct/${words.size}", 16f))
                    addView(label("+$earned XP", 18f, colorGold, true))
                }
                addAction("العودة للبطاقات", true) { showFlashcards() }
                return
            }
            val word = words[pos]
            val franco = toFranco(word)
            clearPage("بطاقة ${pos + 1}/${words.size}")
            addCard {
                addView(label("الكلمة العربية:", 13f, colorMuted))
                addView(label(word, 32f, colorGold, true))
                gap(this, 12)
                val answerView = label("• • • • •", 24f, colorMuted, true)
                answerView.gravity = Gravity.CENTER
                addView(answerView)
                addView(button("👁 إظهار الإجابة", true) {
                    answerView.text = franco
                    answerView.setTextColor(colorGold)
                })
                gap(this, 12)
                addView(label("هل تذكرتها؟", 14f, colorMuted))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("✓ تذكرت") {
                    val s = flashcardStates[word] ?: FlashcardState(word)
                    val newEase = (s.easeFactor + 0.1f).coerceAtMost(3.0f)
                    val newInterval = if (s.intervalDays == 0) 1 else (s.intervalDays * newEase).toInt().coerceIn(1, 365)
                    flashcardStates[word] = s.copy(
                        intervalDays = newInterval, easeFactor = newEase,
                        nextReviewAt = System.currentTimeMillis() + newInterval * 24L * 3600 * 1000,
                        correctCount = s.correctCount + 1
                    )
                    correct++; pos++
                    playSound(soundSuccess); saveData(); next()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                row.addView(button("✗ لم أتذكر") {
                    val s = flashcardStates[word] ?: FlashcardState(word)
                    val newEase = (s.easeFactor - 0.2f).coerceAtLeast(1.3f)
                    flashcardStates[word] = s.copy(
                        intervalDays = 1, easeFactor = newEase,
                        nextReviewAt = System.currentTimeMillis() + 24L * 3600 * 1000,
                        wrongCount = s.wrongCount + 1
                    )
                    pos++; playSound(soundFail); saveData(); next()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }
        }
        next()
    }

    // ============================================================
    // [SAVED — FAVORITES + HISTORY]
    // ============================================================
    private fun showSaved() {
        clearPage("المفضلة والسجل")
        page.tag = null
        addCard { addView(label("⭐ المفضلة (${favorites.size})", 19f, colorGold, true)) }
        if (favorites.isEmpty()) addCard { addView(label("لا توجد عبارات محفوظة.", 15f, colorMuted)) }
        favorites.toList().forEach { item ->
            addCard {
                addView(label(item.optString("input"), 17f, colorText, true))
                addView(label(item.optString("output"), 17f, colorGold))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("📋 نسخ") { copyText(item.optString("output")) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                row.addView(button("🔊") { speak(item.optString("output")) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                row.addView(button("🗑") {
                    favorites.remove(item); saveData(); showSaved()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }
        }

        addCard { addView(label("🕘 سجل الترجمات (${history.size})", 19f, colorGold, true)) }
        if (history.isEmpty()) addCard { addView(label("لا توجد ترجمات سابقة.", 15f, colorMuted)) }
        history.take(50).forEach { item ->
            addCard {
                addView(label(item.optString("direction", ""), 12f, colorGold))
                addView(label(item.optString("input"), 16f, colorText, true))
                addView(label(item.optString("output"), 16f, colorMuted))
                val ts = item.optLong("ts", 0L)
                if (ts > 0) addView(label(SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(ts)), 11f, colorMuted))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("♻️ استخدام") {
                    showTranslator(); sourceText?.setText(item.optString("input"))
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                row.addView(button("⭐ حفظ") { addFavorite(item.optString("input"), item.optString("output")) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }
        }
        addAction("🗑 مسح السجل") {
            AlertDialog.Builder(this).setTitle("مسح السجل؟").setMessage("سيتم حذف ${history.size} ترجمة.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف") { _, _ -> history.clear(); saveData(); showSaved() }.show()
        }
    }

    // ============================================================
    // [BADGES — 10 achievements]
    // ============================================================
    private fun showBadges() {
        clearPage("الأوسمة")
        page.tag = null
        addCard {
            addView(label("🏆 الأوسمة", 20f, colorGold, true))
            addView(label("مفتوحة: ${unlockedBadges.size} من ${badgeDefinitions.size}", 13f, colorMuted))
            val pct = if (badgeDefinitions.isEmpty()) 0 else (unlockedBadges.size * 100) / badgeDefinitions.size
            val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = pct
                progressTintList = ColorStateList.valueOf(colorGold)
            }
            addView(bar)
        }
        for (b in badgeDefinitions) {
            val unlocked = unlockedBadges.contains(b.id)
            addCard {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = b.icon; textSize = 36f
                    alpha = if (unlocked) 1f else 0.3f
                    setPadding(0, 0, dp(16), 0)
                })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label(b.title, 17f, if (unlocked) colorGold else colorMuted, true))
                col.addView(label(b.desc, 13f, colorMuted))
                if (unlocked) col.addView(label("✅ مفتوح", 11f, colorGold))
                else col.addView(label("🔒 مقفل", 11f, colorMuted))
                row.addView(col)
                addView(row)
            }
        }
    }

    // ============================================================
    // [LEVELS — progression list]
    // ============================================================
    private fun showLevels() {
        clearPage("المستويات")
        page.tag = null
        addCard {
            addView(label("🏅 المستوى الحالي: $level", 22f, colorGold, true))
            addView(label("الخبرة: $score XP", 16f))
            val nextLevel = level * 100
            val prevLevel = (level - 1) * 100
            val progress = ((score - prevLevel).coerceAtLeast(0) * 100) / (nextLevel - prevLevel).coerceAtLeast(1)
            addView(label("التقدم للمستوى ${level + 1}: $progress%", 13f, colorMuted))
            val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = progress
                progressTintList = ColorStateList.valueOf(colorGold)
            }
            addView(bar)
        }
        for (lvl in 1..20) {
            val req = lvl * 100
            val reached = score >= req
            addCard {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this@MainActivity).apply {
                    text = if (reached) "✅" else "🔒"
                    textSize = 24f
                    setPadding(0, 0, dp(12), 0)
                })
                val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                col.addView(label("المستوى $lvl", 16f, if (reached) colorGold else colorMuted, true))
                col.addView(label("يتطلب $req XP", 12f, colorMuted))
                row.addView(col)
                addView(row)
            }
        }
    }

    // ============================================================
    // [DAILY CHALLENGE]
    // ============================================================
    private fun showDailyChallenge() {
        clearPage("التحدي اليومي")
        page.tag = null
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val alreadyDone = challengeCompletedDate == today
        addCard {
            addView(label("🎯 التحدي اليومي", 22f, colorGold, true))
            addView(label(if (alreadyDone) "✅ أكملت تحدّي اليوم!" else "5 أسئلة · مكافأة +50 XP", 14f, colorMuted))
        }
        if (alreadyDone) {
            addCard {
                addView(label("عد غداً لتحدٍّ جديد!", 18f, colorGold, true))
                addView(label("التحدي يتغير كل يوم.", 12f, colorMuted))
            }
            addAction("🏠 العودة للرئيسية", true) { showHome() }
        } else {
            addCard {
                addView(label("هل أنت مستعد؟", 16f, colorGold, true))
                addView(label("اختبار 5 أسئلة عشوائية. النجاح الكامل (+50 XP).", 13f, colorMuted))
                addView(button("▶️ ابدأ التحدي الآن", true) { startDailyChallenge() })
            }
        }
    }

    private fun startDailyChallenge() {
        val qs = lessons.shuffled().take(5)
        var pos = 0; var correct = 0
        fun next() {
            if (pos >= qs.size) {
                val allCorrect = correct == qs.size
                if (allCorrect) {
                    score += 50; pointsToday += 50
                    challengeCompletedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    checkLevelUp()
                }
                saveData()
                playSound(if (allCorrect) soundSuccess else soundFail)
                clearPage("نتيجة التحدي")
                addCard {
                    addView(label(if (allCorrect) "🏆 نجاح كامل!" else "اختبرت التحدي", 22f, colorGold, true))
                    addView(label("صحيحة: $correct/${qs.size}", 16f))
                    if (allCorrect) addView(label("+50 XP", 20f, colorGold, true))
                    else addView(label("لا مكافأة — أعد المحاولة غداً", 13f, colorMuted))
                }
                addAction("🏠 العودة للرئيسية", true) { showHome() }
                return
            }
            val l = qs[pos]
            val expected = toFranco(l.ar)
            val choices = (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3))
                .distinct().shuffled()
            clearPage("التحدي ${pos + 1}/${qs.size}")
            addCard {
                addView(label("سؤال ${pos + 1} من ${qs.size}", 12f, colorMuted))
                addView(label(l.ar, 26f, colorGold, true))
                gap(this, 10)
                choices.forEach { c ->
                    addView(button(c) {
                        if (c == expected) { correct++; playSound(soundSuccess) }
                        else playSound(soundFail)
                        pos++; next()
                    })
                    gap(this, 6)
                }
            }
        }
        next()
    }

    // ============================================================
    // [CONTINUE IN PART 4]
    // ============================================================
    // to be continued in Part 4
}
    fun next() {
        if (pos >= qs.size) {
            val allCorrect = correct == qs.size
            if (allCorrect) {
                score += 50; pointsToday += 50
                challengeCompletedDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                checkLevelUp()
            }
            saveData()
            playSound(if (allCorrect) soundSuccess else soundFail)
            clearPage("نتيجة التحدي")
            addCard {
                addView(label(if (allCorrect) "🏆 نجاح كامل!" else "اختبرت التحدي", 22f, colorGold, true))
                addView(label("صحيحة: $correct/${qs.size}", 16f))
                if (allCorrect) addView(label("+50 XP", 20f, colorGold, true))
                else addView(label("لا مكافأة — أعد المحاولة غداً", 13f, colorMuted))
            }
            addAction("🏠 العودة للرئيسية", true) { showHome() }
            return
        }
        val l = qs[pos]
        val expected = toFranco(l.ar)
        val choices = (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3))
            .distinct().shuffled()
        clearPage("التحدي ${pos + 1}/${qs.size}")
        addCard {
            addView(label("سؤال ${pos + 1} من ${qs.size}", 12f, colorMuted))
            addView(label(l.ar, 26f, colorGold, true))
            gap(this, 10)
            choices.forEach { c ->
                addView(button(c) {
                    if (c == expected) { correct++; playSound(soundSuccess) }
                    else playSound(soundFail)
                    pos++; next()
                })
                gap(this, 6)
            }
        }
    }
    next()
}

// ============================================================
// [CONTINUATION — Part 4]
// ============================================================

// ============================================================
// [PROGRESS CHART — 7 days activity]
// ============================================================
private fun showProgressChart() {
    clearPage("مخطط التقدم")
    page.tag = null

    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val labelFormat = SimpleDateFormat("EEE", Locale("ar"))
    val days = mutableListOf<Pair<String, Int>>()
    val cal = Calendar.getInstance()

    for (i in 6 downTo 0) {
        cal.time = Date()
        cal.add(Calendar.DAY_OF_YEAR, -i)
        val key = dayFormat.format(cal.time)
        val label = labelFormat.format(cal.time)
        var count = 0
        for (h in history) {
            val ts = h.optLong("ts", 0L)
            if (ts > 0 && dayFormat.format(Date(ts)) == key) count++
        }
        days.add(label to count)
    }

    val maxCount = (days.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)

    addCard {
        addView(label("📊 نشاطك في آخر 7 أيام", 18f, colorGold, true))
        addView(label("الترجمات · كل عمود = يوم", 12f, colorMuted))
        gap(this, 12)

        val chartRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(180))
        }

        for ((label, count) in days) {
            val col = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, 1f)
            }

            val countView = TextView(this@MainActivity).apply {
                text = if (count > 0) "$count" else ""
                textSize = 11f
                setTextColor(colorGold)
                gravity = Gravity.CENTER
            }
            col.addView(countView)

            val barHeight = if (count == 0) dp(4) else ((count.toFloat() / maxCount) * 140).toInt().coerceAtLeast(dp(8))
            val bar = View(this@MainActivity).apply {
                background = shape(
                    if (count > 0) colorGold else withAlpha(colorMuted, 0.3f),
                    4
                )
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, barHeight).apply {
                    leftMargin = dp(6); rightMargin = dp(6)
                }
            }
            col.addView(bar)

            col.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 10f
                setTextColor(colorMuted)
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            })

            chartRow.addView(col)
        }
        addView(chartRow)
    }

    addCard {
        addView(label("🔥 إجمالي 7 أيام", 16f, colorGold, true))
        addView(label("${days.sumOf { it.second }} ترجمة", 22f, colorText, true))
        addView(label("سلسلة الأيام الحالية: $streakCount", 13f, colorMuted))
    }

    addAction("🏠 العودة للرئيسية", true) { showHome() }
}

// ============================================================
// [QUICK PHRASES — rapid favorites]
// ============================================================
private fun showQuickPhrases() {
    clearPage("المفضلة السريعة")
    page.tag = null

    val quickPhrases = listOf(
        "صباح الخير", "مساء الخير", "شلونك", "كل عام وأنت بخير",
        "شكراً جزيلاً", "الله يعطيك العافية", "مع السلامة", "تصبح على خير"
    )

    addCard {
        addView(label("⚡ عبارات جاهزة", 18f, colorGold, true))
        addView(label("اضغط للنسخ الفوري", 12f, colorMuted))
    }

    for (phrase in quickPhrases) {
        val franco = toFranco(phrase)
        addCard {
            addView(label(phrase, 17f, colorText, true))
            addView(label(franco, 16f, colorGold))
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(button("📋 نسخ") { copyText(franco) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
            row.addView(button("🔊 نطق") { speak(phrase) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(row)
        }
    }

    if (favorites.isNotEmpty()) {
        addCard {
            addView(label("⭐ مفضلتك (${favorites.size})", 18f, colorGold, true))
        }
        favorites.take(10).forEach { item ->
            addCard {
                addView(label(item.optString("input"), 15f, colorText, true))
                addView(label(item.optString("output"), 15f, colorGold))
                addView(button("📋 نسخ") { copyText(item.optString("output")) })
            }
        }
    }
}

// ============================================================
// [STATISTICS DASHBOARD]
// ============================================================
private fun showStatistics() {
    clearPage("الإحصائيات")
    page.tag = null

    addCard {
        addView(label("📊 لوحة الإحصائيات", 20f, colorGold, true))
        addView(label("كل الأرقام محلية، لا تغادر جهازك.", 12f, colorMuted))
    }

    addCard {
        addView(label("📈 النشاط", 16f, colorGold, true))
        addView(label("• الترجمات: ${history.size}", 15f))
        addView(label("• المفضلة: ${favorites.size}", 15f))
        addView(label("• كلمات قاموسك الشخصي: ${personalDictionary.size}", 15f))
        addView(label("• آخر 20 بحثاً: ${recentDictionarySearches.size}", 15f))
    }

    addCard {
        addView(label("🎓 التعلّم", 16f, colorGold, true))
        addView(label("• الدروس المكتملة: ${completed.size}/${lessons.size}", 15f))
        addView(label("• البطاقات في قاعدة SRS: ${flashcardStates.size}", 15f))
        addView(label("• إجمالي الإجابات الصحيحة: ${flashcardStates.values.sumOf { it.correctCount }}", 15f))
        addView(label("• محاولات الاختبار: $quizAttempts", 15f))
        addView(label("• إجابات الاختبار الصحيحة: $quizCorrect", 15f))
    }

    addCard {
        addView(label("🏆 الإنجازات", 16f, colorGold, true))
        addView(label("• الأوسمة المفتوحة: ${unlockedBadges.size}/${badgeDefinitions.size}", 15f))
        addView(label("• المستوى الحالي: $level", 15f))
        addView(label("• الخبرة: $score XP", 15f))
        addView(label("• السلسلة الحالية: $streakCount يوم", 15f))
        addView(label("• نقاط اليوم: $pointsToday XP", 15f))
    }

    val accuracy = if (quizAttempts > 0) (quizCorrect * 100) / quizAttempts else 0
    addCard {
        addView(label("🎯 الدقة", 16f, colorGold, true))
        addView(label("دقة الاختبارات: $accuracy%", 15f))
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = accuracy
            progressTintList = ColorStateList.valueOf(colorGold)
        }
        addView(bar)
    }
}

// ============================================================
// [SETTINGS — theme, accent, tts, sound, font, backup]
// ============================================================
private fun showSettings() {
    clearPage("الإعدادات")
    page.tag = null

    addCard {
        addView(label("🌓 المظهر", 17f, colorGold, true))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("🌙 داكن") {
            isDarkTheme = true; saveSetting("theme_dark", true); applyTheme(); showSettings()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(button("☀️ فاتح") {
            isDarkTheme = false; saveSetting("theme_dark", false); applyTheme(); showSettings()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(row)
    }

    addCard {
        addView(label("🎨 لون التمييز", 17f, colorGold, true))
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val accents = listOf("gold" to "ذهبي", "emerald" to "زمردي", "ruby" to "ياقوتي", "sapphire" to "سماوي")
        for ((key, name) in accents) {
            row.addView(TextView(this@MainActivity).apply {
                text = name
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (accentMode == key) colorBg else colorText)
                background = shape(if (accentMode == key) colorGold else colorPanel, 8, colorGold)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                setOnClickListener {
                    accentMode = key; saveSetting("accent", key); applyTheme(); showSettings()
                }
            })
        }
        addView(row)
    }

    addCard {
        addView(label("🔊 سرعة النطق", 17f, colorGold, true))
        addView(label("الحالي: %.1fx".format(ttsSpeed), 13f, colorMuted))
        val seek = SeekBar(this@MainActivity).apply {
            max = 15; progress = ((ttsSpeed - 0.5f) * 10).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    ttsSpeed = 0.5f + p / 10f
                    tts?.setSpeechRate(ttsSpeed)
                    saveSetting("tts_speed", ttsSpeed)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        addView(seek)
    }

    addCard {
        addView(label("⚙️ تفضيلات", 17f, colorGold, true))
        addView(button(if (ttsAutoSpeak) "🔊 نطق تلقائي: مفعل" else "🔇 نطق تلقائي: متوقف") {
            ttsAutoSpeak = !ttsAutoSpeak; saveSetting("auto_speak", ttsAutoSpeak); showSettings()
        })
        addView(button(if (soundEnabled) "🎵 المؤثرات الصوتية: مفعلة" else "🔕 المؤثرات الصوتية: متوقفة") {
            soundEnabled = !soundEnabled; saveSetting("sound", soundEnabled); showSettings()
        })
        addView(button(if (fontChoice == "serif") "✒️ الخط: Serif" else "📝 الخط: الافتراضي") {
            fontChoice = if (fontChoice == "serif") "default" else "serif"
            saveSetting("font", fontChoice); showSettings()
        })
        addView(button("🔊 اختبار النطق") { speak("اختبار النطق في TON FOX") })
    }

    addCard {
        addView(label("💾 النسخ الاحتياطي", 17f, colorGold, true))
        addView(button("📤 تصدير نسخة احتياطية", true) { exportBackup() })
        addView(button("📥 استيراد نسخة احتياطية") { importBackup() })
    }

    addCard {
        addView(label("⚠️ بيانات", 17f, colorGold, true))
        addView(button("🗑️ حذف جميع البيانات") { confirmClearData() })
    }

    addCard {
        addView(label("ℹ️ عن التطبيق", 17f, colorGold, true))
        addView(label("TON FOX V4", 16f, colorText, true))
        addView(label("المطور: N5CR4", 13f, colorMuted))
        addView(label("Telegram: @N5CR4", 13f, colorGold))
        addView(button("💬 التواصل مع المطور") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/N5CR4"))) }
            catch (_: Exception) { toast("تعذر فتح تيليجرام") }
        })
        addView(button("⭐ قيّمنا على Google Play") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))) }
            catch (_: Exception) { toast("المتجر غير متاح") }
        })
    }
}

// ============================================================
// [EXPORT / IMPORT / CLEAR]
// ============================================================
private fun exportBackup() {
    try {
        val json = JSONObject().apply {
            put("app", "TON FOX")
            put("version", 4)
            put("exported_at", System.currentTimeMillis())
            put("history", JSONArray().apply { history.forEach { put(it) } })
            put("favorites", JSONArray().apply { favorites.forEach { put(it) } })
            put("lessons", JSONArray().apply { completed.forEach { put(it) } })
            put("badges", JSONArray().apply { unlockedBadges.forEach { put(it) } })
            put("personal_dict", JSONArray().apply {
                personalDictionary.forEach {
                    put(JSONObject().put("id", it.id).put("ar", it.arabic).put("fr", it.franco).put("tags", it.tags))
                }
            })
            put("flashcard_states", JSONObject().apply {
                flashcardStates.forEach { (k, s) ->
                    put(k, JSONObject().apply {
                        put("interval", s.intervalDays); put("ease", s.easeFactor.toDouble())
                        put("next", s.nextReviewAt); put("ok", s.correctCount); put("ko", s.wrongCount)
                    })
                }
            })
            put("score", score).put("level", level)
            put("streak", streakCount)
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "TON FOX Backup")
            putExtra(Intent.EXTRA_TEXT, json.toString(2))
        }
        startActivity(Intent.createChooser(send, "مشاركة النسخة الاحتياطية"))
    } catch (e: Exception) { toast("فشل التصدير: ${e.message}") }
}

private fun importBackup() {
    try {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_IMPORT)
    } catch (_: Exception) { toast("تعذر فتح الملفات") }
}

private fun confirmClearData() {
    AlertDialog.Builder(this)
        .setTitle("⚠️ حذف جميع البيانات")
        .setMessage("سيتم حذف السجل، المفضلة، الأوسمة، التقدم، والقواميس الشخصية. لا يمكن التراجع.")
        .setNegativeButton("إلغاء", null)
        .setPositiveButton("حذف الكل") { _, _ ->
            history.clear(); favorites.clear(); completed.clear()
            unlockedBadges.clear(); personalDictionary.clear()
            flashcardStates.clear(); recentDictionarySearches.clear()
            score = 0; level = 1; streakCount = 0; pointsToday = 0
            quizAttempts = 0; quizCorrect = 0
            challengeCompletedDate = ""
            saveData()
            showSettings()
            toast("تم حذف كل البيانات")
        }.show()
}

// ============================================================
// [SHARE AS IMAGE — Canvas PNG]
// ============================================================
private fun shareAsImage(text: String) {
    if (text.isBlank() || text == "ستظهر الترجمة هنا...") {
        toast("لا توجد ترجمة للمشاركة"); return
    }
    try {
        val size = 1080
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Background
        paint.color = colorBg
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)

        // Gold border
        paint.color = colorGold
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        val rect = RectF(30f, 30f, size - 30f, size - 30f)
        canvas.drawRoundRect(rect, 32f, 32f, paint)

        // Title
        paint.style = Paint.Style.FILL
        paint.color = colorGold
        paint.textSize = 56f
        paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("TON FOX 🦊", size / 2f, 140f, paint)

        // Subtitle
        paint.color = colorMuted
        paint.textSize = 30f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("مترجم الفرانكو", size / 2f, 190f, paint)

        // Divider
        paint.color = colorBorder
        paint.strokeWidth = 2f
        canvas.drawLine(150f, 220f, size - 150f, 220f, paint)

        // Main text — wrap
        paint.color = colorText
        paint.textSize = 44f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val maxWidth = size - 200f
        val lines = wrapText(text, paint, maxWidth)
        val startY = 340f
        val lineHeight = 60f
        lines.forEachIndexed { i, line ->
            val y = startY + i * lineHeight
            if (y < size - 200f) canvas.drawText(line, size / 2f, y, paint)
        }

        // Footer
        paint.color = colorMuted
        paint.textSize = 24f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("صُنع بواسطة TON FOX · @N5CR4", size / 2f, size - 80f, paint)

        // Save to cache
        val cacheFile = File(cacheDir, "tonfox_share_${System.currentTimeMillis()}.png")
        FileOutputStream(cacheFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Save to MediaStore for easy sharing
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "TON FOX Translation")
            put(MediaStore.Images.Media.DISPLAY_NAME, "tonfox_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { os -> bmp.compress(Bitmap.CompressFormat.PNG, 100, os) }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "مشاركة الصورة"))
            unlockBadge("share")
        } else {
            toast("تعذر حفظ الصورة"); return
        }
    } catch (e: Exception) {
        toast("فشل المشاركة: ${e.message}")
    }
}

private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
    val words = text.split(" ")
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
        val test = if (current.isEmpty()) word else "$current $word"
        if (paint.measureText(test) <= maxWidth) {
            current = StringBuilder(test)
        } else {
            if (current.isNotEmpty()) lines.add(current.toString())
            current = StringBuilder(word)
        }
    }
    if (current.isNotEmpty()) lines.add(current.toString())
    return lines
}
    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) <= maxWidth) {
                current = StringBuilder(test)
            } else {
                if (current.isNotEmpty()) lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    // ============================================================
    // [PERMISSIONS & ACTIVITY RESULT]
    // ============================================================
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("✔ تم تفعيل إشعارات كلمة اليوم")
            } else {
                toast("⚠️ الإشعارات معطلة — يمكنك تفعيلها من إعدادات النظام")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return

        when (requestCode) {
            REQ_VOICE -> {
                val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    showTranslator()
                    sourceText?.setText(spoken)
                }
            }
            REQ_IMPORT -> {
                data?.data?.let { importBackupFrom(it) }
            }
            REQ_CAPTURE_PHOTO -> {
                AlertDialog.Builder(this)
                    .setTitle("📷 صورة ملتقطة")
                    .setMessage("ميزة OCR الكاملة تتطلب مكتبة ML Kit خارجية. اكتب النص الذي تراه في الصورة يدوياً:")
                    .setPositiveButton("كتابة يدوية") { _, _ ->
                        showTranslator()
                        sourceText?.setText("")
                        toast("اكتب النص المرئي في الحقل")
                    }
                    .setNegativeButton("إلغاء", null).show()
            }
            REQ_CAMERA_OCR -> {
                val text = data?.getStringExtra("OCR_TEXT") ?: ""
                if (text.isNotBlank()) {
                    pendingOcrResult = text
                    showTranslator()
                    sourceText?.setText(text)
                }
            }
            REQ_PICK_AVATAR -> {
                // Placeholder for future avatar feature
            }
        }
    }

    // ============================================================
    // [OVERRIDE onNewIntent — Handle Shortcuts]
    // ============================================================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra("screen")) {
            "translator" -> showTranslator()
            "dictionary" -> showDictionary()
            "home" -> showHome()
        }
    }

    // ============================================================
    // [CLEANUP ON FINISH]
    // ============================================================
    override fun onStart() {
        super.onStart()
        // Update widget when app opens
        updateWidgetData()
    }

    // ============================================================
    // [END OF CLASS]
    // ============================================================
}