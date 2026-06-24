package com.jellywatch.client

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private val background = Color.rgb(0, 0, 0)
    private val panel = Color.rgb(27, 24, 31)
    private val panelLight = Color.rgb(42, 38, 47)
    private val purple = Color.rgb(170, 92, 195)
    private val teal = Color.rgb(0, 164, 220)
    private val muted = Color.rgb(185, 180, 190)

    private lateinit var store: SessionStore
    private var session: Session? = null
    private var api: JellyfinApi? = null
    private val art = ArtworkLoader()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val history = mutableListOf<Screen>()
    private var generation = 0

    private sealed class Screen {
        data object Login : Screen()
        data object Home : Screen()
        data object Search : Screen()
        data class Library(val title: String, val parentId: String) : Screen()
        data class Details(val item: JellyItem) : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = SessionStore(this)
        session = store.load()
        api = session?.let(::JellyfinApi)
        navigate(if (session == null) Screen.Login else Screen.Home, replace = true)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (history.size > 1) {
            history.removeAt(history.lastIndex)
            render(history.last())
        } else {
            super.onBackPressed()
        }
    }

    private fun navigate(screen: Screen, replace: Boolean = false) {
        if (replace) history.clear()
        if (history.lastOrNull() != screen) history.add(screen)
        render(screen)
    }

    private fun render(screen: Screen) {
        generation++
        when (screen) {
            Screen.Login -> renderLogin()
            Screen.Home -> loadHome()
            Screen.Search -> renderSearch()
            is Screen.Library -> loadLibrary(screen)
            is Screen.Details -> renderDetails(screen.item)
        }
    }

    private fun renderLogin() {
        val body = column(gravity = Gravity.CENTER_HORIZONTAL)
        body.setPadding(dp(30), dp(42), dp(30), dp(48))

        val logo = ImageView(this).apply {
            setImageResource(com.jellywatch.client.R.drawable.ic_jellywatch)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        body.addView(logo, LinearLayout.LayoutParams(dp(82), dp(82)))
        body.addView(text("JellyWatch", 24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        body.addView(text("Jellyfin, right on your wrist", 13f, muted).apply { gravity = Gravity.CENTER })
        body.addView(space(20))

        val server = input("Server address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        body.addView(server, matchWrap(bottom = 8))

        val connect = actionButton("Get sign-in code", primary = true)
        body.addView(connect, matchFixed(dp(50), bottom = 12))
        body.addView(text("Enter the complete address, such as\nhttps://jellyfin.example.com", 11f, muted).apply {
            gravity = Gravity.CENTER
        })

        connect.setOnClickListener {
            val normalized = runCatching { JellyfinApi.normalizeServer(server.text.toString()) }
                .getOrElse {
                    toast(it.message ?: "Invalid server address")
                    return@setOnClickListener
                }
            connect.isEnabled = false
            connect.text = "Connecting…"
            val pending = Session(normalized, "", "", "", store.deviceId())
            val pendingApi = JellyfinApi(pending)
            work(
                block = { pendingApi.initiateQuickConnect() },
                success = { quickConnect ->
                    val token = ++generation
                    renderQuickConnect(normalized, quickConnect.code)
                    pollQuickConnect(pendingApi, quickConnect.secret, token, attemptsLeft = 100)
                },
                failure = {
                    connect.isEnabled = true
                    connect.text = "Get sign-in code"
                    toast("Could not start Quick Connect: ${friendlyError(it)}")
                }
            )
        }
        setContent(screen(body))
    }

    private fun renderQuickConnect(server: String, code: String) {
        val body = column(gravity = Gravity.CENTER_HORIZONTAL)
        body.setPadding(dp(30), dp(42), dp(30), dp(50))
        body.addView(text("QUICK CONNECT", 11f, teal, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .14f
        })
        body.addView(text(code, 38f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .16f
        }, matchWrap(top = 10, bottom = 15))
        body.addView(text("On your phone or computer:", 14f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
        })
        body.addView(text("Open Jellyfin → user menu → Quick Connect, then enter this code.", 13f, muted).apply {
            gravity = Gravity.CENTER
        }, matchWrap(top = 5, bottom = 14))
        val waiting = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        body.addView(waiting, LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(text("Waiting for approval…", 12f, muted).apply { gravity = Gravity.CENTER }, matchWrap(top = 6, bottom = 15))
        val cancel = actionButton("Cancel", primary = false)
        body.addView(cancel, matchFixed(dp(46)))
        body.addView(text(server, 10f, muted).apply { gravity = Gravity.CENTER }, matchWrap(top = 12))
        cancel.setOnClickListener { render(Screen.Login) }
        setContent(screen(body))
    }

    private fun pollQuickConnect(
        pendingApi: JellyfinApi,
        secret: String,
        token: Int,
        attemptsLeft: Int
    ) {
        if (token != generation) return
        if (attemptsLeft <= 0) {
            toast("The sign-in code expired. Please try again.")
            render(Screen.Login)
            return
        }
        main.postDelayed({
            if (token != generation) return@postDelayed
            work(
                block = { pendingApi.isQuickConnectApproved(secret) },
                success = { approved ->
                    if (token != generation) return@work
                    if (approved) {
                        work(
                            block = { pendingApi.authenticateQuickConnect(secret) },
                            success = { authenticated ->
                                if (token != generation) return@work
                                store.save(authenticated)
                                session = authenticated
                                api = JellyfinApi(authenticated)
                                navigate(Screen.Home, replace = true)
                            },
                            failure = {
                                if (token == generation) {
                                    toast("Sign-in failed: ${friendlyError(it)}")
                                    render(Screen.Login)
                                }
                            }
                        )
                    } else {
                        pollQuickConnect(pendingApi, secret, token, attemptsLeft - 1)
                    }
                },
                failure = {
                    if (token == generation) pollQuickConnect(pendingApi, secret, token, attemptsLeft - 1)
                }
            )
        }, 3_000)
    }

    private fun loadHome() {
        val token = generation
        setContent(loading("Loading ${session?.userName ?: "Jellyfin"}…"))
        work(
            block = {
                val current = requireNotNull(api)
                Triple(current.resumeItems(), current.latest(), current.libraries())
            },
            success = { (resume, latest, libraries) ->
                if (token == generation) renderHome(resume, latest, libraries)
            },
            failure = {
                if (token == generation) renderError("Couldn't load your server", it) { render(Screen.Home) }
            }
        )
    }

    private fun renderHome(resume: List<JellyItem>, latest: List<JellyItem>, libraries: List<JellyItem>) {
        val body = column()
        body.setPadding(dp(22), dp(32), dp(22), dp(52))

        val eyebrow = text("JELLYWATCH", 11f, teal, bold = true).apply { letterSpacing = .16f }
        body.addView(eyebrow)
        body.addView(text("Hi, ${session?.userName}", 23f, Color.WHITE, bold = true))
        body.addView(space(10))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val search = iconButton(R.drawable.ic_search, "Search", primary = true)
        val refresh = iconButton(R.drawable.ic_refresh, "Refresh libraries", primary = false)
        val signOut = iconButton(R.drawable.ic_more, "Account options", primary = false)
        actions.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(refresh, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(signOut, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions, matchWrap(bottom = 8))
        search.setOnClickListener { navigate(Screen.Search) }
        refresh.setOnClickListener { render(Screen.Home) }
        signOut.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setMessage("Remove this Jellyfin account from the watch?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Sign out") { _, _ ->
                    store.clear()
                    session = null
                    api = null
                    navigate(Screen.Login, replace = true)
                }
                .show()
        }

        if (resume.isNotEmpty()) addShelf(body, "Continue watching", resume)
        if (latest.isNotEmpty()) addShelf(body, "Recently added", latest)

        body.addView(sectionTitle("Libraries"))
        if (libraries.isEmpty()) {
            body.addView(emptyState("No libraries found"))
        } else {
            libraries.forEach { library -> body.addView(libraryRow(library), matchWrap(bottom = 7)) }
        }
        setContent(screen(body))
    }

    private fun addShelf(parent: LinearLayout, title: String, items: List<JellyItem>) {
        parent.addView(sectionTitle(title))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { row.addView(posterCard(it), LinearLayout.LayoutParams(dp(116), dp(176)).apply { marginEnd = dp(8) }) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row)
        }
        parent.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(182)))
    }

    private fun posterCard(item: JellyItem): View {
        val card = column()
        card.background = rounded(panel, 18f)
        card.setPadding(dp(5), dp(5), dp(5), dp(7))
        val image = artworkView(dp(106), dp(116))
        art.load(api?.imageUrl(item, 240), image)
        card.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(116)))
        card.addView(text(item.name, 12f, Color.WHITE, bold = true, maxLines = 2), matchWrap(top = 5))
        if (item.playedPercentage > 0) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                this.progress = item.playedPercentage.roundToInt().coerceIn(0, 100)
                progressTintList = android.content.res.ColorStateList.valueOf(teal)
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(panelLight)
            }
            card.addView(progress, matchFixed(dp(3), top = 3))
        }
        card.setOnClickListener { openItem(item) }
        return card
    }

    private fun libraryRow(item: JellyItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 20f)
            setPadding(dp(8), dp(8), dp(14), dp(8))
        }
        val image = artworkView(dp(54), dp(54))
        art.load(api?.imageUrl(item, 140), image)
        row.addView(image, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(11) })
        val labels = column()
        labels.addView(text(item.name, 15f, Color.WHITE, bold = true))
        labels.addView(text("Browse library", 11f, muted))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 26f, teal))
        row.setOnClickListener { navigate(Screen.Library(item.name, item.id)) }
        return row
    }

    private fun loadLibrary(screen: Screen.Library) {
        val token = generation
        setContent(loading("Opening ${screen.title}…"))
        work(
            block = { requireNotNull(api).children(screen.parentId) },
            success = { if (token == generation) renderLibrary(screen, it) },
            failure = { if (token == generation) renderError("Couldn't open ${screen.title}", it) { render(screen) } }
        )
    }

    private fun renderLibrary(screen: Screen.Library, items: List<JellyItem>) {
        val body = column()
        body.setPadding(dp(22), dp(34), dp(22), dp(52))
        body.addView(backHeader(screen.title))
        body.addView(text("${items.size} items", 11f, muted), matchWrap(bottom = 10))
        if (items.isEmpty()) body.addView(emptyState("Nothing here yet"))
        items.forEach { body.addView(resultRow(it), matchWrap(bottom = 7)) }
        setContent(screen(body))
    }

    private fun renderSearch() {
        val body = column()
        body.setPadding(dp(22), dp(34), dp(22), dp(52))
        body.addView(backHeader("Search"))
        val searchBox = input("Movies, shows, music…", InputType.TYPE_CLASS_TEXT).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            requestFocus()
        }
        val button = actionButton("Search", primary = true)
        val results = column()
        body.addView(searchBox, matchWrap(bottom = 8))
        body.addView(button, matchFixed(dp(48), bottom = 12))
        body.addView(results)
        setContent(screen(body))

        fun performSearch() {
            val query = searchBox.text.toString().trim()
            if (query.length < 2) {
                toast("Type at least two characters")
                return
            }
            button.isEnabled = false
            button.text = "Searching…"
            results.removeAllViews()
            work(
                block = { requireNotNull(api).search(query) },
                success = { items ->
                    button.isEnabled = true
                    button.text = "Search"
                    if (items.isEmpty()) results.addView(emptyState("No results for “$query”"))
                    items.forEach { results.addView(resultRow(it), matchWrap(bottom = 7)) }
                },
                failure = {
                    button.isEnabled = true
                    button.text = "Search"
                    toast("Search failed: ${friendlyError(it)}")
                }
            )
        }
        button.setOnClickListener { performSearch() }
        searchBox.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                performSearch()
                true
            } else false
        }
    }

    private fun resultRow(item: JellyItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 17f)
            setPadding(dp(6), dp(6), dp(11), dp(6))
        }
        val image = artworkView(dp(62), dp(76))
        art.load(api?.imageUrl(item, 160), image)
        row.addView(image, LinearLayout.LayoutParams(dp(62), dp(76)).apply { marginEnd = dp(10) })
        val labels = column()
        labels.addView(text(item.name, 14f, Color.WHITE, bold = true, maxLines = 2))
        labels.addView(text(item.subtitle, 11f, muted, maxLines = 2), matchWrap(top = 2))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(if (item.isPlayable) "▶" else "›", 16f, teal))
        row.setOnClickListener { openItem(item) }
        return row
    }

    private fun openItem(item: JellyItem) {
        if (item.isPlayable) navigate(Screen.Details(item))
        else navigate(Screen.Library(item.name, item.id))
    }

    private fun renderDetails(item: JellyItem) {
        val body = column()
        body.setPadding(dp(22), dp(28), dp(22), dp(54))
        body.addView(backHeader("Details"))
        val image = artworkView(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        art.load(api?.imageUrl(item, 480, backdrop = true) ?: api?.imageUrl(item, 480), image)
        body.addView(image, matchFixed(dp(190), bottom = 12))
        body.addView(text(item.name, 22f, Color.WHITE, bold = true))
        body.addView(text(item.subtitle, 12f, teal, bold = true), matchWrap(top = 3, bottom = 10))
        val label = if (item.playbackTicks > 0) "▶  Resume" else "▶  Play"
        val play = actionButton(label, primary = true)
        body.addView(play, matchFixed(dp(52), bottom = 13))
        if (item.overview.isNotBlank()) body.addView(text(item.overview, 13f, muted))
        play.setOnClickListener { PlayerActivity.start(this, requireNotNull(session), item) }
        setContent(screen(body))
    }

    private fun renderError(title: String, error: Throwable, retry: () -> Unit) {
        val body = column(gravity = Gravity.CENTER_HORIZONTAL)
        body.setPadding(dp(28), dp(60), dp(28), dp(50))
        body.addView(text("!", 36f, purple, bold = true).apply { gravity = Gravity.CENTER })
        body.addView(text(title, 19f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        body.addView(text(friendlyError(error), 12f, muted).apply { gravity = Gravity.CENTER }, matchWrap(top = 6, bottom = 16))
        val button = actionButton("Try again", primary = true)
        body.addView(button, matchFixed(dp(50)))
        button.setOnClickListener { retry() }
        setContent(screen(body))
    }

    private fun loading(label: String): View {
        val root = FrameLayout(this).apply { setBackgroundColor(this@MainActivity.background) }
        val box = column(gravity = Gravity.CENTER)
        val progress = ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(teal) }
        box.addView(progress, LinearLayout.LayoutParams(dp(46), dp(46)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        box.addView(text(label, 13f, muted).apply { gravity = Gravity.CENTER }, matchWrap(top = 10))
        root.addView(box, FrameLayout.LayoutParams(dp(270), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun backHeader(title: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = actionButton("‹", primary = false)
        row.addView(back, LinearLayout.LayoutParams(dp(46), dp(42)).apply { marginEnd = dp(9) })
        row.addView(text(title, 21f, Color.WHITE, bold = true, maxLines = 2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        back.setOnClickListener { onBackPressed() }
        return row
    }

    private fun sectionTitle(value: String) = text(value, 16f, Color.WHITE, bold = true).apply {
        setPadding(0, dp(15), 0, dp(8))
    }

    private fun emptyState(value: String) = text(value, 13f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(22), dp(8), dp(22))
        background = rounded(panel, 18f)
    }

    private fun screen(body: View): View = ScrollView(this).apply {
        setBackgroundColor(this@MainActivity.background)
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun column(gravity: Int = Gravity.NO_GRAVITY) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = Int.MAX_VALUE) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            setMaxLines(maxLines)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun input(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setTextColor(Color.WHITE)
        setHintTextColor(Color.rgb(155, 149, 160))
        textSize = 14f
        setSingleLine(true)
        setPadding(dp(16), 0, dp(16), 0)
        background = rounded(panel, 22f, strokeColor = panelLight)
    }

    private fun actionButton(label: String, primary: Boolean) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        background = rounded(if (primary) purple else panel, 24f, if (primary) null else panelLight)
        setPadding(dp(10), 0, dp(10), 0)
        stateListAnimator = null
    }

    private fun iconButton(drawableRes: Int, description: String, primary: Boolean) = ImageButton(this).apply {
        setImageResource(drawableRes)
        contentDescription = description
        imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        scaleType = ImageView.ScaleType.CENTER
        background = rounded(if (primary) purple else panel, 24f, if (primary) null else panelLight)
        setPadding(dp(13), dp(10), dp(13), dp(10))
        stateListAnimator = null
    }

    private fun artworkView(width: Int, height: Int) = ImageView(this).apply {
        layoutParams = ViewGroup.LayoutParams(width, height)
        setBackgroundColor(panelLight)
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(13).toFloat())
            }
        }
        setImageResource(com.jellywatch.client.R.drawable.ic_jellywatch)
        imageTintList = android.content.res.ColorStateList.valueOf(Color.argb(95, 255, 255, 255))
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun matchWrap(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun matchFixed(height: Int, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun space(dp: Int) = Space(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(dp)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun setContent(view: View) = setContentView(view)
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    private fun friendlyError(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is SSLHandshakeException || it is CertPathValidatorException }) {
            return "HTTPS certificate not trusted. Use a public certificate or install your private certificate authority on the watch."
        }
        val root = causes.last()
        return root.message?.replace("Failed to connect to", "Can't reach") ?: "Unexpected error"
    }

    private fun <T> work(block: () -> T, success: (T) -> Unit, failure: (Throwable) -> Unit) {
        worker.execute {
            runCatching(block).fold(
                onSuccess = { main.post { success(it) } },
                onFailure = { main.post { failure(it) } }
            )
        }
    }
}
