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
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.RippleDrawable
import android.graphics.pdf.PdfDocument
import android.hardware.biometrics.BiometricPrompt
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
private const val REQ_VOICE = 9001
private const val REQ_IMPORT = 9002
private const val REQ_NOTIFY = 9003
private const val REQ_PDF = 9004
private const val CHANNEL_ID_DAILY = "ton_fox_daily_word"

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

data class Lesson(val id: Int, val title: String, val desc: String, val lines: List<String>, val ar: String, val fr: String, val difficulty: String)
data class UserBadge(val id: String, val title: String, val desc: String, val icon: String)
data class CustomWord(val id: String, val arabic: String, val franco: String, val tags: String = "")
data class FlashcardState(val word: String, val intervalDays: Int = 0, val easeFactor: Float = 2.5f, val nextReviewAt: Long = 0L, val correctCount: Int = 0, val wrongCount: Int = 0)
data class ChatMessage(val text: String, val isUser: Boolean, val timestamp: Long = System.currentTimeMillis())

// ============================================================
// [رسم شعار الثعلب برمجياً - Canvas View]
// ============================================================
class FoxLogoView(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        
        // رسم الرأس
        path.reset()
        path.moveTo(w * 0.5f, h * 0.85f) // الذقن
        path.lineTo(w * 0.85f, h * 0.45f) // خد أيمن
        path.lineTo(w * 0.95f, h * 0.1f)  // أذن يمنى
        path.lineTo(w * 0.6f, h * 0.25f)  // جبهة يمنى
        path.lineTo(w * 0.4f, h * 0.25f)  // جبهة يسرى
        path.lineTo(w * 0.05f, h * 0.1f)  // أذن يسرى
        path.lineTo(w * 0.15f, h * 0.45f) // خد أيسر
        path.close()
        canvas.drawPath(path, paint)
        
        // عيون
        paint.color = Color.TRANSPARENT
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(w * 0.3f, h * 0.4f)
        path.lineTo(w * 0.45f, h * 0.48f)
        path.lineTo(w * 0.35f, h * 0.52f)
        path.close()
        canvas.drawPath(path, paint)
        
        path.reset()
        path.moveTo(w * 0.7f, h * 0.4f)
        path.lineTo(w * 0.55f, h * 0.48f)
        path.lineTo(w * 0.65f, h * 0.52f)
        path.close()
        canvas.drawPath(path, paint)
    }
}

// ============================================================
// [العمليات الخلفية - الإشعارات والودجت]
// ============================================================
object DailyWordScheduler {
    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, DailyWordReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, 441, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        try { alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pending) } catch (e: Exception) {}
    }
}

class DailyWordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val words = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran")
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt()
        val w = words[day % words.size]
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID_DAILY, "TON FOX", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(context, 442, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL_ID_DAILY) else Notification.Builder(context)
        val notification = builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TON FOX 🦊 · كلمة اليوم")
            .setContentText("${w.first} • ${w.second}")
            .setStyle(Notification.BigTextStyle().bigText("${w.first}\n${w.second}\nافتح التطبيق لسماع النطق"))
            .setContentIntent(pi).setAutoCancel(true).build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) manager.notify(440, notification)
        DailyWordScheduler.schedule(context)
    }
}

class DailyWordBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) DailyWordScheduler.schedule(context)
    }
}

class TonFoxWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_2)
            val prefs = context.getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
            views.setTextViewText(android.R.id.text1, prefs.getString("widget_today", "افتح التطبيق") ?: "")
            views.setTextViewText(android.R.id.text2, prefs.getString("widget_today_fr", "") ?: "")
            manager.updateAppWidget(id, views)
        }
    }
}
// ============================================================
// [MainActivity]
// ============================================================
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private var colorBg = Color.parseColor("#090D14")
    private var colorPanel = Color.parseColor("#171F2B")
    private var colorGold = Color.parseColor("#D9B33E")
    private var colorText = Color.parseColor("#F5F5F5")
    private var colorMuted = Color.parseColor("#929BAA")
    private var colorBorder = Color.parseColor("#2A3241")

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
    private var ttsAutoSpeak = false
    private var currentDialect = "العراقية"
    private var score = 0
    private var level = 1
    private var streakCount = 0
    private var lastOpenDate = ""
    private var soundEnabled = true
    private var isAppLockEnabled = false
    private var isAppLocked = false

    // Lists & States
    private val history = mutableListOf<JSONObject>()
    private val favorites = mutableListOf<JSONObject>()
    private val completed = mutableSetOf<Int>()
    private val unlockedBadges = mutableSetOf<String>()
    private val personalDictionary = mutableListOf<CustomWord>()
    private val flashcardStates = mutableMapOf<String, FlashcardState>()
    private var recentDictionarySearches = mutableListOf<String>()
    private var challengeCompletedDate = ""
    private var pointsToday = 0
    private var quizAttempts = 0
    private var quizCorrect = 0
    private var focusMode = false
    private var chatHistory = mutableListOf<ChatMessage>()

    private lateinit var vibrator: Vibrator
    private var toneGen: ToneGenerator? = null

    // Dictionaries & Data
    private val dialectDictIraqi = linkedMapOf("شلونك" to "shlonak", "شكو ماكو" to "shako mako", "هسه" to "hassa", "زين" to "zain", "باچر" to "bachir", "وينك" to "wainak", "أريد" to "areed")
    private val dialectDictEgyptian = linkedMapOf("ازيك" to "ezayak", "عامل ايه" to "3amel eh", "كده" to "kda", "معلش" to "ma3lesh", "يعني" to "ya3ni", "كمان" to "kman", "برضه" to "bardu")
    private val dialectDictGulf = linkedMapOf("شلونك" to "shloonk", "وش أخبارك" to "wesh akhbarak", "زين" to "zain", "باجر" to "bacher", "وناسة" to "wnasa", "قواك الله" to "qwak allah")
    private val dialectDictLevantine = linkedMapOf("كيفك" to "keefak", "شو الأخبار" to "shu lakhbar", "منيح" to "mnee7", "هلأ" to "halla2", "بكرا" to "bukra", "شغل" to "shughul")

    private val dictionary = linkedMapOf(
        "مرحبا" to "mar7aba", "السلام عليكم" to "alsalam 3alaykom", "كيف حالك" to "keef 7alak", "شلونك" to "shlonak", "شلونج" to "shlonich", "شلونكم" to "shlonkom", "شكو ماكو" to "shako mako", "شنو" to "shno", "ليش" to "leesh", "وين" to "wain", "هسه" to "hassa", "باچر" to "bachir", "زين" to "zain", "حبيبي" to "7abibi", "حبيبتي" to "7abibti", "قلبي" to "galbi", "عمري" to "3omri", "أحبك" to "a7ebak", "اشتقتلك" to "eshta2tellak", "شكرا" to "shukran", "عفوا" to "3afwan", "نعم" to "na3am", "لا" to "la", "اي" to "ay", "مو" to "mo", "ماكو" to "mako", "اكو" to "ako", "الله" to "allah", "ان شاء الله" to "inshallah", "الحمد لله" to "al7amdulillah", "ما شاء الله" to "mashallah", "صباح الخير" to "saba7 elkhair", "مساء الخير" to "masa elkhair", "أنا" to "ana", "أنت" to "enta", "بيت" to "bait", "كتاب" to "ketab", "مدرسة" to "madrasa", "جامعة" to "game3a", "صديق" to "sadeeq", "جميل" to "jameel", "حلو" to "7elo", "كبير" to "kbeer", "صغير" to "s8eer", "اليوم" to "elyom", "غدا" to "8adan", "الآن" to "alan", "أريد" to "areed", "تعال" to "ta3al", "روح" to "roo7", "يلا" to "yalla", "خلاص" to "khalas", "تمام" to "tamam", "مبروك" to "mabrook", "مشكلة" to "moshkela", "وقت" to "wa2t", "شمس" to "shams", "قمر" to "qamar", "نور" to "noor", "عراق" to "iraq", "مصر" to "masr", "السعودية" to "alsa3odeya"
    )
    private val arabicMap = mapOf('ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "7", 'خ' to "kh", 'د' to "d", 'ذ' to "th", 'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh", 'ص' to "9", 'ض' to "9'", 'ط' to "6", 'ظ' to "6'", 'ع' to "3", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'ة' to "a", 'و' to "w", 'ي' to "y", 'ى' to "a", 'ء' to "2", 'ئ' to "2", 'ؤ' to "2", 'پ' to "p", 'چ' to "ch", 'گ' to "g")
    private val dailyWords = listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "حبيبي" to "7abibi", "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak", "تمام" to "tamam", "وينك" to "wainak", "الله يسعدك" to "allah yesa3dak", "أريد أتعلم" to "areed at3allam", "نلتقي قريباً" to "neltagi gareeban", "كل شيء بخير" to "kol shay bekhair", "تصبح على خير" to "tesba7 3ala khair", "ماكو مشكلة" to "mako moshkela")
    
    private val badgeDefinitions = listOf(
        UserBadge("first", "أول ترجمة", "قم بأول ترجمة", "🎯"), UserBadge("ten", "10 ترجمات", "قم بـ10 ترجمات", "🔟"), UserBadge("hundred", "100 ترجمة", "قم بـ100 ترجمة", "💯"),
        UserBadge("save10", "حافظ الكلمات", "احفظ 10 عبارات", "💾"), UserBadge("five_lessons", "تلميذ مجتهد", "أكمل 5 دروس", "🎓"),
        UserBadge("streak7", "أسبوع متواصل", "7 أيام متتالية", "🔥"), UserBadge("streak30", "شهر ملكي", "30 يوماً متتالياً", "🏆"),
        UserBadge("first_quiz", "أول اختبار", "أتم أول اختبار", "🧪"), UserBadge("perfect_quiz", "علامة كاملة", "اختبار بعلامة كاملة", "⭐"), UserBadge("share", "ناشر", "شارك ترجمة كصورة", "📤")
    )
    
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
        val config = Configuration(newBase.resources.configuration); config.fontScale = 1.0f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vibrator = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        try { toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 50) } catch (_: Exception) {}
        prefs = getSharedPreferences("ton_fox_data", MODE_PRIVATE)
        loadData()
        isAppLockEnabled = prefs.getBoolean("app_lock", false)
        if (isAppLockEnabled) { isAppLocked = true; showLockScreen() } else { initApp() }
    }

    private fun initApp() {
        tts = TextToSpeech(this, this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        DailyWordScheduler.schedule(this)
        updateStreakOnOpen()
        buildShell()
        showHome()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true; tts?.setSpeechRate(ttsSpeed)
            if (prefs.getBoolean("speak_on_open", true) && !isAppLocked) root.postDelayed({ speakDailyWord() }, 1000)
        }
    }

    override fun onDestroy() { tts?.stop(); tts?.shutdown(); toneGen?.release(); super.onDestroy() }

    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) toggleDrawer(false)
        else if (page.tag?.toString() != "home_tag") showHome()
        else super.onBackPressed()
    }

    // --- Helpers ---
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun vibrate(ms: Long) { try { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {} }
    private fun playSound(type: Int) { if (!soundEnabled || toneGen == null) return; try { toneGen?.startTone(if (type == 1) ToneGenerator.TONE_PROP_BEEP else if (type == 2) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_PROP_NACK, 50) } catch (_: Exception) {} }
    private fun shape(color: Int, radius: Int, stroke: Int? = null): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(1), stroke) }
    private fun label(t: String, size: Float = 15f, color: Int = colorText, bold: Boolean = false): TextView = TextView(this).apply { text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setPadding(dp(4), dp(6), dp(4), dp(6)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    
    private fun button(t: String, primary: Boolean = false, action: () -> Unit): TextView = TextView(this).apply {
        text = t; textSize = 15f; gravity = Gravity.CENTER; setTextColor(if (primary) colorBg else colorGold); typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(16), dp(12), dp(16), dp(12)); minHeight = dp(54)
        background = RippleDrawable(ColorStateList.valueOf(withAlpha(Color.WHITE, 0.2f)), shape(if (primary) colorGold else colorPanel, 12, if (primary) null else colorGold), shape(Color.WHITE, 12))
        isClickable = true
        setOnClickListener { animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(90).start(); playSound(1); vibrate(15); action() }.start() }
    }
    
    private fun card(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18)); background = shape(colorPanel, 16, colorBorder); elevation = dp(4).toFloat() }
    private fun gap(parent: LinearLayout, h: Int = 12) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun addCard(block: LinearLayout.() -> Unit) { val c = card(); c.block(); c.alpha = 0f; c.translationY = 50f; page.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(14) }); c.animate().alpha(1f).translationY(0f).setDuration(300).start() }
    private fun addAction(t: String, primary: Boolean = false, action: () -> Unit) { page.addView(button(t, primary, action), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hideKeyboard(v: View) { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
    private fun copyText(s: String) { if (s.isBlank()) { toast("لا يوجد نص"); return }; (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("TON FOX", s)); toast("تم النسخ") }
    private fun todaysWord(): Pair<String, String> { val n = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()).toInt(); return dailyWords[n % dailyWords.size] }
    
    private fun speakDailyWord() {
        if (!ttsReady) return; val w = todaysWord(); val result = tts?.setLanguage(Locale("ar"))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) { tts?.language = Locale.US; tts?.speak(w.second, TextToSpeech.QUEUE_FLUSH, null, "d_en") }
        else { tts?.speak(w.first, TextToSpeech.QUEUE_FLUSH, null, "d_ar"); Handler(Looper.getMainLooper()).postDelayed({ tts?.language = Locale.US; tts?.speak(w.second, TextToSpeech.QUEUE_ADD, null, "d_fr") }, 1200) }
    }
    private fun speak(s: String) { if (!ttsReady || s.isBlank()) return; tts?.language = Locale.US; tts?.setSpeechRate(ttsSpeed); tts?.speak(s, TextToSpeech.QUEUE_FLUSH, null, "sp") }

    private fun showLockScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(colorBg) }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), dp(32), dp(32), dp(32)) }
        layout.addView(FoxLogoView(this, colorGold), LinearLayout.LayoutParams(dp(120), dp(120)))
        gap(layout, 20); layout.addView(label("TON FOX مقفل", 24f, colorText, true)); layout.addView(label("يرجى التحقق من هويتك", 14f, colorMuted))
        gap(layout, 40); layout.addView(button("🔓 فتح ببصمة الإصبع", true) { authenticateUser() })
        root.addView(layout, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)); setContentView(root); authenticateUser()
    }

    private fun authenticateUser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val executor = mainExecutor
            val biometricPrompt = BiometricPrompt.Builder(this).setTitle("TON FOX").setSubtitle("تأكيد الهوية").setDescription("استخدم بصمة الإصبع")
                .setNegativeButton("إلغاء", executor) { _, _ -> finish() }.build()
            biometricPrompt.authenticate(android.os.CancellationSignal(), executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { super.onAuthenticationSucceeded(result); isAppLocked = false; runOnUiThread { initApp() } }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { super.onAuthenticationError(errorCode, errString); toast("فشل: $errString") }
            })
        } else { isAppLocked = false; initApp() }
    }

    private fun buildShell() {
        window.statusBarColor = colorBg; window.navigationBarColor = colorBg
        root = FrameLayout(this).apply { setBackgroundColor(colorBg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); setBackgroundColor(colorPanel) }
        header.addView(button("☰", true) { toggleDrawer(true) }, LinearLayout.LayoutParams(dp(52), dp(48)))
        
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        heading.addView(FoxLogoView(this, colorGold), LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(8) })
        heading.addView(label("TON FOX", 22f, colorGold, true))
        header.addView(heading)
        
        if (streakCount > 0) {
            val flame = label("🔥 $streakCount", 16f, colorGold); flame.setPadding(dp(8), dp(8), dp(8), dp(8))
            flame.setOnClickListener { AlertDialog.Builder(this).setTitle("🔥 سلسلة الأيام").setMessage("أنت متواصل منذ $streakCount يوماً!").setPositiveButton("حسناً", null).show() }
            header.addView(flame)
        }
        
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
        drawerContent.removeAllViews(); drawerContent.addView(label("TON FOX 🦊", 26f, colorGold, true)); drawerContent.addView(label("القائمة الرئيسية", 13f, colorMuted)); gap(drawerContent, 20)
        val entries = listOf<Pair<String, () -> Unit>>(
            "الرئيسية" to { showHome() }, "المترجم" to { showTranslator() }, "الأكاديمية" to { showAcademy() },
            "القاموس" to { showDictionary() }, "قاموسي الشخصي" to { showPersonalDictionary() }, "مستشار الثعلب 🦊" to { showFoxChat() },
            "تصدير المفضلة PDF" to { exportToPdf() }, "بطاقات الحفظ (SRS)" to { showFlashcards() }, "المفضلة والسجل" to { showSaved() },
            "الأوسمة" to { showBadges() }, "المستويات" to { showLevels() }, "التحدي اليومي" to { showDailyChallenge() },
            "مخطط التقدم" to { showProgressChart() }, "المفضلة السريعة" to { showQuickPhrases() }, "الإحصائيات" to { showStatistics() },
            "الإعدادات" to { showSettings() }, "💬 تواصل مع المطور (@N5CR4)" to { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/N5CR4"))) } catch (_: Exception) { toast("تعذر فتح تيليجرام") } }
        )
        entries.forEach { (name, fn) -> drawerContent.addView(button(name) { toggleDrawer(false); fn() }, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply { bottomMargin = dp(6) }) }
        drawerContent.addView(label("TON FOX V5", 12f, colorMuted, false))
    }

    private fun toggleDrawer(show: Boolean) {
        if (show) { drawer.visibility = View.VISIBLE; shade.visibility = View.VISIBLE; drawer.translationX = dp(300).toFloat(); drawer.animate().translationX(0f).setDuration(250).start(); shade.bringToFront(); drawer.bringToFront() }
        else { drawer.animate().translationX(dp(300).toFloat()).setDuration(250).withEndAction { drawer.visibility = View.GONE; shade.visibility = View.GONE }.start() }
    }
    private fun clearPage(title: String) { page.removeAllViews(); page.addView(label(title, 24f, colorGold, true)); gap(page, 16) }

    private fun showHome() {
        clearPage("الرئيسية"); page.tag = "home_tag"; val w = todaysWord()
        addCard {
            addView(label("أهلاً بك في TON FOX", 20f, colorGold, true)); addView(label("ترجم وتعلّم الفرانكو خطوة بخطوة.", 15f))
            addView(button("🔊 استمع لكلمة اليوم", true) { speakDailyWord() })
        }
        addCard {
            addView(label("🌟 كلمة اليوم", 19f, colorGold, true)); addView(label(w.first, 25f, colorText, true)); addView(label(w.second, 22f, colorGold, true))
            addView(button("نسخ الفرانكو") { copyText(w.second) })
        }
        addCard {
            addView(label("🔥 سلسلتك: $streakCount يوم", 18f, colorGold, true)); addView(label("المستوى: $level · الخبرة: $score XP"))
            addView(label("أوسمة: ${unlockedBadges.size}/${badgeDefinitions.size}"))
        }
        addAction("📝 ابدأ الترجمة", true) { showTranslator() }
        addAction("🦊 تحدث مع مستشار الثعلب", true) { showFoxChat() }
        addAction("🎓 الأكاديمية") { showAcademy() }
    }

    private fun showFoxChat() {
        clearPage("مستشار الثعلب 🦊")
        val chatContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        val scroll = ScrollView(this).apply { addView(chatContainer); layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f) }
        
        fun addMessage(text: String, isUser: Boolean) {
            val bubble = TextView(this).apply {
                this.text = text; textSize = 16f; setTextColor(if (isUser) colorBg else colorText)
                background = shape(if (isUser) colorGold else colorPanel, 16, if (isUser) null else colorGold)
                setPadding(dp(16), dp(12), dp(16), dp(12)); layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = if (isUser) Gravity.END else Gravity.START; topMargin = dp(8); bottomMargin = dp(8) }
            }
            chatContainer.addView(bubble); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        addMessage("أهلاً بك! أنا الثعلب، مساعدك في الفرانكو. كيف يمكنني مساعدتك اليوم؟", false)
        
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val input = EditText(this).apply { hint = "اكتب رسالتك..."; setTextColor(colorText); setHintTextColor(colorMuted); background = shape(colorPanel, 12, colorBorder); setPadding(dp(12), dp(12), dp(12), dp(12)); layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val sendBtn = button("إرسال", true) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                addMessage(text, true); input.setText("")
                Handler(Looper.getMainLooper()).postDelayed({
                    val response = when {
                        text.contains("شلونك") || text.contains("كيفك") -> "أنا بخير، شكراً لسؤالك! شلونك أنت؟"
                        text.contains("شكرا") -> "العفو! أنا هنا دائماً لمساعدتك."
                        text.contains("فرانكو") -> "الفرانكو لغة رقمية جميلة، هل تريد تعلم قاعدة جديدة؟"
                        text.contains("ترجمة") -> "جرب استخدام المترجم في القائمة، سيساعدك فوراً!"
                        else -> "كلامك جميل! هل تريد أن نتعلم كلمة جديدة اليوم؟"
                    }
                    addMessage(response, false); playSound(2)
                }, 800)
            }
        }
        inputRow.addView(input); inputRow.addView(sendBtn)
        page.addView(scroll); page.addView(inputRow)
    }
    // ============================================================
    // [الأكاديمية والدروس]
    // ============================================================
    private fun showAcademy() {
        clearPage("الأكاديمية"); page.tag = null
        var filterDifficulty = "all"
        
        addCard {
            addView(label("تعلّم الفرانكو خطوة بخطوة", 20f, colorGold, true))
            addView(label("المستوى $level · $score XP · ${completed.size}/${lessons.size} مكتملة", 14f, colorMuted))
            gap(this, 8)
            
            // أزرار تصفية الصعوبة
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("all" to "الكل", "beginner" to "مبتدئ", "intermediate" to "متوسط", "advanced" to "متقدم").forEach { (key, name) ->
                val isSel = filterDifficulty == key
                row.addView(TextView(this@MainActivity).apply {
                    text = name; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (isSel) colorBg else colorText)
                    background = shape(if (isSel) colorGold else colorPanel, 8, colorGold)
                    setPadding(dp(10), dp(8), dp(10), dp(8)); isClickable = true
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
                    setOnClickListener { 
                        filterDifficulty = key
                        // إعادة بناء الشاشة بالفلتر الجديد
                        showAcademyFiltered(filterDifficulty)
                    }
                })
            }
            addView(row)
        }
        
        val filtered = lessons
        filtered.forEach { l ->
            addCard {
                val done = completed.contains(l.id)
                val diffLabel = when (l.difficulty) { "beginner" -> "🟢 مبتدئ"; "intermediate" -> "🟡 متوسط"; else -> "🔴 متقدم" }
                addView(label("${if (done) "✓" else "○"} الدرس ${l.id + 1}: ${l.title}", 17f, colorGold, true))
                addView(label(diffLabel, 12f, colorMuted))
                addView(label(l.desc, 14f, colorMuted)); gap(this, 8)
                addView(button("فتح الدرس") { showLesson(l.id) })
            }
        }
        addAction("🧪 اختبار شامل", true) { startQuiz(-1) }
    }

    private fun showAcademyFiltered(filter: String) {
        // دالة مساعدة لإعادة رسم الأكاديمية مع الفلتر المحدد
        clearPage("الأكاديمية")
        addCard {
            addView(label("تصفية: $filter", 16f, colorGold, true))
            addView(button("عرض الكل") { showAcademy() })
        }
        val filtered = if (filter == "all") lessons else lessons.filter { it.difficulty == filter }
        filtered.forEach { l ->
            addCard {
                val done = completed.contains(l.id)
                addView(label("${if (done) "✓" else "○"} ${l.title}", 17f, colorGold, true))
                addView(label(l.desc, 14f, colorMuted)); gap(this, 8)
                addView(button("فتح الدرس") { showLesson(l.id) })
            }
        }
        addAction("🧪 اختبار", true) { startQuiz(-1) }
    }

    private fun showLesson(i: Int) {
        val l = lessons[i]; clearPage(l.title); page.tag = null
        addCard {
            addView(label("الدرس ${i + 1}", 14f, colorGold, true))
            addView(label(l.desc, 15f))
            l.lines.forEachIndexed { n, line -> addView(label("${n + 1}. $line", 15f)) }
            gap(this, 10); addView(button("🔊 نطق الجملة الأساسية") { speak(l.ar) })
        }
        val answer = EditText(this).apply {
            hint = "اكتب التحويل بالفرانكو"; setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorBg, 12, colorGold); setPadding(dp(14), dp(14), dp(14), dp(14))
            textSize = 17f; layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        addCard {
            addView(label("تدريب: ${l.ar}", 20f, colorGold, true)); addView(answer); gap(this, 8)
            addView(button("تحقق من الإجابة", true) {
                val typed = answer.text.toString().replace(" ", "").lowercase(Locale.ROOT)
                val expected = l.fr.replace(" ", "").lowercase(Locale.ROOT)
                if (typed == expected || typed == toFranco(l.ar).replace(" ", "").lowercase(Locale.ROOT)) {
                    val wasNew = completed.add(i)
                    if (wasNew) { score += 20; pointsToday += 20 }
                    level = score / 100 + 1
                    if (completed.size >= 5) unlockBadge("five_lessons")
                    saveData(); playSound(2); toast("✔ إجابة صحيحة +20 XP"); showLesson(i)
                } else { playSound(3); toast("حاول مرة أخرى: ${l.fr}") }
            })
        }
        addAction("العودة للأكاديمية", true) { showAcademy() }
    }

    private fun startQuiz(lessonIndex: Int) {
        val qs = if (lessonIndex >= 0) listOf(lessons[lessonIndex]) else lessons.shuffled().take(5)
        var pos = 0; var correct = 0; quizAttempts++
        fun next() {
            if (pos >= qs.size) {
                val earned = correct * 10; score += earned; level = score / 100 + 1
                pointsToday += earned; quizCorrect += correct
                unlockBadge("first_quiz"); if (correct == qs.size) unlockBadge("perfect_quiz")
                saveData(); playSound(2); clearPage("نتيجة الاختبار")
                addCard {
                    addView(label("انتهى الاختبار!", 23f, colorGold, true))
                    addView(label("الإجابات الصحيحة: $correct/${qs.size}", 17f))
                    addView(label("+$earned XP", 18f, colorGold))
                }
                addAction("العودة للأكاديمية", true) { showAcademy() }
                return
            }
            val l = qs[pos]; val expected = toFranco(l.ar)
            val choices = (listOf(expected) + lessons.filter { it != l }.map { toFranco(it.ar) }.shuffled().take(3)).distinct().shuffled()
            clearPage("السؤال ${pos + 1}/${qs.size}")
            addCard {
                addView(label("حوّل إلى فرانكو:", 16f, colorMuted)); addView(label(l.ar, 24f, colorGold, true)); gap(this, 8)
                choices.forEach { choice ->
                    addView(button(choice) {
                        if (choice == expected) { correct++; playSound(2) } else playSound(3)
                        pos++; next()
                    }); gap(this, 6)
                }
            }
        }
        next()
    }

    // ============================================================
    // [القاموس وقاموسي الشخصي]
    // ============================================================
    private fun showDictionary() {
        clearPage("القاموس"); page.tag = null
        val search = EditText(this).apply {
            hint = "ابحث بالعربية أو الفرانكو"; setTextColor(colorText); setHintTextColor(colorMuted)
            background = shape(colorPanel, 12, colorGold); setPadding(dp(14), dp(14), dp(14), dp(14)); layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) }); gap(page); page.addView(results)
        
        search.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString().orEmpty(); results.removeAllViews()
                val merged = linkedMapOf<String, String>(); merged.putAll(dictionary); personalDictionary.forEach { merged[it.arabic] = it.franco }
                val filtered = merged.filter { it.key.contains(q, true) || it.value.contains(q, true) }
                if (filtered.isEmpty() && q.isNotBlank()) {
                    val c = card(); c.addView(label("لا نتائج", 15f, colorMuted)); c.addView(button("➕ إضافة للقاموس") { addToPersonalDictionary(q, toFranco(q)); showDictionary() }); results.addView(c)
                }
                filtered.forEach { (ar, fr) ->
                    val c = card(); c.addView(label(ar, 18f, colorGold, true)); c.addView(label(fr, 16f, colorText))
                    val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    row.addView(button("نسخ") { copyText(fr) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                    row.addView(button("نطق") { speak(fr) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    c.addView(row)
                    results.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun showPersonalDictionary() {
        clearPage("قاموسي الشخصي"); page.tag = null
        addCard { addView(label("الكلمات المضافة: ${personalDictionary.size}", 18f, colorGold, true)) }
        personalDictionary.forEach { w ->
            addCard {
                addView(label(w.arabic, 18f, colorText, true)); addView(label(w.franco, 16f, colorGold))
                addView(button("حذف") { personalDictionary.remove(w); saveData(); showPersonalDictionary() })
            }
        }
    }

    private fun addToPersonalDictionary(ar: String, fr: String) {
        if (ar.isBlank() || fr.isBlank()) return
        personalDictionary.add(CustomWord(UUID.randomUUID().toString().take(6), ar, fr))
        saveData(); playSound(2); toast("📚 أضيفت")
    }

    // ============================================================
    // [البطاقات الذكية SRS]
    // ============================================================
    private fun showFlashcards() {
        clearPage("بطاقات الحفظ (SRS)"); page.tag = null
        val now = System.currentTimeMillis()
        val allWords = (dictionary.keys + personalDictionary.map { it.arabic }).toList()
        val due = allWords.filter { val s = flashcardStates[it]; s == null || s.nextReviewAt <= now }
        val learned = flashcardStates.size
        
        addCard {
            addView(label("🧠 التكرار المتباعد (SM-2)", 17f, colorGold, true))
            addView(label("مستحق اليوم: ${due.size} · في قاعدتك: $learned", 13f, colorMuted))
        }
        if (due.isEmpty()) {
            addCard { addView(label("✨ لا توجد بطاقات مستحقة!", 16f, colorGold, true)); addView(button("🎲 مراجعة عشوائية") { startFlashcardSession(allWords.shuffled().take(5)) }) }
        } else {
            addCard { addView(label("📚 ابدأ المراجعة", 16f, colorGold, true)); addView(button("▶️ ابدأ (${due.size})", true) { startFlashcardSession(due.take(20)) }) }
        }
    }

    private fun startFlashcardSession(words: List<String>) {
        if (words.isEmpty()) return; var pos = 0; var correct = 0
        fun next() {
            if (pos >= words.size) {
                score += correct * 5; checkLevelUp(); saveData(); playSound(2); clearPage("انتهت المراجعة")
                addCard { addView(label("✅ انتهت المراجعة!", 22f, colorGold, true)); addView(label("+${correct * 5} XP", 18f, colorGold)) }
                addAction("العودة", true) { showFlashcards() }; return
            }
            val word = words[pos]; val franco = toFranco(word); clearPage("بطاقة ${pos + 1}/${words.size}")
            addCard {
                addView(label(word, 32f, colorGold, true)); gap(this, 12)
                val ans = label("•••••", 24f, colorMuted); ans.gravity = Gravity.CENTER; addView(ans)
                addView(button("👁 إظهار الإجابة", true) { ans.text = franco; ans.setTextColor(colorGold) }); gap(this, 12)
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("✓ تذكرت") {
                    val s = flashcardStates[word] ?: FlashcardState(word); val e = (s.easeFactor + 0.1f).coerceAtMost(3.0f)
                    val i = if (s.intervalDays == 0) 1 else (s.intervalDays * e).toInt().coerceIn(1, 365)
                    flashcardStates[word] = s.copy(intervalDays = i, easeFactor = e, nextReviewAt = System.currentTimeMillis() + i * 86400000L, correctCount = s.correctCount + 1)
                    correct++; pos++; playSound(2); saveData(); next()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
                row.addView(button("✗ لم أتذكر") {
                    val s = flashcardStates[word] ?: FlashcardState(word)
                    flashcardStates[word] = s.copy(intervalDays = 1, easeFactor = (s.easeFactor - 0.2f).coerceAtLeast(1.3f), nextReviewAt = System.currentTimeMillis() + 86400000L, wrongCount = s.wrongCount + 1)
                    pos++; playSound(3); saveData(); next()
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }
        }
        next()
    }

    // ============================================================
    // [المفضلة، الأوسمة، الإحصائيات]
    // ============================================================
    private fun showSaved() {
        clearPage("المفضلة والسجل"); page.tag = null
        addCard { addView(label("⭐ المفضلة (${favorites.size})", 19f, colorGold, true)) }
        favorites.forEach { item ->
            addCard {
                addView(label(item.optString("input"), 16f, colorText)); addView(label(item.optString("output"), 16f, colorGold))
                val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(button("نسخ") { copyText(item.optString("output")) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
                row.addView(button("حذف") { favorites.remove(item); saveData(); showSaved() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(row)
            }
        }
        addAction("تصدير المفضلة PDF", true) { exportToPdf() }
    }

    private fun showBadges() {
        clearPage("الأوسمة"); page.tag = null
        badgeDefinitions.forEach { b ->
            addCard {
                val unlocked = unlockedBadges.contains(b.id)
                addView(label(b.icon + " " + b.title, 18f, if (unlocked) colorGold else colorMuted, true))
                addView(label(b.desc, 14f, colorMuted))
            }
        }
    }

    private fun showLevels() {
        clearPage("المستويات"); page.tag = null
        addCard {
            addView(label("المستوى الحالي: $level", 20f, colorGold, true)); addView(label("الخبرة: $score XP", 16f))
            val progress = (score % 100); addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; this.progress = progress; progressTintList = ColorStateList.valueOf(colorGold) })
        }
    }

    private fun showDailyChallenge() {
        clearPage("التحدي اليومي"); page.tag = null
        addCard { addView(label("🎯 التحدي اليومي (+50 XP)", 18f, colorGold, true)); addView(button("ابدأ التحدي", true) { startQuiz(-1) }) }
    }

    private fun showProgressChart() {
        clearPage("مخطط التقدم"); page.tag = null
        addCard { addView(label("🔥 سلسلتك: $streakCount يوم", 18f, colorGold, true)); addView(label("الترجمات الكلية: ${history.size}", 15f)) }
    }

    private fun showQuickPhrases() {
        clearPage("عبارات سريعة"); page.tag = null
        listOf("صباح الخير" to "saba7 elkhair", "شلونك" to "shlonak", "شكراً" to "shukran", "وينك" to "wainak").forEach { (ar, fr) ->
            addCard { addView(label(ar, 18f, colorText)); addView(label(fr, 16f, colorGold)); addView(button("نسخ") { copyText(fr) }) }
        }
    }

    private fun showStatistics() {
        clearPage("الإحصائيات"); page.tag = null
        addCard {
            addView(label("الترجمات: ${history.size}", 16f)); addView(label("المفضلة: ${favorites.size}", 16f))
            addView(label("المستوى: $level · $score XP", 18f, colorGold, true))
        }
    }

    // ============================================================
    // [الإعدادات]
    // ============================================================
    private fun showSettings() {
        clearPage("الإعدادات"); page.tag = null
        addCard {
            addView(label("🔐 الأمان", 18f, colorGold, true))
            addView(button("قفل بصمة الإصبع: ${if (isAppLockEnabled) "مفعل" else "متوقف"}") {
                isAppLockEnabled = !isAppLockEnabled; prefs.edit().putBoolean("app_lock", isAppLockEnabled).apply(); showSettings()
            })
        }
        addCard {
            addView(label("🎙 النطق", 18f, colorGold, true))
            addView(button("نطق تلقائي: ${if (ttsAutoSpeak) "مفعل" else "متوقف"}") { ttsAutoSpeak = !ttsAutoSpeak; prefs.edit().putBoolean("auto_speak", ttsAutoSpeak).apply(); showSettings() })
            addView(button("🔊 اختبار النطق") { speak("اختبار النطق في TON FOX") })
        }
        addCard {
            addView(label("💾 البيانات", 18f, colorGold, true))
            addView(button("حذف جميع البيانات") {
                AlertDialog.Builder(this).setTitle("تأكيد").setMessage("حذف كل شيء؟").setPositiveButton("نعم") { _, _ ->
                    history.clear(); favorites.clear(); completed.clear(); score = 0; level = 1; saveData(); showHome(); toast("تم المسح")
                }.setNegativeButton("لا", null).show()
            })
        }
    }

    // ============================================================
    // [تصدير PDF والمشاركة كصورة]
    // ============================================================
    private fun exportToPdf() {
        if (favorites.isEmpty()) { toast("المفضلة فارغة"); return }
        try {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/pdf"; putExtra(Intent.EXTRA_TITLE, "TON_FOX_Favorites.pdf") }
            @Suppress("DEPRECATION") startActivityForResult(intent, REQ_PDF)
        } catch (e: Exception) { toast("تعذر التصدير") }
    }

    private fun generatePdf(uri: Uri) {
        try {
            val pdf = PdfDocument(); val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create(); val page = pdf.startPage(pageInfo); val canvas = page.canvas
            val paint = Paint().apply { color = Color.BLACK; textSize = 14f }; var y = 50f
            paint.textSize = 24f; paint.isFakeBoldText = true; canvas.drawText("TON FOX - Favorites", 50f, y, paint); y += 40f
            paint.textSize = 14f; paint.isFakeBoldText = false
            for (item in favorites) { canvas.drawText("${item.optString("input")} -> ${item.optString("output")}", 50f, y, paint); y += 25f; if (y > 800f) break }
            pdf.finishPage(page); contentResolver.openOutputStream(uri)?.use { pdf.writeTo(it) }; pdf.close(); toast("✔ تم التصدير"); playSound(2)
        } catch (e: Exception) { toast("فشل: ${e.message}") }
    }

    private fun shareAsImage(text: String) {
        if (text.isBlank() || text == "ستظهر الترجمة هنا...") return
        try {
            val bmp = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888); val canvas = Canvas(bmp); val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = colorBg; canvas.drawRect(0f, 0f, 1080f, 1080f, paint)
            paint.color = colorGold; paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; canvas.drawRoundRect(RectF(30f, 30f, 1050f, 1050f), 32f, 32f, paint)
            paint.style = Paint.Style.FILL; paint.color = colorGold; paint.textSize = 56f; paint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); paint.textAlign = Paint.Align.CENTER
            canvas.drawText("TON FOX", 540f, 140f, paint); paint.color = colorText; paint.textSize = 44f; paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText(text, 540f, 540f, paint)
            val uri = MediaStore.Images.Media.insertImage(contentResolver, bmp, "TON FOX", "Translation")
            val share = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(share, "مشاركة"))
        } catch (e: Exception) { toast("فشل الإنشاء") }
    }

    // ============================================================
    // [البيانات والترجمة]
    // ============================================================
    private fun loadData() {
        score = prefs.getInt("score", 0); level = prefs.getInt("level", 1); streakCount = prefs.getInt("streak", 0); lastOpenDate = prefs.getString("last_open", "") ?: ""
        try { val a = JSONArray(prefs.getString("history", "[]")); for (i in 0 until a.length()) history.add(a.getJSONObject(i)) } catch (_: Exception) {}
        try { val a = JSONArray(prefs.getString("favorites", "[]")); for (i in 0 until a.length()) favorites.add(a.getJSONObject(i)) } catch (_: Exception) {}
        try { val a = JSONArray(prefs.getString("lessons", "[]")); for (i in 0 until a.length()) completed.add(a.getInt(i)) } catch (_: Exception) {}
        try { val a = JSONArray(prefs.getString("badges", "[]")); for (i in 0 until a.length()) unlockedBadges.add(a.getString(i)) } catch (_: Exception) {}
    }

    private fun saveData() {
        val h = JSONArray().apply { history.forEach { put(it) } }; val f = JSONArray().apply { favorites.forEach { put(it) } }
        val l = JSONArray().apply { completed.forEach { put(it) } }; val b = JSONArray().apply { unlockedBadges.forEach { put(it) } }
        prefs.edit().putInt("score", score).putInt("level", level).putInt("streak", streakCount).putString("last_open", lastOpenDate)
            .putString("history", h.toString()).putString("favorites", f.toString()).putString("lessons", l.toString()).putString("badges", b.toString()).apply()
    }

    private fun updateStreakOnOpen() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()); if (lastOpenDate == today) return
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time)
        if (lastOpenDate == yesterday) streakCount++ else streakCount = 1; lastOpenDate = today; saveData()
    }

    private fun saveHistory(input: String, output: String, dir: String) {
        history.add(0, JSONObject().put("input", input).put("output", output).put("dir", dir).put("ts", System.currentTimeMillis())); saveData()
    }

    private fun addFavorite(input: String, output: String) {
        favorites.add(0, JSONObject().put("input", input).put("output", output)); saveData(); toast("⭐ تم الحفظ")
    }

    private fun unlockBadge(id: String) {
        if (unlockedBadges.add(id)) { score += 30; saveData(); playSound(2); toast("🎉 وسام جديد!") }
    }

    private fun checkLevelUp() {
        val old = level; level = score / 100 + 1
        if (level > old) { playSound(2); toast("⭐ ارتقيت للمستوى $level!") }
    }

    private fun shareText(s: String) { val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$s\n\nTON FOX 🦊") }; startActivity(Intent.createChooser(i, "مشاركة")) }

    private fun toFranco(s: String): String { return dictionary[s.trim()] ?: s.map { arabicMap[it] ?: it.toString() }.joinToString("") }
    private fun toArabic(s: String): String { val rev = dictionary.entries.associate { it.value.lowercase() to it.key }; return rev[s.trim().lowercase()] ?: s }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == REQ_PDF && data?.data != null) generatePdf(data.data!!)
    }
}
