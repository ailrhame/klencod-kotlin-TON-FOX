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
import android.view.inputmethod.EditorInfo
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
private const val REQ_PICK_KB_BG = 9006
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
// [KEYBOARD SERVICE — Customizable Size & Spacing]
// ============================================================

class FrancoKeyboardService : InputMethodService() {

    private var isFrancoMode = true
    private var isShift = false
    private var isNumericMode = false
    private var isNumericFieldMode = false
    private var isPhoneFieldMode = false
    private var prefs: SharedPreferences? = null
    private var lastCommittedText = ""
    private val typedArabicPreview = StringBuilder()
    private var lastArabicPreviewKey = ""
    private var arabicPreviewView: TextView? = null

    private val arabicToFranco = mapOf(
        "ض" to "9'", "ص" to "9", "ث" to "th", "ق" to "q",
        "ف" to "f", "غ" to "gh", "ع" to "3", "ه" to "h",
        "خ" to "kh", "ح" to "7", "ج" to "j", "چ" to "ch",
        "ش" to "sh", "س" to "s", "ي" to "y", "ب" to "b",
        "ل" to "l", "ا" to "a", "ت" to "t", "ن" to "n",
        "م" to "m", "ك" to "k", "ط" to "6", "ئ" to "2",
        "ء" to "2", "ؤ" to "2", "ر" to "r", "لا" to "la",
        "ى" to "a", "ة" to "a", "و" to "w", "ز" to "z",
        "ظ" to "6'", "د" to "d", "ذ" to "dh"
    )

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val inputClass = attribute.inputType and EditorInfo.TYPE_MASK_CLASS
        isPhoneFieldMode = inputClass == EditorInfo.TYPE_CLASS_PHONE
        isNumericFieldMode = isPhoneFieldMode || inputClass == EditorInfo.TYPE_CLASS_NUMBER
        isNumericMode = isNumericFieldMode
        lastCommittedText = ""
        typedArabicPreview.setLength(0)
        lastArabicPreviewKey = ""
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // إعادة بناء الواجهة عند الانتقال بين خانة نصية وخانة رقمية/هاتف.
        setInputView(onCreateInputView())
    }

    override fun onCreateInputView(): View {
        prefs = getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)

        val bgType = prefs?.getString("kb_bg_type", "dark") ?: "dark"
        val root = FrameLayout(this)

        when (bgType) {
            "image" -> {
                val uriText = prefs?.getString("kb_bg_uri", null)
                var loaded = false
                if (!uriText.isNullOrBlank()) {
                    try {
                        contentResolver.openInputStream(Uri.parse(uriText))?.use { stream ->
                            val bitmap = BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                root.background = BitmapDrawable(resources, bitmap).apply {
                                    gravity = Gravity.FILL
                                }
                                loaded = true
                            }
                        }
                    } catch (_: Exception) {
                        loaded = false
                    }
                }
                if (!loaded) root.setBackgroundColor(Color.parseColor("#0B0914"))
            }
            "light" -> root.setBackgroundColor(Color.parseColor("#F0F0F0"))
            else -> root.setBackgroundColor(Color.parseColor("#0B0914"))
        }

        val spacing = prefs?.getString("kb_spacing", "normal") ?: "normal"
        val margin = when (spacing) {
            "tight" -> dp(1)
            "wide" -> dp(4)
            else -> dp(2)
        }
        val outerPadding = when (spacing) {
            "tight" -> dp(2)
            "wide" -> dp(6)
            else -> dp(4)
        }

        // الارتفاع قيمة فعلية بالسنتيمتر، وتحويلها يعتمد على كثافة الشاشة الفيزيائية.
        val heightCm = (prefs?.getFloat("kb_height_cm", 4.8f) ?: 4.8f).coerceIn(3.5f, 8.0f)
        val metrics = resources.displayMetrics
        val physicalDpi = metrics.xdpi.takeIf { it in 100f..1000f } ?: (metrics.density * 160f)
        val requestedHeightPx = (heightCm * physicalDpi / 2.54f).toInt()

        // صف معاينة عربية مستقل أسفل العنوان، ثم الأرقام وثلاثة صفوف أحرف وشريط الوظائف.
        val headerHeight = dp(26)
        val previewHeight = if (isNumericMode) 0 else dp(30)
        val rowCount = 5
        val heightForRows = requestedHeightPx - headerHeight - previewHeight - (outerPadding * 2) - (margin * (rowCount + 1))
        val keyHeight = (heightForRows / rowCount).coerceIn(dp(25), dp(90))
        val keyHeightDp = keyHeight / metrics.density
        val keyTextSp = (keyHeightDp * 0.49f).coerceIn(12f, 26f)
        val specialTextSp = (keyTextSp * 0.82f).coerceAtLeast(11f)

        val style = prefs?.getString("kb_style", "soft") ?: "soft"
        val radius = if (style == "square") dp(3).toFloat() else dp(7).toFloat()
        val isLight = bgType == "light"
        val keyColor = if (isLight) Color.WHITE else Color.parseColor("#171324")
        val textColor = if (isLight) Color.parseColor("#171324") else Color.WHITE
        val specialColor = if (isLight) Color.parseColor("#E4DFEA") else Color.parseColor("#332B45")
        val gold = Color.parseColor("#FFB800")
        val thinBorder = withAlpha(if (isLight) Color.BLACK else Color.WHITE, 0.10f)

        val main = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(outerPadding, outerPadding, outerPadding, outerPadding)
            clipChildren = false
            clipToPadding = false
        }
        root.addView(main, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val header = TextView(this).apply {
            text = when {
                isNumericMode && isPhoneFieldMode -> "TON FOX  •  لوحة الهاتف"
                isNumericMode -> "TON FOX  •  لوحة الأرقام"
                isFrancoMode -> "TON FOX  •  عربي ⇢ فرانكو"
                else -> "TON FOX  •  English QWERTY"
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(gold)
            includeFontPadding = false
            setPadding(dp(5), 0, dp(5), 0)
            background = GradientDrawable().apply {
                setColor(if (isLight) Color.WHITE else Color.parseColor("#CC171324"))
                cornerRadius = dp(7).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, headerHeight).apply {
                bottomMargin = margin
            }
            setOnClickListener {
                isFrancoMode = !isFrancoMode
                isShift = false
                lastCommittedText = ""
                typedArabicPreview.setLength(0)
                lastArabicPreviewKey = ""
                setInputView(onCreateInputView())
            }
        }
        main.addView(header)

        if (!isNumericMode) {
            arabicPreviewView = TextView(this).apply {
                text = if (typedArabicPreview.isEmpty()) "المعاينة العربية ستظهر هنا" else typedArabicPreview.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_RTL
                setTextColor(if (isLight) Color.parseColor("#332B45") else Color.parseColor("#FFE2A0"))
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.START
                setPadding(dp(8), 0, dp(8), 0)
                background = GradientDrawable().apply {
                    setColor(if (isLight) Color.parseColor("#E8E4EF") else Color.parseColor("#241D32"))
                    cornerRadius = dp(5).toFloat()
                    setStroke(dp(1), thinBorder)
                }
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, previewHeight).apply {
                    bottomMargin = margin
                }
            }
            main.addView(arabicPreviewView)
        } else {
            arabicPreviewView = null
        }

        val arabicRows = listOf(
            listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"),
            listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط", "ذ"),
            listOf("⇧", "ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ", "⌫")
        )
        val englishRows = listOf(
            listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫")
        )

        fun createKey(key: String, keyWeight: Float = 1f): TextView {
            val special = key == "⇧" || key == "⌫"
            val shownText = if (!isFrancoMode && isShift && key.length == 1) {
                key.uppercase(Locale.ROOT)
            } else key

            return TextView(this).apply {
                text = shownText
                val fittedKeyTextSp = if (!special && key.length > 1) keyTextSp * 0.78f else keyTextSp
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (special) specialTextSp else fittedKeyTextSp)
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                setPadding(dp(1), 0, dp(1), 0)
                setTextColor(if (special) gold else textColor)
                typeface = if (special) Typeface.DEFAULT else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = GradientDrawable().apply {
                    setColor(if (special) specialColor else keyColor)
                    cornerRadius = radius
                    setStroke(dp(1), thinBorder)
                }
                isClickable = true
                isFocusable = true
                isHapticFeedbackEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, keyWeight).apply {
                    setMargins(margin, 0, margin, 0)
                }
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    handleKeyPress(key)
                }
            }
        }

        fun addKeyRow(keys: List<String>, units: Int = 12, rtl: Boolean = isFrancoMode) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
                clipChildren = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, keyHeight).apply {
                    bottomMargin = margin
                }
            }
            val hasControlKeys = keys.any { it == "⇧" || it == "⌫" }
            val keyWeights = keys.map { key ->
                when (key) {
                    "⇧", "⌫" -> 1.5f
                    else -> if (hasControlKeys) 0.9f else 1f
                }
            }
            val occupiedUnits = keyWeights.sum()
            val sideWeight = ((units - occupiedUnits).coerceAtLeast(0f)) / 2f
            if (sideWeight > 0f) {
                rowLayout.addView(View(this), LinearLayout.LayoutParams(0, 1, sideWeight))
            }
            keys.forEachIndexed { index, key -> rowLayout.addView(createKey(key, keyWeights[index])) }
            if (sideWeight > 0f) {
                rowLayout.addView(View(this), LinearLayout.LayoutParams(0, 1, sideWeight))
            }
            main.addView(rowLayout)
        }

        fun bottomKey(
            title: String,
            weight: Float,
            backgroundColor: Int,
            foregroundColor: Int,
            action: () -> Unit
        ): TextView {
            return TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (title == "مسافة") minOf(15f, keyTextSp) else minOf(14f, keyTextSp))
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                isSingleLine = true
                setPadding(dp(2), 0, dp(2), 0)
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(foregroundColor)
                background = GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = radius
                    setStroke(dp(1), thinBorder)
                }
                layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
                    setMargins(margin, 0, margin, 0)
                }
                isHapticFeedbackEnabled = true
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                }
            }
        }

        if (isNumericMode) {
            // لوحة 3×4 حقيقية؛ الهاتف يعرض * و#، والأرقام العادية تعرض - و.
            val numericRows = if (isPhoneFieldMode) {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
            } else {
                listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("-", "0", "."))
            }
            numericRows.forEach { addKeyRow(it, 4, rtl = false) }

            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, keyHeight)
            }
            bottom.addView(bottomKey("ABC", 1.35f, specialColor, gold) {
                isNumericMode = false
                setInputView(onCreateInputView())
            })
            bottom.addView(bottomKey("⌫", 1.25f, specialColor, gold) { handleKeyPress("⌫") })
            bottom.addView(bottomKey("إرسال", 1.7f, gold, Color.BLACK) { sendKeyboardEnter() })
            main.addView(bottom)
        } else {
            // صف الأرقام يبقى ظاهراً دائماً حتى لا يضطر المستخدم لفتح طبقة أخرى.
            addKeyRow((1..9).map { it.toString() } + "0", 12)
            val letterRows = if (isFrancoMode) arabicRows else englishRows
            letterRows.forEach { addKeyRow(it, 12) }

            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, keyHeight)
            }
            bottom.addView(bottomKey("?123", 1.35f, specialColor, gold) {
                isNumericMode = true
                setInputView(onCreateInputView())
            })
            bottom.addView(bottomKey("،", 0.9f, specialColor, textColor) { commitKeyboardText(",") })
            bottom.addView(bottomKey("مسافة", 3.7f, keyColor, textColor) { commitKeyboardText(" ") })
            bottom.addView(bottomKey(".", 0.9f, specialColor, textColor) { commitKeyboardText(".") })
            bottom.addView(bottomKey("إرسال", 1.7f, gold, Color.BLACK) { sendKeyboardEnter() })
            main.addView(bottom)
        }

        root.minimumHeight = requestedHeightPx
        root.setPadding(0, 0, 0, 0)
        return root
    }

    private fun sendKeyboardEnter() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        lastCommittedText = ""
    }

    private fun commitKeyboardText(value: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(value, 1)
        lastCommittedText = value
        if (value.isBlank()) {
            typedArabicPreview.setLength(0)
            lastArabicPreviewKey = ""
            updateArabicPreview()
        }
    }

    private fun updateArabicPreview() {
        arabicPreviewView?.text = if (typedArabicPreview.isEmpty()) {
            "المعاينة العربية ستظهر هنا"
        } else {
            typedArabicPreview.toString()
        }
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return

        when (key) {
            "⌫" -> {
                if (lastCommittedText.isNotEmpty()) {
                    ic.deleteSurroundingText(lastCommittedText.length, 0)
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
                lastCommittedText = ""
                if (isFrancoMode && lastArabicPreviewKey.isNotEmpty()) {
                    repeat(lastArabicPreviewKey.length.coerceAtMost(typedArabicPreview.length)) {
                        typedArabicPreview.deleteCharAt(typedArabicPreview.length - 1)
                    }
                    lastArabicPreviewKey = ""
                    updateArabicPreview()
                }
            }
            "⇧" -> {
                isShift = !isShift
                setInputView(onCreateInputView())
            }
            "?123" -> {
                isNumericMode = true
                setInputView(onCreateInputView())
            }
            "ABC" -> {
                isNumericMode = false
                setInputView(onCreateInputView())
            }
            else -> {
                val output = if (isFrancoMode) {
                    arabicToFranco[key] ?: key
                } else {
                    if (isShift) key.uppercase(Locale.ROOT) else key
                }
                if (isFrancoMode && arabicToFranco.containsKey(key)) {
                    typedArabicPreview.append(key)
                    lastArabicPreviewKey = key
                    updateArabicPreview()
                } else {
                    lastArabicPreviewKey = ""
                }
                commitKeyboardText(output)
                if (!isFrancoMode && isShift) {
                    isShift = false
                    setInputView(onCreateInputView())
                }
            }
        }
    }

    private fun toastKeyboard(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

// ============================================================
// [ACCESSIBILITY LENS — Floating on top of the phone's own keyboard]
// Requires the service declaration and XML config supplied with this source.
// ============================================================
class TonFoxLensAccessibilityService : android.accessibilityservice.AccessibilityService() {
    private var windowManager: WindowManager? = null
    private var lensButton: TextView? = null
    private var attached = false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val button = TextView(this).apply {
            text = "عدسة"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D91B1726"))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(2), Color.parseColor("#FFFFB800"))
            }
            elevation = dp(8).toFloat()
            setOnClickListener { convertFocusedArabicText() }
        }
        lensButton = button
        val params = WindowManager.LayoutParams(
            dp(58), dp(48),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(8)
            y = dp(40)
        }
        try {
            windowManager?.addView(button, params)
            attached = true
            button.visibility = View.GONE
        } catch (_: Exception) {
            attached = false
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        val enabled = getSharedPreferences("ton_fox_data", Context.MODE_PRIVATE)
            .getBoolean("lens_mode_enabled", false)
        if (!enabled) {
            lensButton?.visibility = View.GONE
            return
        }
        val root = rootInActiveWindow
        val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focused?.isEditable == true
        lensButton?.visibility = if (editable) View.VISIBLE else View.GONE
    }

    private fun convertFocusedArabicText() {
        val root = rootInActiveWindow
        val node = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable) {
            Toast.makeText(this, "اضغط أولاً داخل حقل الكتابة", Toast.LENGTH_SHORT).show()
            return
        }
        val original = node.text?.toString().orEmpty()
        if (original.isBlank()) {
            Toast.makeText(this, "لا يوجد نص لتحويله", Toast.LENGTH_SHORT).show()
            return
        }
        val converted = TonFoxTransliterator.toFranco(original)
        if (converted == original) {
            Toast.makeText(this, "لم أجد حروفاً عربية لتحويلها", Toast.LENGTH_SHORT).show()
            return
        }
        val args = Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, converted)
        }
        val success = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!success) Toast.makeText(this, "هذا الحقل لا يسمح بتعديل النص عبر العدسة", Toast.LENGTH_SHORT).show()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (attached) {
            try { windowManager?.removeView(lensButton) } catch (_: Exception) { }
        }
        attached = false
        lensButton = null
        windowManager = null
        super.onDestroy()
    }
}

