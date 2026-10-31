package com.ton.fox

import android.app.Activity
import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.InputType

class MainActivity : Activity() {

    private val bg = Color.rgb(12, 14, 23)
    private val panel = Color.rgb(23, 27, 40)
    private val gold = Color.rgb(255, 190, 65)
    private val white = Color.rgb(245, 246, 250)
    private val muted = Color.rgb(155, 164, 182)
    private val green = Color.rgb(80, 210, 160)

    private var currentPage = "translator"
    private var arabicToLatin = true

    private lateinit var mainLayout: LinearLayout
    private lateinit var activeContent: LinearLayout
    private lateinit var inputText: EditText
    private lateinit var outputText: TextView

    private val arabicMap = mapOf(
        'ا' to "a",
        'أ' to "2",
        'إ' to "2",
        'آ' to "2a",
        'ء' to "2",
        'ؤ' to "2",
        'ئ' to "2",
        'ب' to "b",
        'ت' to "t",
        'ث' to "th",
        'ج' to "j",
        'ح' to "7",
        'خ' to "5",
        'د' to "d",
        'ذ' to "dh",
        'ر' to "r",
        'ز' to "z",
        'س' to "s",
        'ش' to "sh",
        'ص' to "9",
        'ض' to "9'",
        'ط' to "6",
        'ظ' to "6'",
        'ع' to "3",
        'غ' to "gh",
        'ف' to "f",
        'ق' to "q",
        'ك' to "k",
        'ل' to "l",
        'م' to "m",
        'ن' to "n",
        'ه' to "h",
        'و' to "w",
        'ي' to "y",
        'ى' to "a",
        'ة' to "a",
        'پ' to "p",
        'چ' to "ch",
        'ڤ' to "v",
        'گ' to "g"
    )

    private val reverseMap = mapOf(
        "2a" to "آ",
        "9'" to "ض",
        "6'" to "ظ",
        "th" to "ث",
        "dh" to "ذ",
        "sh" to "ش",
        "gh" to "غ",
        "ch" to "چ",
        "kh" to "خ",
        "2" to "ء",
        "3" to "ع",
        "5" to "خ",
        "6" to "ط",
        "7" to "ح",
        "8" to "غ",
        "9" to "ص",
        "a" to "ا",
        "b" to "ب",
        "t" to "ت",
        "j" to "ج",
        "d" to "د",
        "r" to "ر",
        "z" to "ز",
        "s" to "س",
        "f" to "ف",
        "q" to "ق",
        "k" to "ك",
        "l" to "ل",
        "m" to "م",
        "n" to "ن",
        "h" to "ه",
        "w" to "و",
        "y" to "ي",
        "p" to "پ",
        "v" to "ڤ",
        "g" to "گ",
        "e" to "ع",
        "i" to "ي",
        "o" to "و",
        "u" to "و"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        showTranslator()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun rounded(
        color: Int,
        radius: Int = 18,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()

            if (strokeColor != Color.TRANSPARENT) {
                setStroke(dp(1), strokeColor)
            }
        }
    }

    private fun text(
        value: String,
        size: Float = 15f,
        color: Int = white,
        bold: Boolean = false
    ): TextView {

        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)

