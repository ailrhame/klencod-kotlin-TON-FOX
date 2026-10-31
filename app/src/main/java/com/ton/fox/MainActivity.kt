package com.ton.fox

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
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

    private var currentDialect = "العراقية"
    private var sourceText: EditText? = null
    private var resultText: TextView? = null

    private var score = 0
    private var level = 1
    private var streak = 0

    private val history = mutableListOf<JSONObject>()
    private val favorites = mutableListOf<JSONObject>()
    private val completedLessons = mutableSetOf<Int>()
    private val achievements = mutableSetOf<String>()

    private data class Lesson(
        val title: String,
        val description: String,
        val content: List<String>,
        val exampleArabic: String,
        val acceptedAnswer: String
    )

    private val lessons = listOf(
        Lesson("مقدمة في الفرانكو", "تعرف على طريقة كتابة العربية بالحروف اللاتينية والأرقام.",
            listOf("الفرانكو أسلوب غير رسمي لكتابة العربية.", "تختلف كتابة بعض الكلمات من شخص لآخر.", "السياق يساعد على فهم الكلمة."),
            "مرحبا", "mar7aba"),
        Lesson("الحروف الأساسية", "تعلم أشهر المقابلات بين الحروف.",
            listOf("ب = b", "ت = t", "ش = sh", "خ = kh", "ح = 7"),
            "كتاب", "ketab"),
        Lesson("الأرقام", "تستخدم الأرقام لتمثيل أصوات عربية معينة.",
            listOf("2 = ء", "3 = ع", "5 = خ", "6 = ط", "7 = ح", "8 = غ أو ق حسب النمط", "9 = ص"),
            "حبيبي", "7abibi"),
        Lesson("تكوين الكلمات", "اجمع الحروف لتكوين كلمات واضحة.",
            listOf("اكتب الكلمة كما تنطقها.", "حافظ على نمط ثابت.", "لا توجد كتابة موحدة لكل الكلمات."),
            "شكرا", "shukran"),
        Lesson("المحادثات", "تدرب على العبارات اليومية.",
            listOf("استخدم المسافات بين الكلمات.", "يمكن اختصار بعض الكلمات في الدردشة.", "اقرأ الجملة كاملة لفهم المعنى."),
            "كيف حالك", "keef 7alak"),
        Lesson("اللهجة العراقية", "أمثلة من المفردات العراقية.",
            listOf("شلونك = shlonak", "شكو ماكو = shako mako", "هسه = hassa", "زين = zain"),
            "شلونك", "shlonak"),
        Lesson("الاحتراف", "حسّن وضوح كتابتك وتناسقها.",
            listOf("اختر طريقة ثابتة للأصوات.", "راجع الكلمات الملتبسة.", "لا تعتمد على التحويل الحرفي وحده."),
            "اشتقتلك", "eshta2tellak")
    )

    private val dictionary = linkedMapOf(
        "مرحبا" to "mar7aba", "السلام عليكم" to "alsalam 3alaykom",
        "وعليكم السلام" to "w 3alaykom alsalam", "كيف حالك" to "keef 7alak",
        "شلونك" to "shlonak", "شلونج" to "shlonich", "شلونكم" to "shlonkom",
        "شكو ماكو" to "shako mako", "شنو" to "shنو", "ليش" to "leesh",
        "وين" to "wain", "هسه" to "hassa", "باچر" to "bachir",
        "زين" to "zain", "حبيبي" to "7abibi", "حبيبتي" to "7abibti",
        "قلبي" to "galbi", "عمري" to "3omri", "احبك" to "a7ebak",
        "أحبك" to "a7ebak", "اشتقتلك" to "eshta2tellak", "شكرا" to "shukran",
        "شكراً" to "shukran", "عفوا" to "3afwan", "نعم" to "na3am",
        "لا" to "la", "اي" to "ay", "مو" to "mo", "ماكو" to "mako",
        "اكو" to "ako", "الله" to "allah", "ان شاء الله" to "inshallah",
        "الحمد لله" to "al7amdulillah", "ما شاء الله" to "mashallah",
        "صباح الخير" to "saba7 elkhair", "مساء الخير" to "masa elkhair",
        "انا" to "ana", "أنا" to "ana", "انت" to "enta", "أنت" to "enta",
        "بيت" to "bait", "كتاب" to "ketab", "مدرسة" to "madrasa",
        "جامعة" to "game3a", "صديق" to "sadeeq", "جميل" to "jameel",
        "حلو" to "7elo", "كبير" to "kbeer", "صغير" to "s8eer",
        "اليوم" to "elyom", "غدا" to "8adan", "الآن" to "alan",
        "اريد" to "areed", "أريد" to "areed", "تعال" to "ta3al",
        "روح" to "roo7", "يلا" to "yalla", "خلاص" to "khalas",
        "تمام" to "tamam", "مبروك" to "mabrook", "مشكلة" to "moshkela",
        "فلوس" to "floوس", "وقت" to "wa2t", "شمس" to "shams",
        "قمر" to "qamar", "نور" to "noor", "عراق" to "iraq",
        "مصر" to "masr", "السعودية" to "alsa3odeya"
    )

    private val arabicMap = mapOf(
        'ا' to "a", 'أ' to "a", 'إ' to "e", 'آ' to "aa",
        'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j",
        'ح' to "7", 'خ' to "kh", 'د' to "d", 'ذ' to "th",
        'ر' to "r", 'ز' to "z", 'س' to "s", 'ش' to "sh",
        'ص' to "9", 'ض' to "9'", 'ط' to "6", 'ظ' to "6'",
        'ع' to "3", 'غ' to "gh", 'ف' to "f", 'ق' to "q",
        'ك' to "k", 'ل' to "l", 'م' to "m", 'ن' to "n",
        'ه' to "h", 'ة' to "a", 'و' to "w", 'ي' to "y",
        'ى' to "a", 'ء' to "2", 'ئ' to "2", 'ؤ' to "2",
        'پ' to "p", 'چ' to "ch", 'گ' to "g"
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
        readJsonObjects("history").forEach { history.add(it) }
        readJsonObjects("favorites").forEach { favorites.add(it) }
        try {
            val a = JSONArray(prefs.getString("lessons", "[]") ?: "[]")
            for (i in 0 until a.length()) completedLessons.add(a.optInt(i))
        } catch (_: Exception) {}
        try {
            val a = JSONArray(prefs.getString("achievements", "[]") ?: "[]")
            for (i in 0 until a.length()) achievements.add(a.optString(i))
        } catch (_: Exception) {}
    }

    private fun readJsonObjects(key: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        try {
            val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val value = array.opt(i)
                if (value is JSONObject) result.add(value)
                else if (value is String) {
                    try { result.add(JSONObject(value)) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return result
    }

    private fun saveData() {
        fun objectArray(items: List<JSONObject>) = JSONArray().apply { items.forEach { put(it) } }
        val lessonArray = JSONArray().apply { completedLessons.forEach { put(it) } }
        val achievementArray = JSONArray().apply { achievements.forEach { put(it) } }
        prefs.edit()
            .putInt("score", score)
            .putInt("level", level)
            .putInt("streak", streak)
            .putString("history", objectArray(history).toString())
            .putString("favorites", objectArray(favorites).toString())
            .putString("lessons", lessonArray.toString())
            .putString("achievements", achievementArray.toString())
            .apply()
    }

    private fun buildMainLayout() {
        window.statusBarColor = dark
        window.navigationBarColor = dark
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(dark)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 14, 16, 12)
        }
        header.addView(label("🦊 TON FOX", 25f, gold, true))
        header.addView(label("مترجم الفرانكو وأكاديمية التعلم", 12f, muted))
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 18)
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildNavigation())
        setContentView(root)
    }

    private fun buildNavigation(): LinearLayout {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(4, 8, 4, 8)
            setBackgroundColor(panel)
        }
        listOf("الرئيسية", "المترجم", "الأكاديمية", "المحفوظات", "المزيد").forEach { title ->
            val v = TextView(this).apply {
                text = title
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(gold)
                setPadding(4, 10, 4, 10)
                setOnClickListener {
                    when (title) {
                        "الرئيسية" -> showHome()
                        "المترجم" -> showTranslator()
                        "الأكاديمية" -> showAcademy()
                        "المحفوظات" -> showSaved()
                        else -> showMore()
                    }
                }
            }
            nav.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return nav
    }

    private fun clearPage(title: String) {
        content.removeAllViews()
        content.addView(label(title, 23f, gold, true))
        addGap(8)
    }

    private fun shape(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != null) setStroke(1, stroke)
        }

    private fun label(value: String, size: Float = 15f, color: Int = cream, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(2, 5, 2, 5)
        }

    private fun makeLabel(value: String, size: Int, color: Int, bold: Boolean = false) =
        label(value, size.toFloat(), color, bold)

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(15, 13, 15, 13)
        background = shape(panel, 16)
    }

    private fun addCard(block: LinearLayout.() -> Unit) {
        val c = card()
        c.block()
        content.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 11 })
    }

    private fun addGap(height: Int = 10) {
        content.addView(View(this), LinearLayout.LayoutParams(1, height))
    }

    private fun actionButton(title: String, onClick: () -> Unit): TextView =
        actionButton(title, false, onClick)

    private fun actionButton(title: String, primary: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = title
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) dark else gold)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(13, 12, 13, 12)
            background = shape(if (primary) gold else panel, 12, if (primary) null else gold)
            setOnClickListener { onClick() }
        }

    private fun addAction(title: String, primary: Boolean = false, action: () -> Unit) {
        content.addView(actionButton(title, primary, action),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
    }

    private fun showHome() {
        clearPage("الرئيسية")
        addCard {
            addView(label("مرحباً بك في TON FOX 🦊", 19f, gold, true))
            addView(label("حوّل العربية إلى فرانكو وتعلّم الكتابة خطوة بخطوة.", 14f))
            addView(label("إصدار V2 · معالجة محلية", 11f, muted))
        }
        addCard {
            addView(label("مستواك التعليمي", 17f, gold, true))
            addView(label("المستوى: $level"))
            addView(label("نقاط الخبرة: $score XP"))
            addView(label("الدروس المكتملة: ${completedLessons.size}/${lessons.size}"))
            addView(label("الترجمات: ${history.size}"))
        }
        addAction("📝 ابدأ الترجمة", true) { showTranslator() }
        addAction("🎓 دخول الأكاديمية") { showAcademy() }
        val words = listOf("حبيبي" to "7abibi", "شلونك" to "shlonak", "صباح الخير" to "saba7 elkhair", "شكراً" to "shukran", "اشتقتلك" to "eshta2tellak")
        val day = SimpleDateFormat("D", Locale.US).format(Date()).toInt()
        addCard {
            addView(label("✨ كلمة اليوم", 17f, gold, true))
            addView(label(words[day % words.size].first, 22f, cream, true))
            addView(label(words[day % words.size].second, 18f, gold))
        }
    }

    private fun showTranslator() {
        clearPage("المترجم الذكي")
        val input = EditText(this).apply {
            hint = "اكتب النص هنا..."
            textSize = 17f
            setTextColor(cream)
            setHintTextColor(muted)
            minLines = 4
            gravity = Gravity.TOP or Gravity.RIGHT
            setPadding(12, 12, 12, 12)
            background = shape(dark, 12, gold)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        sourceText = input
        addCard {
            addView(label("النص المراد تحويله", 15f, gold, true))
            addView(input, LinearLayout.LayoutParams(-1, -2))
            addView(label("اللهجة / النمط", 14f, gold, true))
            val spinner = Spinner(this@MainActivity)
            val dialects = arrayOf("العراقية", "المصرية", "الخليجية", "العربية العامة")
            spinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, dialects)
            spinner.setSelection(dialects.indexOf(currentDialect).coerceAtLeast(0))
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentDialect = dialects[position]
                }
            }
            addView(spinner)
        }
        val output = TextView(this).apply {
            text = "ستظهر الترجمة هنا..."
            textSize = 18f
            setTextColor(cream)
            setPadding(12, 15, 12, 15)
            background = shape(dark, 12)
            minHeight = 75
            textIsSelectable = true
        }
        resultText = output
        addCard {
            addView(label("النتيجة", 15f, gold, true))
            addView(output)
        }
        addAction("تحويل إلى فرانكو", true) {
            val value = input.text.toString().trim()
            if (value.isBlank()) toast("اكتب نصاً أولاً") else {
                val converted = translateToFranco(value)
                output.text = converted
                saveHistory(value, converted, "عربي ← فرانكو")
                hideKeyboard(input)
            }
        }
        addAction("تحويل من فرانكو إلى العربية") {
            val value = input.text.toString().trim()
            if (value.isBlank()) toast("اكتب نص فرانكو أولاً") else {
                val converted = translateToArabic(value)
                output.text = converted
                saveHistory(value, converted, "فرانكو ← عربي")
                hideKeyboard(input)
            }
        }
        addAction("تبديل النص والنتيجة") {
            val old = input.text.toString()
            input.setText(output.text.toString())
            output.text = old
        }
        addAction("نسخ النتيجة") { copyText(output.text.toString()) }
        addAction("إضافة إلى المفضلة") {
            val value = output.text.toString()
            if (value.isNotBlank() && value != "ستظهر الترجمة هنا...") addFavorite(input.text.toString(), value)
        }
        addAction("مشاركة النتيجة") { shareText(output.text.toString()) }
        addAction("🎙 إدخال صوتي") { startVoiceInput() }
    }

    private fun translateToFranco(input: String): String {
        if (input.isBlank()) return ""
        dictionary[input.trim()]?.let { return it }
        return input.map { arabicMap[it] ?: it.toString() }.joinToString("")
    }

    private fun translateToArabic(input: String): String {
        if (input.isBlank()) return ""
        val reverse = dictionary.entries.associate { it.value.lowercase() to it.key }
        reverse[input.trim().lowercase()]?.let { return it }
        return input.split(Regex("(?<=\\s)|(?=\\s)")).joinToString("") { word ->
            if (word.isBlank()) word else reverse[word.lowercase()] ?: decodeFrancoWord(word)
        }
    }

    private fun decodeFrancoWord(value: String): String {
        val replacements = listOf(
            "sh" to "ش", "kh" to "خ", "gh" to "غ", "th" to "ث", "ch" to "چ",
            "aa" to "ا", "ee" to "ي", "oo" to "و", "2" to "ء", "3" to "ع",
            "5" to "خ", "6" to "ط", "7" to "ح", "8" to "غ", "9" to "ص",
            "a" to "ا", "b" to "ب", "t" to "ت", "j" to "ج", "d" to "د",
            "r" to "ر", "z" to "ز", "s" to "س", "f" to "ف", "q" to "ق",
            "k" to "ك", "l" to "ل", "m" to "م", "n" to "ن", "h" to "ه",
            "w" to "و", "y" to "ي", "p" to "پ", "g" to "گ", "e" to "ي",
            "i" to "ي", "o" to "و", "u" to "و"
        )
        val text = value.lowercase()
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val match = replacements.firstOrNull { text.startsWith(it.first, i) }
            if (match != null) {
                out.append(match.second)
                i += match.first.length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun saveHistory(input: String, output: String, direction: String) {
        history.add(0, JSONObject().apply {
            put("input", input); put("output", output); put("direction", direction); put("time", System.currentTimeMillis())
        })
        while (history.size > 250) history.removeAt(history.lastIndex)
        saveData()
    }

    private fun addFavorite(input: String, output: String) {
        if (favorites.any { it.optString("input") == input && it.optString("output") == output }) {
            toast("العبارة موجودة مسبقاً")
            return
        }
        favorites.add(0, JSONObject().apply {
            put("input", input); put("output", output); put("time", System.currentTimeMillis())
        })
        saveData()
        toast("تمت الإضافة إلى المفضلة")
    }

    private fun showAcademy() {
        clearPage("أكاديمية TON FOX")
        addCard {
            addView(label("تعلّم الفرانكو خطوة بخطوة", 20f, gold, true))
            addView(label("المستوى $level · $score XP · ${completedLessons.size}/${lessons.size} دروس مكتملة", 14f, muted))
        }
        lessons.forEachIndexed { index, lesson ->
            addCard {
                addView(label("${if (completedLessons.contains(index)) "✓" else "○"} الدرس ${index + 1}: ${lesson.title}", 16f, gold, true))
                addView(label(lesson.description, 13f, muted))
                addView(actionButton("فتح الدرس") { showLesson(index) })
            }
        }
        addAction("اختبار شامل", true) { startQuiz(-1) }
    }

    private fun showLesson(index: Int) {
        if (index !in lessons.indices) return
        val lesson = lessons[index]
        clearPage(lesson.title)
        addCard {
            addView(label("الدرس ${index + 1} من ${lessons.size}", 13f, gold, true))
            addView(label(lesson.description, 14f, muted))
            lesson.content.forEachIndexed { i, line -> addView(label("${i + 1}. $line", 15f)) }
        }
        val answer = EditText(this).apply {
            hint = "اكتب التحويل بالفرانكو"
            setTextColor(cream)
            setHintTextColor(muted)
            background = shape(panel, 12, gold)
            setPadding(12, 12, 12, 12)
        }
        addCard {
            addView(label("تدريب: ${lesson.exampleArabic}", 18f, gold, true))
            addView(answer)
            addView(actionButton("تحقق من الإجابة", true) {
                val entered = answer.text.toString().replace(" ", "").lowercase()
                val expected = lesson.acceptedAnswer.replace(" ", "").lowercase()
                if (entered == expected || entered == translateToFranco(lesson.exampleArabic).replace(" ", "").lowercase()) {
                    if (completedLessons.add(index)) score += 20
                    updateLevel()
                    saveData()
                    toast("إجابة صحيحة!")
                    showLesson(index)
                } else toast("حاول مرة أخرى. مثال: $expected")
            })
        }
        addAction("اختبار هذا الدرس") { startQuiz(index) }
        addAction("العودة للأكاديمية") { showAcademy() }
    }

    private fun updateLevel() { level = score / 100 + 1 }

    private fun startQuiz(lessonIndex: Int) {
        val questions = if (lessonIndex >= 0) listOf(lessons[lessonIndex]) else lessons.shuffled().take(5)
        var index = 0
        var correct = 0
        fun next() {
            if (index >= questions.size) {
                val earned = correct * 10
                score += earned
                updateLevel()
                saveData()
                clearPage("نتيجة الاختبار")
                addCard {
                    addView(label("انتهى الاختبار!", 23f, gold, true))
                    addView(label("الإجابات الصحيحة: $correct/${questions.size}", 17f))
                    addView(label("+$earned XP", 16f, gold))
                }
                addAction("العودة للأكاديمية", true) { showAcademy() }
                return
            }
            val lesson = questions[index]
            val expected = translateToFranco(lesson.exampleArabic)
            clearPage("السؤال ${index + 1}/${questions.size}")
            addCard {
                addView(label("حوّل: ${lesson.exampleArabic}", 20f, gold, true))
                val choices = (listOf(expected) + lessons.filter { it != lesson }.map { translateToFranco(it.exampleArabic) }.shuffled().take(3)).distinct().shuffled()
                choices.forEach { choice ->
                    addView(actionButton(choice) {
                        if (choice == expected) correct++
                        index++
                        next()
                    })
                }
            }
        }
        next()
    }

    private fun showDictionary() {
        clearPage("القاموس")
        val search = EditText(this).apply {
            hint = "ابحث بالعربية أو الفرانكو"
            setTextColor(cream); setHintTextColor(muted); background = shape(panel, 12, gold)
            setPadding(12, 12, 12, 12)
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(search)
        content.addView(results)
        fun render(q: String) {
            results.removeAllViews()
            dictionary.filter { it.key.contains(q, true) || it.value.contains(q, true) }
                .forEach { (ar, fr) -> results.addView(label("$ar  —  $fr", 15f, cream)) }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
        addAction("بطاقات حفظ الكلمات", true) { showFlashcards() }
    }

    private fun showFlashcards() {
        val entries = dictionary.entries.toList()
        var index = 0
        var reveal = false
        fun render() {
            clearPage("بطاقات الحفظ")
            val entry = entries[index % entries.size]
            addCard {
                addView(label("بطاقة ${index + 1}/${entries.size}", 13f, muted))
                addView(label(if (reveal) entry.value else entry.key, 28f, gold, true))
                addView(actionButton(if (reveal) "إخفاء الإجابة" else "إظهار الإجابة", true) {
                    reveal = !reveal; render()
                })
            }
            addAction("البطاقة التالية") { index = (index + 1) % entries.size; reveal = false; render() }
            addAction("العودة للقاموس") { showDictionary() }
        }
        if (entries.isNotEmpty()) render()
    }

    private fun showSaved() {
        clearPage("المحفوظات")
        addCard {
            addView(label("المفضلة (${favorites.size})", 19f, gold, true))
        }
        if (favorites.isEmpty()) addCard { addView(label("لا توجد عبارات محفوظة.", 14f, muted)) }
        favorites.toList().forEach { item ->
            addCard {
                addView(label(item.optString("input"), 15f, cream, true))
                addView(label(item.optString("output"), 15f, gold))
                addView(actionButton("نسخ النتيجة") { copyText(item.optString("output")) })
                addView(actionButton("حذف") {
                    favorites.remove(item); saveData(); showSaved()
                })
            }
        }
        addGap()
        content.addView(label("سجل الترجمات (${history.size})", 19f, gold, true))
        history.take(60).forEach { item ->
            addCard {
                addView(label(item.optString("direction"), 12f, gold))
                addView(label(item.optString("input"), 14f))
                addView(label(item.optString("output"), 14f, muted))
                addView(actionButton("إعادة استخدام") {
                    showTranslator()
                    sourceText?.setText(item.optString("input"))
                })
            }
        }
        addAction("مسح السجل") {
            AlertDialog.Builder(this).setTitle("مسح السجل؟")
                .setMessage("سيتم حذف سجل الترجمات فقط.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف") { _, _ -> history.clear(); saveData(); showSaved() }
                .show()
        }
    }

    private fun showHistorySearch() {
        clearPage("البحث في السجل")
        val input = EditText(this).apply { hint = "ابحث في السجل"; setTextColor(cream); setHintTextColor(muted) }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(input); content.addView(results)
        fun render(q: String) {
            results.removeAllViews()
            history.filter { it.optString("input").contains(q, true) || it.optString("output").contains(q, true) }
                .forEach { item ->
                    results.addView(label("${item.optString("input")}  —  ${item.optString("output")}", 14f))
                }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        render("")
    }

    private fun showPhraseGenerator() {
        clearPage("مولّد العبارات")
        val phrases = listOf("شلونك؟", "صباح الخير", "وينك؟", "شنو الأخبار؟", "أنا بالطريق", "اشتقت لك", "الله يسعدك", "عندي امتحان", "أحتاج مساعدة")
        phrases.forEach { phrase ->
            addCard {
                addView(label(phrase, 15f, cream))
                addView(actionButton("تحويل ونسخ") {
                    val converted = translateToFranco(phrase)
                    copyText(converted)
                    toast(converted)
                })
            }
        }
    }

    private fun showNameGenerator() {
        clearPage("مولّد الأسماء")
        val names = listOf("نور", "ليث", "زين", "آدم", "علي", "حسن", "حسين", "مريم", "زهراء", "فاطمة", "سارة", "ياسمين", "محمد", "يوسف", "كرار", "مصطفى", "حيدر")
        val output = label("اضغط لتوليد اسم", 24f, gold, true)
        addCard {
            addView(output)
            addView(actionButton("توليد اسم", true) {
                val name = names.random()
                output.text = "$name\n${translateToFranco(name)}"
            })
            addView(actionButton("نسخ الاسم") { copyText(output.text.toString().substringBefore("\n")) })
        }
    }

    private fun showStatistics() {
        clearPage("الإحصائيات")
        addCard {
            addView(label("الترجمات: ${history.size}", 16f))
            addView(label("المفضلة: ${favorites.size}", 16f))
            addView(label("الدروس: ${completedLessons.size}/${lessons.size}", 16f))
            addView(label("النقاط: $score XP", 16f, gold, true))
            addView(label("المستوى: $level", 16f))
            addView(label("سلسلة التعلم: $streak يوم", 16f))
        }
    }

    private fun showSettings() {
        clearPage("الإعدادات")
        addCard {
            addView(label("TON FOX V2", 20f, gold, true))
            addView(label("يعمل التطبيق محلياً ويحفظ بياناته على الجهاز.", 14f, muted))
            addView(label("المطور: N5CR4", 14f, cream))
            addView(label("Telegram: @N5CR4", 14f, gold))
            addView(actionButton("تصدير نسخة احتياطية", true) { exportBackup() })
            addView(actionButton("استيراد نسخة احتياطية") { importBackup() })
            addView(actionButton("حذف جميع البيانات") { confirmClearData() })
            addView(actionButton("التواصل مع المطور") {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/N5CR4"))) }
                catch (_: Exception) { toast("تعذر فتح تيليجرام") }
            })
        }
    }

    private fun showMore() {
        clearPage("المزيد")
        addAction("القاموس", true) { showDictionary() }
        addAction("بطاقات حفظ الكلمات") { showFlashcards() }
        addAction("البحث في السجل") { showHistorySearch() }
        addAction("مولّد العبارات") { showPhraseGenerator() }
        addAction("مولّد الأسماء") { showNameGenerator() }
        addAction("الإحصائيات") { showStatistics() }
        addAction("الإعدادات والنسخ الاحتياطي") { showSettings() }
        addCard { addView(label("ملاحظة: التحويل تقريبي وقد تختلف كتابة الكلمات حسب اللهجة والشخص.", 13f, muted)) }
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this).setTitle("حذف البيانات")
            .setMessage("سيتم حذف السجل والمفضلة والتقدم التعليمي.")
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حذف") { _, _ ->
                history.clear(); favorites.clear(); completedLessons.clear(); achievements.clear()
                score = 0; level = 1; streak = 0
                saveData(); showSettings(); toast("تم حذف البيانات")
            }.show()
    }

    private fun exportBackup() {
        val backup = JSONObject().apply {
            put("app", "TON FOX"); put("version", 2)
            put("history", JSONArray().apply { history.forEach { put(it) } })
            put("favorites", JSONArray().apply { favorites.forEach { put(it) } })
            put("lessons", JSONArray().apply { completedLessons.forEach { put(it) } })
            put("score", score); put("level", level); put("streak", streak)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "TON FOX Backup")
            putExtra(Intent.EXTRA_TEXT, backup.toString(2))
        }
        startActivity(Intent.createChooser(intent, "مشاركة النسخة الاحتياطية"))
    }

    private fun importBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
        }
        try { startActivityForResult(intent, REQUEST_IMPORT_BACKUP) }
        catch (_: Exception) { toast("تعذر فتح مدير الملفات") }
    }

    private fun onBackupImported(uri: android.net.Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("تعذر قراءة الملف")
            val json = JSONObject(text)
            if (json.optString("app") != "TON FOX") { toast("ملف غير صالح"); return }
            history.clear(); favorites.clear(); completedLessons.clear()
            val h = json.optJSONArray("history") ?: JSONArray()
            for (i in 0 until h.length()) h.optJSONObject(i)?.let { history.add(it) }
            val f = json.optJSONArray("favorites") ?: JSONArray()
            for (i in 0 until f.length()) f.optJSONObject(i)?.let { favorites.add(it) }
            val l = json.optJSONArray("lessons") ?: JSONArray()
            for (i in 0 until l.length()) completedLessons.add(l.optInt(i))
            score = json.optInt("score", 0).coerceAtLeast(0)
            level = json.optInt("level", 1).coerceAtLeast(1)
            streak = json.optInt("streak", 0).coerceAtLeast(0)
            saveData(); showHome(); toast("تم استيراد النسخة")
        } catch (e: Exception) { toast("فشل الاستيراد: ${e.message}") }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث الآن")
        }
        try { startActivityForResult(intent, REQUEST_VOICE_INPUT) }
        catch (_: Exception) { toast("التعرف الصوتي غير متاح") }
    }

    @Deprecated("Kept for compatibility with older Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQUEST_VOICE_INPUT -> {
                val spoken = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (!spoken.isNullOrBlank()) {
                    showTranslator()
                    sourceText?.setText(spoken)
                    toast("تم إدخال النص الصوتي")
                }
            }
            REQUEST_IMPORT_BACKUP -> data.data?.let { onBackupImported(it) }
        }
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun copyText(text: String) {
        if (text.isBlank()) { toast("لا يوجد نص لنسخه"); return }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TON FOX", text))
        toast("تم النسخ")
    }

    private fun shareText(text: String) {
        if (text.isBlank()) { toast("لا يوجد نص للمشاركة"); return }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$text\n\nTON FOX 🦊")
        }
        startActivity(Intent.createChooser(intent, "مشاركة"))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_VOICE_INPUT = 9001
        private const val REQUEST_IMPORT_BACKUP = 9002
    }
}