private object TonFoxTransliterator {
    private val map = mapOf(
        'ض' to "9'", 'ص' to "9", 'ث' to "th", 'ق' to "q", 'ف' to "f", 'غ' to "gh",
        'ع' to "3", 'ه' to "h", 'خ' to "kh", 'ح' to "7", 'ج' to "j", 'چ' to "ch",
        'ش' to "sh", 'س' to "s", 'ي' to "y", 'ى' to "a", 'ب' to "b", 'ل' to "l",
        'ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa", 'ت' to "t", 'ن' to "n",
        'م' to "m", 'ك' to "k", 'ط' to "6", 'ئ' to "2", 'ء' to "2", 'ؤ' to "2",
        'ر' to "r", 'ة' to "a", 'و' to "w", 'ز' to "z", 'ظ' to "6'", 'د' to "d", 'ذ' to "dh"
    )

    fun toFranco(text: String): String = buildString {
        text.forEach { ch -> append(map[ch] ?: ch.toString()) }
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

    // أزرار نصية بسيطة بدون خلفية أو إطار؛ لتجنب قصّ الأزرار أو ظهور نصفها.
    private fun button(t: String, primary: Boolean = false, action: () -> Unit): TextView =
        TextView(this).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(if (primary) colorGold else colorText)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(10), dp(10), dp(10))
            minHeight = dp(48)
            background = null
            isClickable = true
            isFocusable = true
            setOnClickListener {
                playSound(1)
                vibrate(15)
                action()
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

    // ============================================================
    // [SRS FLASHCARDS]
    // ============================================================
    private fun showFlashcards() {
        clearPage("بطاقات SRS 🧠")
        val allWords = dictionary.keys.toList()
        val due = allWords.filter { !flashcardStates.containsKey(it) || (flashcardStates[it]?.nextReviewAt ?: 0) <= System.currentTimeMillis() }
        
        if (due.isEmpty()) {
            addCard { 
                addView(label("✨ لا توجد بطاقات مستحقة الآن!", 18f, colorGold, true))
                addView(button("🎲 مراجعة عشوائية", true) { startFlashcardSession(allWords.shuffled().take(5)) })
            }
            return
        }
        
        addCard {
            addView(label("📚 لديك ${due.size} كلمة مستحقة للمراجعة", 16f, colorText))
            addView(button("▶️ ابدأ الآن", true) { startFlashcardSession(due.take(20)) })
        }
    }

    private fun startFlashcardSession(words: List<String>) {
        if (words.isEmpty()) return
        var pos = 0
        var correct = 0
        
        fun next() {
            if (pos >= words.size) {
                val earned = correct * 10
                score += earned; saveData(); playSound(2)
                clearPage("انتهت المراجعة")
                addCard {
                    addView(label("✅ انتهت المراجعة!", 22f, colorGold, true))
                    addView(label("صحيحة: $correct/${words.size}", 16f))
                    addView(label("+$earned XP", 18f, colorGold, true))
                }
                addAction("العودة", true) { showFlashcards() }
                return
            }
            
            val word = words[pos]
            val franco = toFranco(word)
            clearPage("بطاقة ${pos + 1}/${words.size}")
            
            var isFlipped = false
            val cardView = card()
            val qView = label(word, 32f, colorGold, true)
            qView.gravity = Gravity.CENTER
            val aView = label(franco, 32f, colorText, true)
            aView.gravity = Gravity.CENTER
            aView.visibility = View.GONE
            
            cardView.addView(qView)
            cardView.addView(aView)
            cardView.setOnClickListener {
                isFlipped = !isFlipped
                cardView.animate().rotationY(90f).setDuration(150).withEndAction {
                    qView.visibility = if (isFlipped) View.GONE else View.VISIBLE
                    aView.visibility = if (isFlipped) View.VISIBLE else View.GONE
                    cardView.rotationY = -90f
                    cardView.animate().rotationY(0f).setDuration(150).start()
                }.start()
            }
            page.addView(cardView)
            
            addCard {
                addView(button("✓ تذكرت", true) {
                    val s = flashcardStates[word] ?: FlashcardState(word)
                    val nextInt = if (s.intervalDays == 0) 1 else (s.intervalDays * s.easeFactor).toInt().coerceIn(1, 365)
                    flashcardStates[word] = s.copy(intervalDays = nextInt, nextReviewAt = System.currentTimeMillis() + nextInt * 24L * 3600 * 1000)
                    correct++; pos++; next()
                })
                addView(button("✗ نسيت") {
                    val s = flashcardStates[word] ?: FlashcardState(word)
                    flashcardStates[word] = s.copy(intervalDays = 1, nextReviewAt = System.currentTimeMillis() + 24L * 3600 * 1000)
                    pos++; next()
                })
            }
        }
        next()
    }

    private fun showFoxProfile() {
        clearPage("بروفايل الثعلب 🦊")
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

    // ============================================================
    // [SETTINGS]
    // ============================================================
    private fun showSettings() {
    clearPage("الإعدادات")
    
    // تفعيل الكيبورد
    addCard {
        addView(label("⌨️ تفعيل لوحة المفاتيح", 18f, colorGold, true))
        addView(label("لتفعيل TON FOX ككيبورد افتراضي، اتبع الخطوتين:", 14f, colorMuted))
        gap(this, 8)
        addView(button("1️⃣ تفعيل TON FOX في النظام", true) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) { toast("تعذر فتح الإعدادات") }
        })
        addView(button("2️⃣ اختيار TON FOX ككيبورد حالي") {
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) { toast("تعذر فتح قائمة الكيبورد") }
        })
    }