            typeface = if (bold) {
                Typeface.create("sans-serif", Typeface.BOLD)
            } else {
                Typeface.create("sans-serif", Typeface.NORMAL)
            }

            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
    }

    private fun button(
        title: String,
        color: Int = panel,
        textColor: Int = white,
        action: () -> Unit
    ): Button {

        return Button(this).apply {
            text = title
            textSize = 13f
            setTextColor(textColor)
            isAllCaps = false
            background = rounded(color, 14)

            setPadding(
                dp(12),
                dp(8),
                dp(12),
                dp(8)
            )

            setOnClickListener {
                action()
            }
        }
    }

    private fun space(height: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                1,
                dp(height)
            )
        }
    }

    private fun horizontalSpace(width: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(width),
                1
            )
        }
    }

    private fun card(): LinearLayout {

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 20)

            setPadding(
                dp(17),
                dp(17),
                dp(17),
                dp(17)
            )

            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
    }

    private fun addText(
        parent: LinearLayout,
        value: String,
        size: Float = 15f,
        color: Int = white,
        bold: Boolean = false
    ): TextView {

        val view = text(value, size, color, bold)
        parent.addView(view)
        return view
    }

    private fun addSpace(
        parent: LinearLayout,
        height: Int = 12
    ) {
        parent.addView(space(height))
    }

    private fun setupPage(
        title: String,
        subtitle: String
    ) {

        mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutDirection = View.LAYOUT_DIRECTION_RTL

            setPadding(
                dp(18),
                dp(15),
                dp(18),
                dp(8)
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        header.addView(
            text("🦊 TON FOX", 27f, gold, true)
        )

        header.addView(
            text(title, 19f, white, true)
        )

        header.addView(
            text(subtitle, 12f, muted)
        )

        mainLayout.addView(header)
        addSpace(mainLayout, 15)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }

        activeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL

            setPadding(
                0,
                0,
                0,
                dp(15)
            )
        }

        scroll.addView(activeContent)

        mainLayout.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(panel, 18)

            setPadding(
                dp(4),
                dp(5),
                dp(4),
                dp(5)
            )

            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val pages = listOf(
            Pair("المترجم", "translator"),
            Pair("التعليم", "course"),
            Pair("حول", "about")
        )

        pages.forEach { item ->

            val selected = currentPage == item.second

            val navButton = button(
                item.first,
                if (selected) gold else panel,
                if (selected) Color.BLACK else muted
            ) {

                when (item.second) {
                    "translator" -> showTranslator()
                    "course" -> showCourse()
                    "about" -> showAbout()
                }
            }

            navigation.addView(
                navButton,
                LinearLayout.LayoutParams(
                    0,
                    dp(47),
                    1f
                )
            )
        }

        mainLayout.addView(navigation)

        setContentView(mainLayout)
    }

    // =====================================
    // TRANSLATOR
    // =====================================

    private fun showTranslator() {

        currentPage = "translator"

        setupPage(
            "المترجم الذكي",
            "ترجمة بين العربية والفرانكو بسهولة"
        )

        addText(
            activeContent,
            "اختر اتجاه الترجمة",
            16f,
            gold,
            true
        )

        addSpace(activeContent, 10)

        val directions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val toFranco = button(
            "العربية ← فرانكو",
            if (arabicToLatin) gold else panel,
            if (arabicToLatin) Color.BLACK else white
        ) {
            arabicToLatin = true
            showTranslator()
        }

        val toArabic = button(
            "فرانكو ← العربية",
            if (!arabicToLatin) gold else panel,
            if (!arabicToLatin) Color.BLACK else white
        ) {
            arabicToLatin = false
            showTranslator()
        }

        directions.addView(
            toFranco,
            LinearLayout.LayoutParams(
                0,
                dp(49),
                1f
            )
        )

        directions.addView(horizontalSpace(7))

        directions.addView(
            toArabic,
            LinearLayout.LayoutParams(
                0,
                dp(49),
                1f
            )
        )

        activeContent.addView(directions)

        addSpace(activeContent, 18)

        val inputCard = card()

        addText(
            inputCard,
            if (arabicToLatin) "النص العربي" else "نص الفرانكو",
            15f,
            gold,
            true
        )

        addSpace(inputCard, 10)

        inputText = EditText(this).apply {

            hint = if (arabicToLatin) {
                "اكتب النص العربي هنا..."
            } else {
                "Write Franco text here..."
            }

            textSize = 17f
            setTextColor(white)
            setHintTextColor(muted)

            gravity = Gravity.TOP or Gravity.RIGHT

            minLines = 4
            maxLines = 8

            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES

            background = rounded(bg, 14)

            setPadding(
                dp(13),
                dp(13),
                dp(13),
                dp(13)
            )

            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        inputCard.addView(
            inputText,
            LinearLayout.LayoutParams(
                -1,
                dp(145)
            )
        )

        addSpace(inputCard, 12)

        val clearButton = button(
            "مسح النص",
            bg,
            muted
        ) {

            inputText.setText("")
            outputText.text = "ستظهر الترجمة هنا..."
        }

        inputCard.addView(
            clearButton,
            LinearLayout.LayoutParams(
                -1,
                dp(43)
            )
        )

        activeContent.addView(inputCard)

        addSpace(activeContent, 13)

        val convertButton = button(
            "⇄   ترجمة النص الآن",
            gold,
            Color.BLACK
        ) {

            val source = inputText.text.toString()

            if (source.isBlank()) {

                toast("يرجى كتابة نص أولاً")

            } else {

                val result = if (arabicToLatin) {
                    arabicToFranco(source)
                } else {
                    francoToArabic(source)
                }

                outputText.text = result
            }
        }

        activeContent.addView(
            convertButton,
            LinearLayout.LayoutParams(
                -1,
                dp(54)
            )
        )

        addSpace(activeContent, 17)

        val outputCard = card()

        addText(
            outputCard,
            "نتيجة الترجمة",
            16f,
            gold,
            true
        )

        addSpace(outputCard, 12)

        outputText = text(
            "ستظهر الترجمة هنا...",
            19f,
            white,
            true
        ).apply {

            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            minHeight = dp(85)

            setPadding(
                dp(10),
                dp(10),
                dp(10),
                dp(10)
            )

            background = rounded(bg, 14)
        }

        outputCard.addView(
            outputText,
            LinearLayout.LayoutParams(
                -1,
                -2
            )
        )

        addSpace(outputCard, 12)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        val copyButton = button(
            "نسخ النتيجة",
            green,
            Color.BLACK
        ) {
            copyText(outputText.text.toString())
        }

        val swapButton = button(
            "عكس الاتجاه",
            bg,
            white
        ) {

            val oldInput = inputText.text.toString()
            val oldOutput = outputText.text.toString()

            arabicToLatin = !arabicToLatin

            showTranslator()

            inputText.setText(
                if (oldOutput == "ستظهر الترجمة هنا...") {
                    oldInput
                } else {
                    oldOutput
                }
            )
        }

        actions.addView(
            copyButton,
            LinearLayout.LayoutParams(
                0,
                dp(47),
                1f
            )
        )

        actions.addView(horizontalSpace(8))

        actions.addView(
            swapButton,
            LinearLayout.LayoutParams(
                0,
                dp(47),
                1f
            )
        )

        outputCard.addView(actions)

        activeContent.addView(outputCard)

        addSpace(activeContent, 16)

        val note = card()

        addText(
            note,
            "ملاحظة مهمة",
            14f,
            gold,
            true
        )

        addSpace(note, 5)

        addText(
            note,
            "الفرانكو ليست لها طريقة كتابة موحدة تماماً. " +
                    "قد تختلف بعض الحروف حسب البلد واللهجة وطريقة الاستخدام.",
            13f,
            muted
        )

        activeContent.addView(note)
    }

    // =====================================
    // ARABIC TO FRANCO
    // =====================================

    private fun arabicToFranco(value: String): String {

        val result = StringBuilder()

        value.forEach { char ->

            val normalized = when (char) {

                'أ', 'إ' -> 'أ'
                'آ' -> 'آ'
                'ؤ', 'ئ', 'ء' -> 'ء'

                else -> char
            }

            result.append(
                arabicMap[normalized] ?: char.toString()
            )
        }

        return result.toString()
    }

    // =====================================
    // FRANCO TO ARABIC
    // =====================================

    private fun francoToArabic(value: String): String {

        val source = value.lowercase()
        val result = StringBuilder()

        val keys = reverseMap.keys.sortedByDescending {
            it.length
        }

        var i = 0

        while (i < source.length) {

            var matched = false

            for (key in keys) {

                if (source.startsWith(key, i)) {

                    result.append(reverseMap[key])

                    i += key.length

                    matched = true

                    break
                }
            }

            if (!matched) {

                result.append(source[i])
                i++
            }
        }

        return result.toString()
    }

    // =====================================
    // EDUCATION COURSE
    // =====================================

    private fun showCourse() {

        currentPage = "course"

        setupPage(
            "أكاديمية TON FOX",
            "تعلم كتابة وقراءة الفرانكو من الصفر"
        )

        val intro = card()

        addText(
            intro,
            "مرحباً بك في دورة الفرانكو 🦊",
            18f,
            gold,
            true
        )

        addSpace(intro, 9)

        addText(
            intro,
            "ستتعلم هنا كيف تحول الحروف العربية إلى أحرف " +
                    "لاتينية وأرقام، وكيف تقرأ الرسائل المكتوبة بالفرانكو.",
            14f,
            muted
        )

        activeContent.addView(intro)

        addSpace(activeContent, 16)

        addText(
            activeContent,
            "الدروس التعليمية",
            18f,
            gold,
            true
        )

        addSpace(activeContent, 10)

        val lessons = listOf(

            Pair(
                "الدرس الأول: ما هي الفرانكو؟",

                "الفرانكو أو العربيزي طريقة لكتابة اللغة العربية " +
                        "باستخدام الحروف الإنجليزية والأرقام.\n\n" +
                        "أمثلة:\n\n" +
                        "مرحبا = mar7aba\n" +
                        "كيف حالك = keef 7alak\n" +
                        "أنا بخير = ana b5air\n" +
                        "حبيبي = 7abibi"
            ),

            Pair(
                "الدرس الثاني: الأرقام الأساسية",

                "تستخدم الأرقام للتعبير عن بعض الحروف العربية " +
                        "التي لا يوجد لها مقابل مباشر في الإنجليزية.\n\n" +
                        "2 = ء\n" +
                        "3 = ع\n" +
                        "5 = خ\n" +
                        "6 = ط\n" +
                        "7 = ح\n" +
                        "8 = غ\n" +
                        "9 = ص\n\n" +
                        "أمثلة:\n" +
                        "7ob = حب\n" +
                        "3omr = عمر\n" +
                        "5aled = خالد"
            ),

            Pair(
                "الدرس الثالث: الحروف المركبة",

                "بعض الحروف تكتب باستخدام حرفين إنجليزيين.\n\n" +
                        "sh = ش\n" +
                        "th = ث\n" +
                        "dh = ذ\n" +
                        "gh = غ\n" +
                        "kh = خ\n" +
                        "ch = چ\n\n" +
                        "أمثلة:\n" +
                        "shams = شمس\n" +
                        "ghali = غالي\n" +
                        "khaled = خالد"
            ),

            Pair(
                "الدرس الرابع: تكوين الكلمات",

                "تعلم كيف تكتب الكلمات اليومية.\n\n" +
                        "Salam = سلام\n" +
                        "Mar7aba = مرحبا\n" +
                        "Shlonak = شلونك\n" +
                        "Ana = أنا\n" +
                        "Enta = أنت\n" +
                        "Enti = أنتِ\n" +
                        "Shukran = شكراً\n" +
                        "Habibi = حبيبي"
            ),

            Pair(
                "الدرس الخامس: تكوين الجمل",

                "حاول كتابة جمل كاملة بدلاً من الكلمات المفردة.\n\n" +
                        "Ana a7eb el qiraa = أنا أحب القراءة\n" +
                        "Shlonak el yom = شلونك اليوم\n" +
                        "Ana rayeh lel bait = أنا رايح للبيت\n" +
                        "Weinak = وينك\n" +
                        "Ma afham = ما أفهم\n\n" +
                        "تذكر أن الكتابة تختلف حسب اللهجة."
            ),

            Pair(
                "الدرس السادس: المحادثة اليومية",

                "عبارات تستخدمها في المحادثات:\n\n" +
                        "Shako mako = شكو ماكو\n" +
                        "Shlonak = شلونك\n" +
                        "Tamam = تمام\n" +
                        "Yalla = يلا\n" +
                        "Inshallah = إن شاء الله\n" +
                        "Ma3a alsalama = مع السلامة\n" +
                        "7abibi = حبيبي"
            ),

            Pair(
                "الدرس السابع: الاحتراف والممارسة",

                "حتى تتقن الفرانكو:\n\n" +
                        "1. احفظ الأرقام الأساسية.\n" +
                        "2. تدرب على كتابة الكلمات يومياً.\n" +
                        "3. اقرأ رسائل الفرانكو وحاول فهمها.\n" +
                        "4. اكتب جمل كاملة.\n" +
                        "5. استخدم المترجم للتأكد من كتابتك.\n\n" +
                        "أفضل طريقة للتعلم هي الممارسة المستمرة."
            )
        )

        lessons.forEachIndexed { index, lesson ->

            val lessonCard = card()

            addText(
                lessonCard,
                lesson.first,
                15f,
                white,
                true
            )

            addSpace(lessonCard, 8)

            val description = text(
                lesson.second,
                13f,
                muted
            ).apply {

                gravity = Gravity.RIGHT

                visibility = View.GONE

                setLineSpacing(
                    dp(5).toFloat(),
                    1f
                )
            }

            lessonCard.addView(description)

            addSpace(lessonCard, 10)

            // إصلاح خطأ open السابق:
            // ننشئ الزر أولاً ثم نربط الحدث به.

            val openButton = button(
                "فتح الدرس ${index + 1}",
                bg,
                gold
            ) {
                // يتم تعيين الحدث لاحقاً
            }

            openButton.setOnClickListener {

                val isVisible =
                    description.visibility == View.VISIBLE

                description.visibility = if (isVisible) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                openButton.text = if (isVisible) {
                    "فتح الدرس ${index + 1}"
                } else {
                    "إغلاق الدرس"
                }
            }

            lessonCard.addView(openButton)

            activeContent.addView(lessonCard)

            addSpace(activeContent, 10)
        }

        val practiceButton = button(
            "ابدأ التدريب في المترجم",
            gold,
            Color.BLACK
        ) {
            showTranslator()
        }

        activeContent.addView(
            practiceButton,
            LinearLayout.LayoutParams(
                -1,
                dp(53)
            )
        )
    }

    // =====================================
    // ABOUT PAGE
    // =====================================

    private fun showAbout() {

        currentPage = "about"

        setupPage(
            "حول التطبيق",
            "TON FOX · Arabizi Translator"
        )

        val logo = card().apply {
            gravity = Gravity.CENTER
        }

        addText(
            logo,
            "🦊",
            65f,
            gold,
            true
        ).apply {
            gravity = Gravity.CENTER
        }

        addText(
            logo,
            "TON FOX",
            29f,
            gold,
            true
        ).apply {
            gravity = Gravity.CENTER
        }

        addText(
            logo,
            "ARABIZI TRANSLATOR",
            12f,
            muted,
            true
        ).apply {
            gravity = Gravity.CENTER
        }

        activeContent.addView(logo)

        addSpace(activeContent, 15)

        val details = card()

        addText(
            details,
            "عن TON FOX",
            18f,
            gold,
            true
        )

        addSpace(details, 10)

        addText(
            details,
            "تطبيق متخصص في تحويل النصوص بين اللغة العربية " +
                    "والعربيزي (الفرانكو)، مع دورة تعليمية تساعدك " +
                    "على تعلم هذه الطريقة من البداية وحتى الاحتراف.",
            14f,
            white
        )

        addSpace(details, 15)

        addText(
            details,
            "مميزات التطبيق",
            16f,
            gold,
            true
        )

        addSpace(details, 8)

        val features = listOf(
            "• الترجمة من العربية إلى الفرانكو.",
            "• الترجمة من الفرانكو إلى العربية.",
            "• نسخ النصوص بسهولة.",
            "• دورة تعليمية متكاملة.",
            "• يعمل دون الحاجة إلى الإنترنت.",
            "• واجهة عربية سهلة الاستخدام."
        )

        features.forEach {

            addText(
                details,
                it,
                14f,
                muted
            )

            addSpace(details, 5)
        }

        activeContent.addView(details)

        addSpace(activeContent, 15)

        val developer = card()

        addText(
            developer,
            "حقوق التطوير",
            17f,
            gold,
            true
        )

        addSpace(developer, 10)

        addText(
            developer,
            "Developer: N5CR4",
            15f,
            white,
            true
        )

        addSpace(developer, 8)

        addText(
            developer,
            "Telegram: @N5CR4",
            15f,
            green,
            true
        )

        addSpace(developer, 12)

        val telegramButton = button(
            "نسخ حساب التلغرام",
            gold,
            Color.BLACK
        ) {
            copyText("@N5CR4")
        }

        developer.addView(telegramButton)

        activeContent.addView(developer)

        addSpace(activeContent, 20)

        addText(
            activeContent,
            "TON FOX © 2026",
            12f,
            muted,
            true
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    // =====================================
    // CLIPBOARD
    // =====================================

    private fun copyText(value: String) {

        if (
            value.isBlank() ||
            value == "ستظهر الترجمة هنا..."
        ) {

            toast("لا يوجد نص لنسخه")
            return
        }

        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "TON FOX",
                value
            )
        )

        toast("تم نسخ النص بنجاح")
    }

    // =====================================
    // TOAST
    // =====================================

    private fun toast(message: String) {

        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }
}