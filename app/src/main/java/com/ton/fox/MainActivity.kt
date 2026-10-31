package com.ton.fox

import android.app.*
import android.os.*
import android.content.*
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import android.text.*
import android.text.method.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val gold = Color.rgb(255, 193, 65)
    private val dark = Color.rgb(15, 13, 19)
    private val panel = Color.rgb(29, 26, 35)
    private val cream = Color.rgb(245, 239, 225)
    private val muted = Color.rgb(165, 158, 170)

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var prefs: android.content.SharedPreferences

    private var currentPage = "home"
    private var currentDialect = "العراقية"

    private var sourceText: EditText? = null
    private var resultText: TextView? = null

    private var score = 0
    private var level = 1
    private var streak = 0

    private val history = mutableListOf<String>()
    private val favorites = mutableListOf<String>()
    private val completedLessons = mutableSetOf<Int>()
    private val achievements = mutableSetOf<String>()

    private val lessons = listOf(
        Lesson(
            "مقدمة في الفرانكو",
            "تعرف على لغة الفرانكو وأسباب استخدامها.",
            "الفرانكو هي طريقة لكتابة الكلمات العربية باستخدام الحروف اللاتينية والأرقام."
        ),
        Lesson(
            "الحروف الأساسية",
            "تعلم تحويل الحروف العربية إلى حروف لاتينية.",
            "استخدم a و b و t و j و d و r و z و s لكتابة الأصوات العربية المقابلة."
        ),
        Lesson(
            "الأرقام في الفرانكو",
            "تعرف على الأرقام التي تمثل الحروف العربية.",
            "2 = أ أو ء، 3 = ع، 5 = خ، 6 = ط، 7 = ح، 8 = غ، 9 = ص."
        ),
        Lesson(
            "كتابة الكلمات",
            "تعلم دمج الحروف لتكوين الكلمات.",
            "مثال: كتاب = ketab، سلام = salam، حبيبي = 7abibi."
        ),
        Lesson(
            "الجمل والمحادثات",
            "استخدم الفرانكو في المحادثات اليومية.",
            "مثال: كيف حالك؟ = keef 7alak."
        ),
        Lesson(
            "اللهجة العراقية",
            "تعلم مفردات عراقية شائعة.",
            "شلونك = shlonak، شكو ماكو = shako mako، زين = zain."
        ),
        Lesson(
            "الاحتراف",
            "تعلم كتابة الجمل الطويلة وفهم اختلاف الأنماط.",
            "احرص على ثبات طريقة كتابة الكلمات، واستخدم السياق لتجنب الالتباس."
        )
    )

    private data class Lesson(
        val title: String,
        val description: String,
        val body: String
    )

    private val dictionary = mapOf(
        "مرحبا" to "mar7aba",
        "السلام" to "alsalam",
        "السلام عليكم" to "alsalam 3alaykom",
        "وعليكم السلام" to "w 3alaykom alsalam",
        "كيف" to "keef",
        "حالك" to "7alak",
        "كيف حالك" to "keef 7alak",
        "شلونك" to "shlonak",
        "شلونج" to "shlonich",
        "شلونكم" to "shlonkom",
        "شكو ماكو" to "shako mako",
        "شنو" to "shنو",
        "ليش" to "leesh",
        "وين" to "wain",
        "هسه" to "hassa",
        "باچر" to "bachir",
        "باجر" to "bacher",
        "زين" to "zain",
        "حبيبي" to "7abibi",
        "حبيبتي" to "7abibti",
        "قلبي" to "galbi",
        "عمري" to "3omri",
        "احبك" to "a7ebak",
        "أحبك" to "a7ebak",
        "اشتقتلك" to "eshta2tellak",
        "شكرا" to "shukran",
        "شكراً" to "shukran",
        "عفوا" to "3afwan",
        "نعم" to "na3am",
        "لا" to "la",
        "اي" to "ay",
        "ايوا" to "aywa",
        "مو" to "mo",
        "ماكو" to "mako",
        "اكو" to "ako",
        "الله" to "allah",
        "ان شاء الله" to "inshallah",
        "إن شاء الله" to "inshallah",
        "الحمد لله" to "al7amdulillah",
        "ما شاء الله" to "mashallah",
        "تصبح على خير" to "tesba7 3ala khair",
        "صباح الخير" to "saba7 elkhair",
        "مساء الخير" to "masa elkhair",
        "انا" to "ana",
        "أنا" to "ana",
        "انت" to "enta",
        "أنت" to "enta",
        "انتي" to "enti",
        "أنتِ" to "enti",
        "هو" to "howa",
        "هي" to "heya",
        "نحن" to "ne7na",
        "بيت" to "bait",
        "كتاب" to "ketab",
        "مدرسة" to "madrasa",
        "جامعة" to "game3a",
        "صديق" to "sadeeq",
        "صديقي" to "sadeeqi",
        "جميل" to "jameel",
        "حلو" to "7elo",
        "كبير" to "kbeer",
        "صغير" to "s8eer",
        "ماذا" to "mada",
        "لماذا" to "leemada",
        "اليوم" to "elyom",
        "غدا" to "8adan",
        "غداً" to "8adan",
        "الآن" to "alan",
        "بعد" to "ba3d",
        "قبل" to "gabel",
        "اريد" to "areed",
        "أريد" to "areed",
        "احب" to "a7eb",
        "أحب" to "a7eb",
        "تعال" to "ta3al",
        "روح" to "roo7",
        "يلا" to "yalla",
        "خلاص" to "khalas",
        "تمام" to "tamam",
        "مبروك" to "mabrook",
        "مشكلة" to "moshkela",
        "فلوس" to "floوس",
        "وقت" to "wa2t",
        "شمس" to "shams",
        "قمر" to "qamar",
        "نور" to "noor",
        "عراق" to "iraq",
        "مصر" to "masr",
        "السعودية" to "alsa3odeya",
        "العراق" to "aliraq"
    )

    private val alphabet = mapOf(
        'ا' to "a",
        'أ' to "a",
        'إ' to "e",
        'آ' to "aa",
        'ب' to "b",
        'ت' to "t",
        'ث' to "th",
        'ج' to "j",
        'ح' to "7",
        'خ' to "5",
        'د' to "d",
        'ذ' to "th",
        'ر' to "r",
        'ز' to "z",
        'س' to "s",
        'ش' to "sh",
        'ص' to "9",
        'ض' to "9'",
        'ط' to "6",
        'ظ' to "6'",
        'ع' to "3",
        'غ' to "8",
        'ف' to "f",
        'ق' to "q",
        'ك' to "k",
        'ل' to "l",
        'م' to "m",
        'ن' to "n",
        'ه' to "h",
        'ة' to "a",
        'و' to "w",
        'ي' to "y",
        'ى' to "a",
        'ء' to "2",
        'ئ' to "2",
        'ؤ' to "2",
        'پ' to "p",
        'گ' to "g",
        'چ' to "ch"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("ton_fox_data", MODE_PRIVATE)

        loadData()
        buildMainLayout()
        showHome()
    }

    private fun loadData() {
        score = prefs.getInt("score", 0)
        level = prefs.getInt("level", 1)
        streak = prefs.getInt("streak", 0)

        val h = prefs.getString("history", "[]")
        val f = prefs.getString("favorites", "[]")
        val l = prefs.getString("lessons", "[]")
        val a = prefs.getString("achievements", "[]")

        try {
            val arr = JSONArray(h)
            for (i in 0 until arr.length()) history.add(arr.getString(i))
        } catch (_: Exception) {}

        try {
            val arr = JSONArray(f)
            for (i in 0 until arr.length()) favorites.add(arr.getString(i))
        } catch (_: Exception) {}

        try {
            val arr = JSONArray(l)
            for (i in 0 until arr.length()) completedLessons.add(arr.getInt(i))
        } catch (_: Exception) {}

        try {
            val arr = JSONArray(a)
            for (i in 0 until arr.length()) achievements.add(arr.getString(i))
        } catch (_: Exception) {}
    }

    private fun saveData() {
        val h = JSONArray()
        history.forEach { h.put(it) }

        val f = JSONArray()
        favorites.forEach { f.put(it) }

        val l = JSONArray()
        completedLessons.forEach { l.put(it) }

        val a = JSONArray()
        achievements.forEach { a.put(it) }

        prefs.edit()
            .putInt("score", score)
            .putInt("level", level)
            .putInt("streak", streak)
            .putString("history", h.toString())
            .putString("favorites", f.toString())
            .putString("lessons", l.toString())
            .putString("achievements", a.toString())
            .apply()
    }

    private fun buildMainLayout() {
        window.statusBarColor = dark
        window.navigationBarColor = dark
        window.decorView.systemUiVisibility = 0

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 12)
            setBackgroundColor(dark)
        }

        val brand = TextView(this).apply {
            text = "🦊 TON FOX"
            textSize = 25f
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "مترجم الفرانكو وأكاديمية التعلم"
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }

        header.addView(brand)
        header.addView(subtitle)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(17, 12, 17, 18)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }

        root.addView(header)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        root.addView(buildNavigation())

        setContentView(root)
    }

    private fun buildNavigation(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(5, 10, 5, 10)
            setBackgroundColor(panel)
        }

        val items = listOf(
            "الرئيسية" to "home",
            "المترجم" to "translate",
            "الأكاديمية" to "academy",
            "المحفوظات" to "saved",
            "المزيد" to "more"
        )

        items.forEach { item ->
            val button = TextView(this).apply {
                text = item.first
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(gold)
                setPadding(5, 10, 5, 10)
                setOnClickListener {
                    when (item.second) {
                        "home" -> showHome()
                        "translate" -> showTranslator()
                        "academy" -> showAcademy()
                        "saved" -> showSaved()
                        "more" -> showMore()
                    }
                }
            }

            nav.addView(
                button,
                LinearLayout.LayoutParams(0, -2, 1f)
            )
        }

        return nav
    }

    private fun clearPage(title: String) {
        currentPage = title
        content.removeAllViews()

        val heading = TextView(this).apply {
            text = title
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(gold)
            setPadding(0, 10, 0, 18)
        }

        content.addView(heading)
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 15, 16, 15)
            background = shape(panel, 18)
        }
    }

    private fun shape(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun label(
        value: String,
        size: Float = 15f,
        color: Int = cream,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 5, 0, 5)
        }
    }

    private fun actionButton(
        title: String,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(dark)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(15, 13, 15, 13)
            background = shape(gold, 13)
            setOnClickListener { onClick() }
        }
    }

    private fun addGap(height: Int = 12) {
        content.addView(
            View(this),
            LinearLayout.LayoutParams(1, height)
        )
    }

    private fun addCard(view: View) {
        content.addView(
            view,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = 12
            }
        )
    }

    private fun showHome() {
        clearPage("الرئيسية")

        val welcome = card()
        welcome.addView(label("مرحباً بك في TON FOX 🦊", 19f, gold, true))
        welcome.addView(
            label(
                "اكتب بالعربية وحوّل كلماتك إلى فرانكو، أو تعلّم الكتابة من خلال الأكاديمية.",
                14f,
                cream
            )
        )
        welcome.addView(
            label("إصدار V2 · محرك ترجمة محلي", 11f, muted)
        )
        addCard(welcome)

        val stats = card()
        stats.addView(label("مستواك التعليمي", 16f, gold, true))
        stats.addView(label("المستوى: $level"))
        stats.addView(label("نقاط الخبرة: $score XP"))
        stats.addView(label("الدروس المكتملة: ${completedLessons.size}/${lessons.size}"))
        stats.addView(label("الترجمات المحفوظة: ${history.size}"))
        addCard(stats)

        val translate = actionButton("📝 ابدأ الترجمة") {
            showTranslator()
        }
        addCard(translate)

        val academy = actionButton("🎓 دخول الأكاديمية") {
            showAcademy()
        }
        addCard(academy)

        val daily = card()
        daily.addView(label("✨ كلمة اليوم", 17f, gold, true))
        val words = listOf(
            "حبيبي" to "7abibi",
            "شلونك" to "shlonak",
            "صباح الخير" to "saba7 elkhair",
            "شكراً" to "shukran",
            "اشتقتلك" to "eshta2tellak"
        )
        val day = SimpleDateFormat("D", Locale.US)
            .format(Date()).toInt()

        val word = words[day % words.size]
        daily.addView(label(word.first, 20f, cream, true))
        daily.addView(label(word.second, 18f, gold))
        addCard(daily)

        checkAchievements()
    }

    private fun showTranslator() {
        clearPage("المترجم الذكي")

        val box = card()

        box.addView(label("النص الذي تريد ترجمته", 15f, gold, true))

        val input = EditText(this).apply {
            textSize = 17f
            setTextColor(cream)
            setHintTextColor(muted)
            hint = "اكتب النص هنا..."
            minLines = 4
            gravity = Gravity.TOP or Gravity.RIGHT
            setPadding(12, 12, 12, 12)
            background = shape(dark, 12)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        sourceText = input

        box.addView(
            input,
            LinearLayout.LayoutParams(-1, -2)
        )

        box.addView(label("اللهجة / نمط الكتابة", 14f, gold, true))

        val dialects = arrayOf(
            "العراقية",
            "المصرية",
            "الخليجية",
            "العربية العامة"
        )

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            dialects
        )

        spinner.setSelection(dialects.indexOf(currentDialect).coerceAtLeast(0))

        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    currentDialect = dialects[position]
                }
            }

        box.addView(spinner)
        addCard(box)

        val outputCard = card()
        outputCard.addView(label("نتيجة الترجمة", 15f, gold, true))

        val output = TextView(this).apply {
            text = "ستظهر الترجمة هنا..."
            textSize = 18f
            setTextColor(cream)
            setPadding(12, 15, 12, 15)
            background = shape(dark, 12)
            minHeight = 75
            gravity = Gravity.CENTER_VERTICAL
            textIsSelectable = true
        }

        resultText = output
        outputCard.addView(output)
        addCard(outputCard)

        val translate = actionButton("تحويل إلى فرانكو") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                val result = toFranco(text)
                output.text = result
                addHistory(text, result)
                hideKeyboard(input)
            } else {
                toast("اكتب نصاً أولاً")
            }
        }
        addCard(translate)

        val reverse = actionButton("تحويل من فرانكو إلى العربية") {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                val result = toArabic(text)
                output.text = result
                addHistory(text, result)
                hideKeyboard(input)
            } else {
                toast("اكتب نص فرانكو أولاً")
            }
        }
        addCard(reverse)

        addCard(actionButton("🔄 تبديل النص والنتيجة") {
            val old = input.text.toString()
            input.setText(output.text.toString())
            output.text = old
        })

        addCard(actionButton("📋 نسخ النتيجة") {
            copyText(output.text.toString())
        })

        addCard(actionButton("⭐ إضافة إلى المفضلة") {
            val value = output.text.toString()
            if (value.isNotBlank() && value != "ستظهر الترجمة هنا...") {
                if (!favorites.contains(value)) {
                    favorites.add(value)
                    saveData()
                    toast("تمت الإضافة إلى المفضلة")
                } else {
                    toast("النص موجود مسبقاً")
                }
            }
        })

        addCard(actionButton("📤 مشاركة النتيجة") {
            shareText(output.text.toString())
        })
    }

    private fun hideKeyboard(view: View) {
        val manager = getSystemService(INPUT_METHOD_SERVICE)
            as InputMethodManager
        manager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun copyText(text: String) {
        if (text.isBlank()) {
            toast("لا يوجد نص لنسخه")
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE)
            as android.content.ClipboardManager

        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("TON FOX", text)
        )

        toast("تم النسخ بنجاح")
    }

    private fun shareText(text: String) {
        if (text.isBlank()) {
            toast("لا يوجد نص للمشاركة")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "$text\n\nTON FOX 🦊")
        }

        startActivity(Intent.createChooser(intent, "مشاركة النص"))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
        private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun translateToFranco(input: String): String {
        if (input.isBlank()) return ""
        val exact = dictionary[input.trim()]
        if (exact != null) return exact

        val map = mapOf(
            'ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa",
            'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j",
            'ح' to "7", 'خ' to "kh", 'د' to "d", 'ذ' to "th",
            'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh",
            'ص' to "9", 'ض' to "9'", 'ط' to "6", 'ظ' to "6'",
            'ع' to "3", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
            'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n",
            'ه' to "h", 'ة' to "a", 'و' to "w", 'ي' to "y",
            'ى' to "a", 'ء' to "2", 'ؤ' to "2", 'ئ' to "2",
            'پ' to "p", 'چ' to "ch", 'گ' to "g"
        )

        val output = StringBuilder()
        input.forEach { character ->
            val converted = map[character]
            if (converted != null) {
                output.append(converted)
            } else {
                output.append(character)
            }
        }
        return output.toString()
    }

    private fun translateToArabic(input: String): String {
        if (input.isBlank()) return ""

        val reverse = dictionary.entries.associate { it.value.lowercase() to it.key }
        val exact = reverse[input.trim().lowercase()]
        if (exact != null) return exact

        val words = input.split(Regex("(\\s+)"))
        return words.joinToString("") { word ->
            if (word.isBlank()) {
                word
            } else {
                reverse[word.lowercase()] ?: decodeFrancoWord(word)
            }
        }
    }

    private fun decodeFrancoWord(original: String): String {
        val word = original.lowercase()
        val replacements = listOf(
            "sh" to "ش", "kh" to "خ", "gh" to "غ",
            "th" to "ث", "ch" to "چ", "aa" to "ا",
            "ee" to "ي", "oo" to "و", "ou" to "و",
            "7'" to "ح", "6'" to "ظ", "9'" to "ض",
            "2" to "ء", "3" to "ع", "5" to "خ",
            "6" to "ط", "7" to "ح", "8" to "ق",
            "9" to "ص", "a" to "ا", "b" to "ب",
            "t" to "ت", "j" to "ج", "d" to "د",
            "r" to "ر", "z" to "ز", "s" to "س",
            "f" to "ف", "q" to "ق", "k" to "ك",
            "l" to "ل", "m" to "م", "n" to "ن",
            "h" to "ه", "w" to "و", "y" to "ي",
            "p" to "پ", "g" to "گ", "e" to "ي",
            "i" to "ي", "o" to "و", "u" to "و"
        )

        val result = StringBuilder()
        var index = 0
        while (index < word.length) {
            val remaining = word.substring(index)
            val match = replacements.firstOrNull { remaining.startsWith(it.first) }
            if (match != null) {
                result.append(match.second)
                index += match.first.length
            } else {
                result.append(word[index])
                index++
            }
        }
        return result.toString()
    }

    private fun runTranslation(toFranco: Boolean) {
        val input = sourceText.text.toString().trim()
        if (input.isEmpty()) {
            toast("اكتب النص أولاً")
            sourceText.requestFocus()
            return
        }

        val output = if (toFranco) {
            translateToFranco(input)
        } else {
            translateToArabic(input)
        }

        resultText.text = output
        saveHistory(input, output, if (toFranco) "عربي ← فرانكو" else "فرانكو ← عربي")
        hideKeyboard()
    }

    private fun saveHistory(input: String, output: String, direction: String) {
        history.add(
            0,
            JSONObject().apply {
                put("input", input)
                put("output", output)
                put("direction", direction)
                put("time", System.currentTimeMillis())
            }
        )
        while (history.size > 250) history.removeAt(history.lastIndex)
        saveData()
    }

    private fun addFavorite(input: String, output: String) {
        val exists = favorites.any {
            it.optString("input") == input && it.optString("output") == output
        }
        if (exists) {
            toast("هذه العبارة موجودة في المفضلة")
            return
        }

        favorites.add(
            0,
            JSONObject().apply {
                put("input", input)
                put("output", output)
                put("time", System.currentTimeMillis())
            }
        )
        saveData()
        toast("تمت الإضافة إلى المفضلة")
    }

    private fun showAcademy() {
        clearPage("أكاديمية TON FOX")
        addCard {
            label("تعلّم الفرانكو خطوة بخطوة", 21, gold, true)
            label(
                "دروس قصيرة واختبارات تفاعلية تساعدك على فهم الحروف والأرقام والكلمات.",
                14,
                muted
            )
            addGap(12)
            label("المستوى الحالي: $level", 16, cream, true)
            label("النقاط المكتسبة: $score XP", 14, gold)
            label("الدروس المكتملة: ${completedLessons.size} من ${lessons.size}", 14, muted)
        }

        lessons.forEachIndexed { index, lesson ->
            val completed = completedLessons.contains(index)
            addCard {
                rowLayout {
                    val info = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                    }
                    info.addView(makeLabel("الدرس ${index + 1}: ${lesson.title}", 16, cream, true))
                    info.addView(makeLabel(lesson.description, 12, muted))
                    addView(info)
                    addView(makeLabel(if (completed) "✓ مكتمل" else "ابدأ", 13, gold, true))
                }
                actionButton(
                    if (completed) "مراجعة الدرس" else "فتح الدرس",
                    true
                ) {
                    showLesson(index)
                }
            }
        }

        actionButton("اختبار شامل", false) { startQuiz(-1) }
    }

    private fun showLesson(index: Int) {
        if (index !in lessons.indices) return
        val lesson = lessons[index]
        clearPage(lesson.title)

        addCard {
            label("الدرس ${index + 1} من ${lessons.size}", 13, gold, true)
            label(lesson.title, 23, cream, true)
            label(lesson.description, 14, muted)
            addGap(10)
            lesson.content.forEachIndexed { i, line ->
                label("${i + 1}. $line", 15, cream)
                addGap(7)
            }
        }

        addCard {
            label("تدرّب بنفسك", 18, gold, true)
            label("حوّل الكلمة التالية إلى فرانكو:", 14, cream)
            label(lesson.exampleArabic, 23, cream, true)
            val answer = EditText(this).apply {
                hint = "اكتب الإجابة هنا"
                setTextColor(cream)
                setHintTextColor(muted)
                textSize = 16f
                setPadding(14, 12, 14, 12)
                background = shape(panel, 12, gold)
            }
            addView(answer)
            actionButton("تحقق من الإجابة", true) {
                val expected = translateToFranco(lesson.exampleArabic)
                    .replace(" ", "")
                    .lowercase()
                val entered = answer.text.toString()
                    .replace(" ", "")
                    .lowercase()

                if (entered == expected || entered == lesson.acceptedAnswer.lowercase()) {
                    completedLessons.add(index)
                    completedLessons.distinct()
                    score += 20
                    updateLevel()
                    saveData()
                    toast("إجابة صحيحة! +20 XP")
                    showLesson(index)
                } else {
                    toast("حاول مرة أخرى، الإجابة التقريبية: $expected")
                }
            }
        }

        actionButton("اختبار هذا الدرس", false) { startQuiz(index) }
        actionButton("العودة إلى الأكاديمية", false) { showAcademy() }
    }

    private fun updateLevel() {
        level = (score / 100) + 1
    }

    private fun startQuiz(lessonIndex: Int) {
        val questions = if (lessonIndex >= 0) {
            listOf(lessons[lessonIndex])
        } else {
            lessons.shuffled().take(5)
        }

        var questionIndex = 0
        var correctAnswers = 0

        fun displayQuestion() {
            if (questionIndex >= questions.size) {
                val earned = correctAnswers * 10
                score += earned
                updateLevel()
                saveData()
                clearPage("نتيجة الاختبار")
                addCard {
                    label("انتهى الاختبار!", 24, gold, true)
                    label("إجابات صحيحة: $correctAnswers من ${questions.size}", 18, cream)
                    label("النقاط المكتسبة: $earned XP", 16, gold)
                }
                actionButton("العودة إلى الأكاديمية", true) { showAcademy() }
                return
            }

            val lesson = questions[questionIndex]
            clearPage("اختبار ${questionIndex + 1}/${questions.size}")
            addCard {
                label("حوّل هذه الكلمة إلى فرانكو", 17, gold, true)
                label(lesson.exampleArabic, 25, cream, true)
                val expected = translateToFranco(lesson.exampleArabic)
                val choices = (listOf(expected) +
                    lessons.filter { it != lesson }
                        .map { translateToFranco(it.exampleArabic) }
                        .shuffled().take(3)).shuffled()

                choices.forEach { choice ->
                    actionButton(choice, false) {
                        if (choice == expected) {
                            correctAnswers++
                            toast("إجابة صحيحة")
                        } else {
                            toast("ليست الإجابة الصحيحة")
                        }
                        questionIndex++
                        displayQuestion()
                    }
                }
            }
        }

        displayQuestion()
    }

    private fun rowLayout(content: LinearLayout.() -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            content()
        }
    }

    private fun makeLabel(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 5, 4, 5)
        }
    }
        private fun makeLabel(
        text: String,
        size: Int,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(4, 5, 4, 5)
        }
    }

    private fun showDictionary() {
        clearPage("القاموس")
        addCard {
            label("قاموس فرانكو وعربي", 21, gold, true)
            label("ابحث عن كلمة عربية أو فرانكو لتحصل على تحويل تقريبي.", 13, muted)
            val search = EditText(this@MainActivity).apply {
                hint = "اكتب كلمة للبحث..."
                setTextColor(cream)
                setHintTextColor(muted)
                textSize = 16f
                setPadding(14, 12, 14, 12)
                background = shape(panel, 12, gold)
            }
            addView(search)

            val results = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(results)

            fun render(query: String) {
                results.removeAllViews()
                if (query.isBlank()) {
                    results.addView(makeLabel("ابدأ بكتابة كلمة للبحث.", 13, muted))
                    return
                }

                val matches = dictionary.entries.filter {
                    it.key.contains(query, true) ||
                        it.value.contains(query, true)
                }.take(30)

                if (matches.isEmpty()) {
                    val converted = if (query.any { it in 'ا'..'ي' }) {
                        translateToFranco(query)
                    } else {
                        translateToArabic(query)
                    }
                    results.addView(
                        makeLabel("$query  ←  $converted", 15, cream)
                    )
                } else {
                    matches.forEach { entry ->
                        results.addView(
                            makeLabel("${entry.key}  —  ${entry.value}", 15, cream)
                        )
                    }
                }
            }

            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    render(s?.toString().orEmpty())
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
            render("")
        }

        actionButton("بطاقات حفظ الكلمات", true) { showFlashcards() }
    }

    private fun showFlashcards() {
        val entries = dictionary.entries.toList()
        if (entries.isEmpty()) {
            toast("لا توجد كلمات في القاموس")
            return
        }

        var index = 0
        var showMeaning = false

        fun renderCard() {
            clearPage("بطاقات الحفظ")
            val entry = entries[index % entries.size]

            addCard {
                label("بطاقة ${index + 1} من ${entries.size}", 13, muted)
                label(
                    if (showMeaning) entry.value else entry.key,
                    30,
                    gold,
                    true
                )
                label(
                    if (showMeaning) "التحويل بالفرانكو" else "اضغط لإظهار التحويل",
                    13,
                    muted
                )
                actionButton(
                    if (showMeaning) "إخفاء الإجابة" else "إظهار الإجابة",
                    true
                ) {
                    showMeaning = !showMeaning
                    renderCard()
                }
            }

            actionButton("البطاقة التالية", false) {
                index = (index + 1) % entries.size
                showMeaning = false
                renderCard()
            }
            actionButton("العودة للقاموس", false) { showDictionary() }
        }

        renderCard()
    }

    private fun showSaved() {
        clearPage("المحفوظات")

        addCard {
            label("المفضلة", 20, gold, true)
            label("${favorites.size} عبارة محفوظة", 13, muted)
        }

        if (favorites.isEmpty()) {
            addCard {
                label("لا توجد عبارات محفوظة حتى الآن.", 14, muted)
            }
        }

        favorites.toList().forEachIndexed { index, item ->
            addCard {
                label(item.optString("input"), 16, cream, true)
                label(item.optString("output"), 15, gold)
                rowLayout {
                    addView(
                        actionButtonView("نسخ") {
                            copyText(item.optString("output"))
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    addView(
                        actionButtonView("استخدام") {
                            sourceText.setText(item.optString("input"))
                            showTranslator()
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    addView(
                        actionButtonView("حذف") {
                            favorites.removeAt(index)
                            saveData()
                            showSaved()
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                }
            }
        }

        addGap(10)
        label("سجل الترجمات", 19, gold, true)

        if (history.isEmpty()) {
            label("سجل الترجمات فارغ.", 13, muted)
        }

        history.take(60).forEach { item ->
            addCard {
                label(item.optString("direction"), 12, gold, true)
                label(item.optString("input"), 14, cream)
                label(item.optString("output"), 14, muted)
                actionButton("إعادة استخدام النص", false) {
                    sourceText.setText(item.optString("input"))
                    showTranslator()
                }
            }
        }

        actionButton("مسح سجل الترجمات", false) {
            history.clear()
            saveData()
            showSaved()
            toast("تم مسح السجل")
        }
    }

    private fun actionButtonView(
        title: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = title
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(gold)
            setPadding(8, 12, 8, 12)
            background = shape(panel, 10, gold)
            setOnClickListener { action() }
        }
    }

    private fun showHistorySearch() {
        clearPage("البحث في السجل")
        val search = EditText(this).apply {
            hint = "ابحث في الترجمات السابقة"
            setTextColor(cream)
            setHintTextColor(muted)
            background = shape(panel, 12, gold)
            setPadding(14, 12, 14, 12)
        }
        content.addView(search)

        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(results)

        fun render(query: String) {
            results.removeAllViews()
            history.filter {
                it.optString("input").contains(query, true) ||
                    it.optString("output").contains(query, true)
            }.take(50).forEach { item ->
                addResultCard(
                    results,
                    item.optString("input"),
                    item.optString("output")
                )
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                render(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        render("")
    }

    private fun addResultCard(
        parent: LinearLayout,
        input: String,
        output: String
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            background = shape(panel, 12, gold)
        }
        container.addView(makeLabel(input, 14, cream, true))
        container.addView(makeLabel(output, 14, gold))
        container.setOnClickListener {
            sourceText.setText(input)
            showTranslator()
        }
        val params = LinearLayout.LayoutParams(-1, -2)
        params.bottomMargin = 9
        parent.addView(container, params)
    }

    private fun showPhraseGenerator() {
        clearPage("مولّد العبارات")

        val categories = listOf(
            "تحيات" to listOf(
                "شلونك؟", "صباح الخير", "مساء الخير",
                "أهلاً وسهلاً", "نورت المكان"
            ),
            "يوميّات" to listOf(
                "وينك؟", "شنو الأخبار؟", "أنا بالطريق",
                "انتظرني قليلاً", "أشوفك بعدين"
            ),
            "مشاعر" to listOf(
                "اشتقت لك", "أنت غالي", "أحب وجودك",
                "الله يسعدك", "أتمنى لك الخير"
            ),
            "دراسة" to listOf(
                "عندي امتحان", "أحتاج مساعدة",
                "فهمت الدرس", "خل نراجع سوا"
            )
        )

        categories.forEach { category ->
            addCard {
                label(category.first, 18, gold, true)
                category.second.forEach { phrase ->
                    rowLayout {
                        val phraseLabel = makeLabel(phrase, 14, cream)
                        addView(
                            phraseLabel,
                            LinearLayout.LayoutParams(0, -2, 1f)
                        )
                        addView(
                            actionButtonView("فرانكو") {
                                val converted = translateToFranco(phrase)
                                resultText.text = converted
                                copyText(converted)
                            },
                            LinearLayout.LayoutParams(100, -2)
                        )
                    }
                }
            }
        }
    }

    private fun showNameGenerator() {
        clearPage("مولّد الأسماء")

        val names = listOf(
            "نور", "ليث", "زين", "آدم", "علي", "حسن",
            "حسين", "مريم", "زهراء", "فاطمة", "سارة",
            "ياسمين", "رُبى", "محمد", "يوسف", "كرار",
            "مصطفى", "حيدر", "رؤى", "ملك"
        )

        val output = TextView(this).apply {
            text = "اضغط على الزر لتوليد اسم"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(gold)
            setPadding(12, 25, 12, 25)
        }

        addCard {
            label("اقتراح اسم عشوائي", 18, gold, true)
            addView(output)
            actionButton("توليد اسم", true) {
                val name = names.random()
                output.text = "$name\n${translateToFranco(name)}"
            }
            actionButton("نسخ الاسم", false) {
                val value = output.text.toString().substringBefore("\n")
                if (value.isNotBlank() && !value.startsWith("اضغط")) {
                    copyText(value)
                }
            }
        }
    }
        private fun showStatistics() {
        clearPage("الإحصائيات والإنجازات")

        val totalTranslations = history.size
        val totalFavorites = favorites.size
        val totalLessons = completedLessons.distinct().size

        addCard {
            label("إحصائيات استخدام TON FOX", 20, gold, true)
            label("إجمالي الترجمات: $totalTranslations", 16, cream)
            label("العبارات المفضلة: $totalFavorites", 16, cream)
            label("الدروس المكتملة: $totalLessons / ${lessons.size}", 16, cream)
            label("النقاط: $score XP", 16, gold, true)
            label("المستوى: $level", 16, cream)
            label("سلسلة التعلم: $streak يوم", 16, cream)
        }

        addCard {
            label("إنجازاتك", 19, gold, true)

            val achievementsList = listOf(
                Triple("أول ترجمة", totalTranslations >= 1, "أنجز أول ترجمة"),
                Triple("مترجم نشيط", totalTranslations >= 10, "أنجز 10 ترجمات"),
                Triple("جامع الكلمات", totalFavorites >= 5, "احفظ 5 عبارات"),
                Triple("طالب مجتهد", totalLessons >= 3, "أكمل 3 دروس"),
                Triple("خبير فرانكو", totalLessons >= 7, "أكمل جميع الدروس"),
                Triple("صاحب النقاط", score >= 100, "اجمع 100 نقطة")
            )

            achievementsList.forEach { item ->
                val status = if (item.second) "✓" else "○"
                label("$status  ${item.first}", 15, if (item.second) gold else muted)
                label(item.third, 12, muted)
            }
        }
    }

    private fun showSettings() {
        clearPage("الإعدادات")

        addCard {
            label("إعدادات التطبيق", 20, gold, true)
            label("TON FOX يعمل محلياً ويحفظ بياناتك على الجهاز.", 13, muted)
        }

        addCard {
            label("إدارة البيانات", 18, gold, true)
            actionButton("تصدير نسخة احتياطية", true) {
                exportBackup()
            }
            actionButton("استيراد نسخة احتياطية", false) {
                importBackup()
            }
            actionButton("حذف جميع البيانات", false) {
                confirmClearData()
            }
        }

        addCard {
            label("معلومات التطبيق", 18, gold, true)
            label("TON FOX V2", 16, cream, true)
            label("مترجم عربي وفرانكو مع أكاديمية تعليمية.", 13, muted)
            label("المطور: N5CR4", 14, gold)
            label("Telegram: @N5CR4", 14, cream)
            actionButton("مشاركة التطبيق", false) {
                shareText("جرّب TON FOX لتعلّم وتحويل العربية والفرانكو.")
            }
            actionButton("تواصل مع المطور", false) {
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://t.me/N5CR4")
                )
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    toast("تعذر فتح رابط تيليجرام")
                }
            }
        }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("حذف البيانات")
            .setMessage("سيتم حذف سجل الترجمات والمفضلة والتقدم التعليمي من هذا الجهاز. هل تريد المتابعة؟")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حذف") { _, _ ->
                history.clear()
                favorites.clear()
                completedLessons.clear()
                score = 0
                level = 1
                streak = 0
                saveData()
                showSettings()
                toast("تم حذف البيانات المحلية")
            }
            .show()
    }

    private fun exportBackup() {
        val backup = JSONObject().apply {
            put("app", "TON FOX")
            put("version", 2)
            put("created", System.currentTimeMillis())
            put("history", JSONArray().apply {
                history.forEach { put(it) }
            })
            put("favorites", JSONArray().apply {
                favorites.forEach { put(it) }
            })
            put("completedLessons", JSONArray().apply {
                completedLessons.forEach { put(it) }
            })
            put("score", score)
            put("level", level)
            put("streak", streak)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "TON FOX Backup")
            putExtra(Intent.EXTRA_TEXT, backup.toString(2))
        }

        try {
            startActivity(Intent.createChooser(shareIntent, "حفظ أو مشاركة النسخة الاحتياطية"))
        } catch (_: Exception) {
            toast("تعذر فتح نافذة المشاركة")
        }
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }

        try {
            startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
        } catch (_: Exception) {
            toast("تعذر فتح مدير الملفات")
        }
    }

    private fun onBackupImported(uri: android.net.Uri) {
        try {
            val jsonText = contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalStateException("تعذر قراءة الملف")

            val backup = JSONObject(jsonText)
            if (backup.optString("app") != "TON FOX") {
                toast("هذا الملف ليس نسخة احتياطية من TON FOX")
                return
            }

            history.clear()
            favorites.clear()
            completedLessons.clear()

            val savedHistory = backup.optJSONArray("history") ?: JSONArray()
            for (i in 0 until savedHistory.length()) {
                history.add(savedHistory.getJSONObject(i))
            }

            val savedFavorites = backup.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until savedFavorites.length()) {
                favorites.add(savedFavorites.getJSONObject(i))
            }

            val savedLessons = backup.optJSONArray("completedLessons") ?: JSONArray()
            for (i in 0 until savedLessons.length()) {
                completedLessons.add(savedLessons.optInt(i))
            }

            score = backup.optInt("score", 0).coerceAtLeast(0)
            level = backup.optInt("level", 1).coerceAtLeast(1)
            streak = backup.optInt("streak", 0).coerceAtLeast(0)

            saveData()
            showHome()
            toast("تم استيراد النسخة الاحتياطية بنجاح")
        } catch (error: Exception) {
            toast("تعذر استيراد النسخة: ${error.message ?: "ملف غير صالح"}")
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        }

        try {
            startActivityForResult(intent, REQUEST_VOICE_INPUT)
        } catch (_: Exception) {
            toast("خدمة التعرف الصوتي غير متاحة على هذا الجهاز")
        }
    }

    @Deprecated("Deprecated in Android API, retained for broad device compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data == null) return

        when (requestCode) {
            REQUEST_VOICE_INPUT -> {
                val results = data.getStringArrayListExtra(
                    android.speech.RecognizerIntent.EXTRA_RESULTS
                )
                val spokenText = results?.firstOrNull()
                if (!spokenText.isNullOrBlank()) {
                    sourceText.setText(spokenText)
                    showTranslator()
                    toast("تم إدخال النص الصوتي")
                }
            }

            REQUEST_IMPORT_BACKUP -> {
                data.data?.let { onBackupImported(it) }
            }
        }
    }

    private fun showMore() {
        clearPage("المزيد")

        val options = listOf(
            "القاموس" to { showDictionary() },
            "بطاقات حفظ الكلمات" to { showFlashcards() },
            "البحث في سجل الترجمات" to { showHistorySearch() },
            "مولّد العبارات" to { showPhraseGenerator() },
            "مولّد الأسماء" to { showNameGenerator() },
            "الإحصائيات والإنجازات" to { showStatistics() },
            "الإعدادات والنسخ الاحتياطي" to { showSettings() }
        )

        options.forEach { option ->
            actionButton(option.first, true, option.second)
        }

        addCard {
            label("ملاحظة", 16, gold, true)
            label(
                "التحويل إلى فرانكو يعتمد على قواعد كتابة شائعة، وقد تختلف طريقة كتابة بعض الكلمات بين الأشخاص واللهجات.",
                13,
                muted
            )
        }
    }

    companion object {
        private const val REQUEST_VOICE_INPUT = 9001
        private const val REQUEST_IMPORT_BACKUP = 9002
    }
}
/*
 * TON FOX V2 — مراجعة نهائية وتعليمات دمج
 *
 * انتهى الهيكل الأساسي في الجزء الرابع.
 * هذا الجزء يحتوي على تعديلات ضرورية قبل البناء.
 *
 * 1) احذف تعريف makeLabel المكرر من الجزء الثالث:
 *    احتفظ بتعريف واحد فقط داخل MainActivity.
 *
 * 2) في showLesson، استبدل:
 *    completedLessons.add(index)
 *    completedLessons.distinct()
 *
 *    بالتالي:
 *    if (!completedLessons.contains(index)) {
 *        completedLessons.add(index)
 *    }
 *
 * 3) في showSaved، لا تستخدم index من قائمة favorites
 *    بعد حذف عنصر من القائمة أثناء إعادة بناء الواجهة.
 *    استخدم نسخة ثابتة من العنصر واحذف العنصر المطابق.
 *
 * 4) تأكد من أن تعريفات Lesson و lessons و dictionary
 *    وحقول واجهة التطبيق الموجودة في الجزء الأول محفوظة
 *    داخل MainActivity.
 *
 * 5) لا تضف هذا التعليق البرمجي كملف مستقل.
 */