    // إعدادات الكيبورد
    addCard {
        addView(label("🎨 إعدادات مظهر الكيبورد", 18f, colorGold, true))
        
        // تحكم حر بارتفاع لوحة المفاتيح بالسنتيمتر، مع منزلق وقيمة رقمية دقيقة.
        addView(label("ارتفاع الكيبورد بالسنتيمتر", 15f, colorMuted, true))
        addView(label("اكتب القياس بنفسك أو حرّك المؤشر. كل 0.1 سم يغيّر الارتفاع تدريجياً. قد يختلف القياس الفعلي قليلاً حسب معايرة شاشة الهاتف.", 12f, colorMuted))
        val minKeyboardCm = 3.5f
        val maxKeyboardCm = 8.0f
        var selectedKeyboardCm = (prefs.getFloat("kb_height_cm", 4.8f)).coerceIn(minKeyboardCm, maxKeyboardCm)
        val heightLabel = label("الارتفاع الحالي: %.1f سم".format(Locale.US, selectedKeyboardCm), 16f, colorGold, true)
        addView(heightLabel)

        val heightSeek = SeekBar(this@MainActivity).apply {
            max = 45
            progress = ((selectedKeyboardCm - minKeyboardCm) * 10f).toInt().coerceIn(0, max)
        }
        addView(heightSeek, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val heightInputRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heightInput = EditText(this@MainActivity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            setText("%.1f".format(Locale.US, selectedKeyboardCm))
            setTextColor(colorText)
            setHintTextColor(colorMuted)
            hint = "مثلاً 5.4"
            textSize = 16f
            gravity = Gravity.CENTER
            background = shape(colorBg, 8, colorBorder)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        heightInputRow.addView(heightInput)
        heightInputRow.addView(label("سم", 14f, colorMuted).apply {
            setPadding(dp(8), 0, dp(8), 0)
        })
        addView(heightInputRow)

        fun updateKeyboardHeight(value: Float, updateInput: Boolean = true) {
            selectedKeyboardCm = value.coerceIn(minKeyboardCm, maxKeyboardCm)
            saveSetting("kb_height_cm", selectedKeyboardCm)
            heightLabel.text = "الارتفاع الحالي: %.1f سم".format(Locale.US, selectedKeyboardCm)
            heightSeek.progress = ((selectedKeyboardCm - minKeyboardCm) * 10f).toInt().coerceIn(0, heightSeek.max)
            if (updateInput) heightInput.setText("%.1f".format(Locale.US, selectedKeyboardCm))
        }

        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedKeyboardCm = minKeyboardCm + (progress / 10f)
                heightLabel.text = "الارتفاع الحالي: %.1f سم".format(Locale.US, selectedKeyboardCm)
                heightInput.setText("%.1f".format(Locale.US, selectedKeyboardCm))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                updateKeyboardHeight(selectedKeyboardCm)
                toast("تم حفظ ارتفاع الكيبورد. أعد فتحه لتطبيق القياس.")
            }
        })

