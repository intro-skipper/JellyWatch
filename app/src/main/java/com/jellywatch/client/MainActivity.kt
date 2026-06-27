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
    private lateinit var homePreferences: HomeScreenPreferences
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
        data object HomeSettings : Screen()
        data object Search : Screen()
        data object ServerDiscovery : Screen()
        data class ManualLogin(val server: String) : Screen()
        data class Library(val title: String, val parentId: String, val parentType: String? = null) : Screen()
        data class Details(val item: JellyItem) : Screen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        store = SessionStore(this)
        homePreferences = HomeScreenPreferences(this)
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
            Screen.HomeSettings -> renderHomeSettings()
            Screen.Search -> renderSearch()
            Screen.ServerDiscovery -> discoverServers()
            is Screen.ManualLogin -> renderManualLogin(screen.server)
            is Screen.Library -> loadLibrary(screen)
            is Screen.Details -> renderDetails(screen.item)
        }
    }

    private fun renderLogin() {
        val body = column(gravity = Gravity.CENTER_HORIZONTAL)
        body.setPadding(dp(30), dp(38), dp(30), dp(48))

        val logo = ImageView(this).apply {
            setImageResource(com.jellywatch.client.R.drawable.ic_jellywatch)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        body.addView(logo, LinearLayout.LayoutParams(dp(82), dp(82)))
        body.addView(text("JellyWatch", 24f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        body.addView(text("Jellyfin, right on your wrist", 13f, muted).apply { gravity = Gravity.CENTER })
        body.addView(space(22))

        val server = input("Server address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        body.addView(server, matchFixed(dp(50), bottom = 15))

        val quickConnect = actionButton("Quick Connect", primary = true)
        body.addView(quickConnect, matchFixed(dp(50), bottom = 9))
        val discover = actionButton("Find server", primary = false)
        body.addView(discover, matchFixed(dp(46), bottom = 14))
        val manual = actionButton("Sign in manually", primary = false)
        body.addView(manual, matchFixed(dp(46), bottom = 9))
        body.addView(text("Enter the complete address, such as\nhttps://jellyfin.example.com", 11f, muted).apply {
            gravity = Gravity.CENTER
        })

        quickConnect.setOnClickListener {
            val normalized = runCatching { JellyfinApi.normalizeServer(server.text.toString()) }
                .getOrElse {
                    toast(it.message ?: "Invalid server address")
                    return@setOnClickListener
                }
            quickConnect.isEnabled = false
            quickConnect.text = "Connecting..."
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
                    quickConnect.isEnabled = true
                    quickConnect.text = "Quick Connect"
                    toast("Could not start Quick Connect: ${friendlyError(it)}")
                }
            )
        }
        manual.setOnClickListener {
            val normalized = runCatching { JellyfinApi.normalizeServer(server.text.toString()) }
                .getOrElse {
                    toast(it.message ?: "Invalid server address")
                    return@setOnClickListener
                }
            navigate(Screen.ManualLogin(normalized))
        }
        discover.setOnClickListener { navigate(Screen.ServerDiscovery) }
        setContent(screen(body))
    }

    private fun beginQuickConnect(server: String) {
        setContent(loading("Connecting to Jellyfin..."))
        val pending = Session(server, "", "", "", store.deviceId())
        val pendingApi = JellyfinApi(pending)
        work(
            block = { pendingApi.initiateQuickConnect() },
            success = { quickConnect ->
                val token = ++generation
                renderQuickConnect(server, quickConnect.code)
                pollQuickConnect(pendingApi, quickConnect.secret, token, attemptsLeft = 100)
            },
            failure = {
                toast("Could not start Quick Connect: ${friendlyError(it)}")
                render(Screen.Login)
            }
        )
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

    private fun renderManualLogin(server: String) {
        val body = column(gravity = Gravity.CENTER_HORIZONTAL)
        body.setPadding(dp(28), dp(34), dp(28), dp(50))
        body.addView(backHeader("Sign in"), matchWrap(bottom = 14))
        body.addView(text("SERVER", 11f, teal, bold = true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .14f
        })
        body.addView(text(server, 11f, muted, maxLines = 2).apply {
            gravity = Gravity.CENTER
        }, matchWrap(top = 3, bottom = 16))

        val username = input("Username", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        val password = input("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        body.addView(username, matchFixed(dp(50), bottom = 10))
        body.addView(password, matchFixed(dp(50), bottom = 16))

        val signIn = actionButton("Sign in", primary = true)
        val quick = actionButton("Use Quick Connect", primary = false)
        body.addView(signIn, matchFixed(dp(50), bottom = 9))
        body.addView(quick, matchFixed(dp(46)))

        signIn.setOnClickListener {
            val name = username.text.toString().trim()
            val pass = password.text.toString()
            if (name.isBlank() || pass.isBlank()) {
                toast("Enter your username and password")
                return@setOnClickListener
            }
            signIn.isEnabled = false
            signIn.text = "Signing in..."
            val pending = Session(server, "", "", "", store.deviceId())
            val pendingApi = JellyfinApi(pending)
            work(
                block = { pendingApi.authenticate(name, pass) },
                success = { authenticated ->
                    store.save(authenticated)
                    session = authenticated
                    api = JellyfinApi(authenticated)
                    navigate(Screen.Home, replace = true)
                },
                failure = {
                    signIn.isEnabled = true
                    signIn.text = "Sign in"
                    toast("Sign-in failed: ${friendlyError(it)}")
                }
            )
        }
        quick.setOnClickListener { beginQuickConnect(server) }
        setContent(screen(body))
    }

    private fun discoverServers() {
        val token = generation
        setContent(loading("Finding Jellyfin servers..."))
        work(
            block = {
                discoveryCandidates()
                    .mapNotNull { candidate -> runCatching { JellyfinApi.discoverServer(candidate) }.getOrNull() }
                    .distinctBy { it.url }
            },
            success = { if (token == generation) renderServerDiscovery(it) },
            failure = { if (token == generation) renderServerDiscovery(emptyList()) }
        )
    }

    private fun renderServerDiscovery(servers: List<DiscoveredServer>) {
        val body = column()
        body.setPadding(dp(22), dp(34), dp(22), dp(52))
        body.addView(backHeader("Find server"), matchWrap(bottom = 12))
        if (servers.isEmpty()) {
            body.addView(emptyState("No Jellyfin servers found"))
            val retry = actionButton("Try again", primary = true)
            body.addView(retry, matchFixed(dp(48), top = 12, bottom = 8))
            retry.setOnClickListener { discoverServers() }
        } else {
            servers.forEach { server ->
                body.addView(serverRow(server), matchWrap(bottom = 8))
            }
        }
        val manual = actionButton("Enter address", primary = false)
        body.addView(manual, matchFixed(dp(46), top = 8))
        manual.setOnClickListener { navigate(Screen.Login) }
        setContent(screen(body))
    }

    private fun serverRow(server: DiscoveredServer): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 18f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val labels = column()
        labels.addView(text(server.name, 15f, Color.WHITE, bold = true, maxLines = 1))
        labels.addView(text("${server.url}\n${server.version}", 11f, muted, maxLines = 2), matchWrap(top = 2))
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text("›", 24f, teal))
        row.setOnClickListener { navigate(Screen.ManualLogin(server.url)) }
        row.setOnLongClickListener {
            beginQuickConnect(server.url)
            true
        }
        return row
    }

    private fun discoveryCandidates() = listOf(
        "http://jellyfin.local:8096",
        "https://jellyfin.local",
        "http://localhost:8096",
        "http://127.0.0.1:8096"
    )

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

    private fun showHomeOptions() {
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setItems(arrayOf("Customize home", "Sign out")) { _, choice ->
                when (choice) {
                    0 -> navigate(Screen.HomeSettings)
                    1 -> confirmSignOut()
                }
            }
            .show()
    }

    private fun confirmSignOut() {
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

    private fun renderHomeSettings() {
        val settings = homePreferences.load()
        val body = column()
        body.setPadding(dp(22), dp(34), dp(22), dp(52))
        body.addView(backHeader("Customize home"))
        body.addView(text("Sections appear on Home in this order. Turn each section on or off, or use the arrows to change the order.", 12f, muted), matchWrap(top = 7, bottom = 10))
        settings.forEachIndexed { index, setting ->
            body.addView(homeSectionSettingCard(settings, index, setting), matchWrap(bottom = 8))
        }
        setContent(screen(body))
    }

    private fun homeSectionSettingCard(
        settings: List<HomeSectionSetting>,
        index: Int,
        setting: HomeSectionSetting
    ): View {
        val card = column().apply {
            background = rounded(panel, 18f)
            setPadding(dp(12), dp(10), dp(12), dp(11))
        }
        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        heading.addView(text(setting.section.title, 15f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = actionButton(if (setting.enabled) "On" else "Off", primary = setting.enabled)
        heading.addView(toggle, LinearLayout.LayoutParams(dp(72), dp(40)))
        card.addView(heading)

        val order = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val moveUp = iconButton(R.drawable.ic_arrow_upward, "Move up", primary = false).apply {
            isEnabled = index > 0
            alpha = if (isEnabled) 1f else 0.35f
        }
        val moveDown = iconButton(R.drawable.ic_arrow_downward, "Move down", primary = false).apply {
            isEnabled = index < settings.lastIndex
            alpha = if (isEnabled) 1f else 0.35f
        }
        order.addView(moveUp, LinearLayout.LayoutParams(dp(44), dp(40)))
        order.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        order.addView(moveDown, LinearLayout.LayoutParams(dp(44), dp(40)))
        card.addView(order, matchWrap(top = 8))

        toggle.setOnClickListener {
            val updated = settings.toMutableList()
            updated[index] = setting.copy(enabled = !setting.enabled)
            saveHomeSettings(updated)
        }
        moveUp.setOnClickListener { moveHomeSection(settings, index, index - 1) }
        moveDown.setOnClickListener { moveHomeSection(settings, index, index + 1) }
        return card
    }

    private fun moveHomeSection(settings: List<HomeSectionSetting>, from: Int, to: Int) {
        if (to !in settings.indices) return
        val updated = settings.toMutableList()
        val moved = updated.removeAt(from)
        updated.add(to, moved)
        saveHomeSettings(updated)
    }

    private fun saveHomeSettings(settings: List<HomeSectionSetting>) {
        homePreferences.save(settings)
        renderHomeSettings()
    }

    private fun loadHome() {
        val token = generation
        setContent(loading("Loading ${session?.userName ?: "Jellyfin"}…"))
        work(
            block = {
                val current = requireNotNull(api)
                val settings = homePreferences.load()
                val sections = settings.filter { it.enabled }.associate { setting ->
                    setting.section to when (setting.section) {
                        HomeSection.ContinueWatching -> current.resumeItems()
                        HomeSection.Libraries -> current.libraries()
                        HomeSection.NextUp -> current.nextUp()
                        HomeSection.RecentlyAdded -> current.latest()
                    }
                }
                settings to sections
            },
            success = { (settings, sections) ->
                if (token == generation) renderHome(settings, sections)
            },
            failure = {
                if (token == generation) renderError("Couldn't load your server", it) { render(Screen.Home) }
            }
        )
    }

    private fun renderHome(
        settings: List<HomeSectionSetting>,
        sections: Map<HomeSection, List<JellyItem>>
    ) {
        val body = column()
        body.setPadding(dp(22), dp(32), dp(22), dp(52))

        val eyebrow = text("JELLYWATCH", 11f, teal, bold = true).apply { letterSpacing = .16f }
        body.addView(eyebrow)
        body.addView(text("Hi, ${session?.userName}", 23f, Color.WHITE, bold = true))
        body.addView(space(10))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val search = iconButton(R.drawable.ic_search, "Search", primary = true)
        val refresh = iconButton(R.drawable.ic_refresh, "Refresh home", primary = false)
        val options = iconButton(R.drawable.ic_more, "Home and account options", primary = false)
        actions.addView(search, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(refresh, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(5) })
        actions.addView(options, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(actions, matchWrap(bottom = 8))
        search.setOnClickListener { navigate(Screen.Search) }
        refresh.setOnClickListener { render(Screen.Home) }
        options.setOnClickListener { showHomeOptions() }

        val enabled = settings.filter { it.enabled }
        if (enabled.isEmpty()) {
            body.addView(emptyState("No home sections enabled. Use the options button to customize Home."))
        }
        enabled.forEach { setting ->
            val items = sections[setting.section].orEmpty()
            when (setting.section) {
                HomeSection.ContinueWatching -> addShelf(body, setting.section.title, items, "Nothing to continue", resumeDirectly = true)
                HomeSection.Libraries -> addLibraries(body, items)
                HomeSection.NextUp -> addShelf(body, setting.section.title, items, "Nothing is up next")
                HomeSection.RecentlyAdded -> addShelf(body, setting.section.title, items, "Nothing recently added")
            }
        }
        setContent(screen(body))
    }

    private fun addShelf(
        parent: LinearLayout,
        title: String,
        items: List<JellyItem>,
        emptyMessage: String,
        resumeDirectly: Boolean = false
    ) {
        parent.addView(sectionTitle(title))
        if (items.isEmpty()) {
            parent.addView(emptyState(emptyMessage))
            return
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { row.addView(posterCard(it, resumeDirectly), LinearLayout.LayoutParams(dp(116), dp(176)).apply { marginEnd = dp(8) }) }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            addView(row)
        }
        parent.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(182)))
    }

    private fun addLibraries(parent: LinearLayout, libraries: List<JellyItem>) {
        parent.addView(sectionTitle(HomeSection.Libraries.title))
        if (libraries.isEmpty()) {
            parent.addView(emptyState("No libraries found"))
        } else {
            libraries.forEach { library -> parent.addView(libraryRow(library), matchWrap(bottom = 7)) }
        }
    }

    private fun posterCard(item: JellyItem, resumeDirectly: Boolean): View {
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
        card.setOnClickListener {
            if (resumeDirectly && item.isPlayable) {
                PlayerActivity.start(this, requireNotNull(session), item)
            } else {
                openItem(item)
            }
        }
        if (resumeDirectly) {
            card.setOnLongClickListener {
                openContinueWatchingSource(item)
                true
            }
        }
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
        row.setOnClickListener { navigate(Screen.Library(item.name, item.id, item.type)) }
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
        body.addView(backHeader(screen.title), matchWrap(bottom = 10))
        if (items.isEmpty()) body.addView(emptyState("Nothing here yet"))
        items.forEach { body.addView(resultRow(it, directPlayback = shouldDirectPlayFromLibrary(screen, it)), matchWrap(bottom = 7)) }
        body.addView(text("${items.size} items", 11f, muted).apply { gravity = Gravity.CENTER }, matchWrap(top = 10))
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

    private fun resultRow(item: JellyItem, directPlayback: Boolean = false): View {
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
        row.setOnClickListener {
            if (directPlayback && item.isPlayable) {
                PlayerActivity.start(this, requireNotNull(session), item)
            } else {
                openItem(item)
            }
        }
        if (directPlayback) {
            row.setOnLongClickListener {
                navigate(Screen.Details(item))
                true
            }
        }
        return row
    }

    private fun openItem(item: JellyItem) {
        if (item.isPlayable) navigate(Screen.Details(item))
        else navigate(Screen.Library(item.name, item.id, item.type))
    }

    private fun shouldDirectPlayFromLibrary(screen: Screen.Library, item: JellyItem): Boolean {
        return when (screen.parentType) {
            "Season" -> item.type == "Episode"
            "BoxSet", "CollectionFolder" -> item.type == "Movie"
            else -> false
        }
    }

    private fun openContinueWatchingSource(item: JellyItem) {
        val parent = continueWatchingParent(item)
        if (parent != null && history.lastOrNull() != parent) {
            history.add(parent)
        }
        navigate(Screen.Details(item))
    }

    private fun continueWatchingParent(item: JellyItem): Screen.Library? {
        if (item.type == "Episode") {
            val seasonId = item.seasonId ?: item.parentId ?: return null
            return Screen.Library(seasonTitle(item), seasonId, "Season")
        }
        val parentId = item.parentId ?: return null
        return Screen.Library(parentTitle(item), parentId)
    }

    private fun seasonTitle(item: JellyItem): String {
        return listOfNotNull(
            item.seriesName,
            item.parentIndexNumber?.let { "Season $it" }
        ).joinToString(" - ").ifBlank { "Season" }
    }

    private fun parentTitle(item: JellyItem): String {
        return when (item.type) {
            "Movie" -> "Movies"
            "Audio" -> "Music"
            else -> "Library"
        }
    }

    private fun renderDetails(item: JellyItem) {
        val body = column()
        body.setPadding(dp(22), dp(28), dp(22), dp(54))
        body.addView(backHeader("Details"), matchWrap(bottom = 12))
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