        val heightActions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        heightActions.addView(button("− 0.1 سم") {
            updateKeyboardHeight(selectedKeyboardCm - 0.1f)
            toast("تم حفظ القياس")
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        heightActions.addView(button("+ 0.1 سم") {
            updateKeyboardHeight(selectedKeyboardCm + 0.1f)
            toast("تم حفظ القياس")
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        heightActions.addView(button("تطبيق القياس", true) {
            val parsed = heightInput.text.toString().trim().replace(',', '.').toFloatOrNull()
            if (parsed == null) {
                toast("أدخل رقماً صالحاً مثل 5.4")
            } else {
                updateKeyboardHeight(parsed)
                toast("تم حفظ الارتفاع بالسنتيمتر. أعد فتح الكيبورد.")
            }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.2f))
        addView(heightActions)
        gap(this, 10)

        // 2. المسافات بين الأزرار
        addView(label("المسافات بين الأزرار:", 14f, colorMuted))
        val spacingRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
        val currentSpacing = prefs.getString("kb_spacing", "normal") ?: "normal"
        listOf("tight" to "ضيق", "normal" to "عادي", "wide" to "واسع").forEach { (id, name) ->
            spacingRow.addView(button(if (currentSpacing == id) "✓ $name" else name) {
                saveSetting("kb_spacing", id); toast("تم تغيير المسافات")
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        addView(spacingRow)

        // 3. شكل الأزرار
        addView(label("شكل المفاتيح (بدون كبسولات):", 14f, colorMuted))
        val styleRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
        val savedStyle = prefs.getString("kb_style", "soft") ?: "soft"
        val currentStyle = if (savedStyle == "square") "square" else "soft"
        listOf("square" to "مربع ناعم", "soft" to "زوايا خفيفة").forEach { (id, name) ->
            styleRow.addView(button(if (currentStyle == id) "✓ $name" else name) {
                saveSetting("kb_style", id); toast("تم تغيير شكل الكيبورد")
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        addView(styleRow)

        // وضع العدسة: يسمح باستخدام كيبورد الهاتف الأصلي مع زر عائم للتحويل عند الطلب.
        val lensEnabled = prefs.getBoolean("lens_mode_enabled", false)
        addView(label("🪄 وضع العدسة العائمة", 15f, colorGold, true))
        addView(label("يستخدم كيبورد الهاتف الأصلي بدلاً من TON FOX. تظهر عدسة فوق حقل الكتابة؛ اضغطها لتحويل النص العربي المكتوب إلى فرانكو.", 12f, colorMuted))
        addView(button(if (lensEnabled) "✓ وضع العدسة مفعّل — إعدادات إمكانية الوصول" else "تفعيل وضع العدسة العائمة", true) {
            val nextEnabled = !prefs.getBoolean("lens_mode_enabled", false)
            saveSetting("lens_mode_enabled", nextEnabled)
            if (nextEnabled) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    toast("فعّل خدمة TON FOX Lens من إمكانية الوصول، ثم اختر كيبورد الهاتف الأصلي")
                } catch (_: Exception) {
                    toast("تعذر فتح إعدادات إمكانية الوصول")
                }
            } else {
                toast("تم إيقاف وضع العدسة")
            }
            showSettings()
        })

        addView(label("الأرقام تظهر دائماً فوق الحروف، وحقول الهاتف والرقم تفتح لوحة أرقام تلقائياً.", 12f, colorMuted))

        // الخلفية
        addView(label("خلفية الكيبورد:", 14f, colorMuted))
        val bgRow = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        val currentBg = prefs.getString("kb_bg_type", "dark") ?: "dark"
        listOf("dark" to "داكن", "light" to "فاتح").forEach { (id, name) ->
            bgRow.addView(button(if (currentBg == id) "✓ $name" else name) {
                saveSetting("kb_bg_type", id); toast("تم تغيير خلفية الكيبورد")
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) })
        }
        bgRow.addView(button("🖼️ صورة", true) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            @Suppress("DEPRECATION") startActivityForResult(intent, REQ_PICK_KB_BG)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(bgRow)
    }

    addCard {
        addView(label("🔊 الصوت والنطق", 18f, colorGold, true))
        val isSpeakOnOpen = prefs.getBoolean("speak_on_open", true)
        addView(button(if (isSpeakOnOpen) "🔊 نطق كلمة اليوم تلقائياً: مفعل" else "🔇 نطق كلمة اليوم تلقائياً: متوقف") {
            saveSetting("speak_on_open", !isSpeakOnOpen)
            showSettings()
        })
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
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // بعض مصادر الصور لا تدعم الإذن الدائم
            }

            saveSetting("kb_bg_uri", uri.toString())
            saveSetting("kb_bg_type", "image")

            toast("✔ تم تعيين صورة الخلفية للكيبورد")
        } catch (e: Exception) {
            toast("تعذر حفظ صورة الخلفية")
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