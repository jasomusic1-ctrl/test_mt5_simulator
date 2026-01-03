package com.example.accountinfo

import android.animation.ObjectAnimator
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


class MainActivity : AppCompatActivity() {

    private val BASE_URL = "http://10.170.110.81:8080/"
    //fst projectttttttt

    lateinit var gson: Gson
    lateinit var okHttpClient: OkHttpClient
    lateinit var api: Mt5SimApi

    private var webSocket: WebSocket? = null
    private var wsReconnectJob: Job? = null
    private var pollingJob: Job? = null
    private var tradesPollingJob: Job? = null
    private var historyPollingJob: Job? = null

    @Volatile
    var isConnected = false

    // Current account tracker - single source of truth
    @Volatile
    var currentAccount = "Fast/Acc"

    // Switching state to prevent concurrent switches with timeout
    private val isSwitchingAccount = AtomicBoolean(false)
    private var switchTimeoutJob: Job? = null

    // OPTIMIZED CACHE SYSTEM with LRU eviction
    private val accountDataCache = object : LinkedHashMap<String, AccountData>(
        4, // Initial capacity for 4 accounts
        0.75f,
        true // Access-order (LRU)
    ) {
        override fun removeEldestEntry(eldest: Map.Entry<String, AccountData>): Boolean {
            // Keep current account + 2 most recently accessed
            return size > 3 && eldest.key != currentAccount
        }
    }

    // All available accounts (Fast/Acc, Demo/Acc, Hunter/Acc, LivePro/Acc)
    private val ALL_ACCOUNTS = listOf("Fast/Acc", "Demo/Acc", "Hunter/Acc", "LivePro/Acc")

    // Aggressive cache tracking - store more history for faster loading
    private val MAX_HISTORY_TRADES_INACTIVE = 200 // Store more history for inactive accounts
    private val MAX_HISTORY_TRADES_ACTIVE = 2000 // Much more history for active account

    // Drawer layout
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuIcon: ImageView

    // Bottom navigation views
    private lateinit var tabQuotes: View
    private lateinit var tabCharts: View
    private lateinit var tabTrade: View
    private lateinit var tabHistory: View
    private lateinit var tabMessages: View

    // Track current fragment to determine menu icon visibility
    private var currentFragmentTag: String = "trade"

    // Fragment instances for hide/show switching (reduces latency)
    private var quotesFragment: QuotesFragment? = null
    private var chartsFragment: ChartsFragment? = null
    private var tradeFragment: TradeFragment? = null
    private var historyFragment: HistoryFragment? = null
    private var messagesFragment: MessagesFragment? = null
    private var activeFragment: Fragment? = null

    // Simplified data class - removed cache size tracking overhead
    data class AccountData(
        val metrics: AccountMetrics,
        val activeTrades: List<TradeData>,
        val historySummary: TradesSummaryResponse,
        val lastUpdated: Long = System.currentTimeMillis()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "Device: API ${Build.VERSION.SDK_INT}, Android ${Build.VERSION.RELEASE}")
        Log.d("MainActivity", "Connecting to: $BASE_URL")
        Log.d("MainActivity", "Cache Strategy: LRU with max 3 accounts (current + 2 recent)")

        setContentView(R.layout.activity_main_container)

        // ===== ENABLE STATUS BAR FOR ANDROID 10+ =====
        setupStatusBar()

        // Initialize dependencies
        gson = Gson()
        okHttpClient = provideOkHttp()
        api = provideApi()

        // Initialize drawer
        setupDrawer()

        // Setup bottom navigation
        setupBottomNavigation()

        // Setup back press handling
        setupBackPressHandling()

        // Initialize all fragments and add them (hidden except trade)
        if (savedInstanceState == null) {
            initializeFragments()
        }

        // Test network connectivity
        testNetworkConnection()

        // OPTIMIZED: Preload current + 1 most important account
        lifecycleScope.launch {
            delay(500) // Reduced delay
            if (isConnected) {
                Log.d("MainActivity", "🚀 Starting optimized data preload...")

                // Preload current account immediately
                preloadAccountData(currentAccount)

                // Preload one more account in background (usually Demo/Acc)
                launch {
                    delay(2000)
                    preloadAccountData("Demo/Acc")
                }

                Log.d("MainActivity", "✓ Critical account data preloaded")

                // Start polling for current account
                startSummaryPolling()
                startTradesPolling()
                startHistoryPolling()
                openWebSocket()
            }
        }
    }

    /**
     * OPTIMIZED: Preload single account data
     * Lazy-loads other accounts on demand
     */
    private suspend fun preloadAccountData(accountType: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d("MainActivity", "⟳ Preloading $accountType...")

                // Switch backend to account
                api.switchAccount(SwitchAccountRequest(accountType))
                delay(100) // Minimal delay

                // Fetch all data for this account
                val metrics = async { api.getAccountMetrics() }
                val trades = async { api.getActiveTrades() }
                val history = async { api.getTradesSummary() }

                // Wait for all to complete in parallel
                val accountData = AccountData(
                    metrics = metrics.await(),
                    activeTrades = trades.await(),
                    historySummary = trimHistoryForCache(history.await(), accountType),
                    lastUpdated = System.currentTimeMillis()
                )

                accountDataCache[accountType] = accountData

                // Cache to Room database asynchronously
                launch { cacheToRoom(accountType) }

                Log.d("MainActivity", "✓ Preloaded $accountType: Balance=${accountData.metrics.balance}, " +
                        "Deposit=${accountData.metrics.deposit}, " +
                        "ActiveTrades=${accountData.activeTrades.size}, History=${accountData.historySummary.all_trades.size}")

            } catch (e: Exception) {
                Log.e("MainActivity", "⚠️ Failed to preload $accountType", e)
            }
        }
    }

    /**
     * OPTIMIZED: Trim history based on account status
     */
    private fun trimHistoryForCache(history: TradesSummaryResponse, accountType: String): TradesSummaryResponse {
        val limit = if (accountType == currentAccount) {
            MAX_HISTORY_TRADES_ACTIVE
        } else {
            MAX_HISTORY_TRADES_INACTIVE
        }

        return if (history.all_trades.size > limit) {
            history.copy(all_trades = history.all_trades.take(limit))
        } else {
            history
        }
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout)
        menuIcon = findViewById(R.id.menu_icon)

        menuIcon.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
            Log.d("MainActivity", "Drawer opened")
        }

        drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                Log.d("MainActivity", "Drawer is now open")
                setupDrawerClickListener()
            }

            override fun onDrawerClosed(drawerView: View) {
                Log.d("MainActivity", "Drawer is now closed")
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        drawerLayout.setScrimColor(0x99000000.toInt())
    }

    private fun setupDrawerClickListener() {
        val drawerView = drawerLayout.getChildAt(1)
        val sideMenuImage = drawerView?.findViewById<ImageView>(R.id.imageView)

        sideMenuImage?.setOnClickListener {
            Log.d("MainActivity", "Side menu image clicked")
            drawerLayout.closeDrawer(GravityCompat.START)
            loadFragment(AccountsFragment(), "accounts")
            Log.d("MainActivity", "Navigated to AccountsFragment")
        }
    }

    private fun setupBackPressHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Setup status bar for Android 10+ (API 29+)
     * Enables edge-to-edge display and handles window insets properly
     */
    private fun setupStatusBar() {
        // Enable edge-to-edge mode (draws behind system bars)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Get the root view
        val rootView = findViewById<View>(android.R.id.content)

        // Apply window insets to handle status bar padding
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Apply top padding for status bar
            view.setPadding(
                insets.left,
                insets.top,  // Status bar height
                insets.right,
                insets.bottom  // Navigation bar height
            )

            Log.d("MainActivity", "Window insets applied - Top: ${insets.top}, Bottom: ${insets.bottom}")

            // Return CONSUMED to indicate we've handled the insets
            WindowInsetsCompat.CONSUMED
        }

        // Set status bar appearance (light background with dark icons)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
            windowInsetsController.isAppearanceLightStatusBars = true  // Dark icons on light background

            Log.d("MainActivity", "Status bar configured: Light background with dark icons")
        }
    }

    private fun setupBottomNavigation() {
        tabQuotes = findViewById(R.id.tab_quotes)
        tabCharts = findViewById(R.id.tab_charts)
        tabTrade = findViewById(R.id.tab_trade)
        tabHistory = findViewById(R.id.tab_history)
        tabMessages = findViewById(R.id.tab_messages)

        tabQuotes.setOnClickListener {
            showFragment("quotes")
            updateBottomNavigationUI("quotes")
        }

        tabCharts.setOnClickListener {
            showFragment("charts")
            updateBottomNavigationUI("charts")
        }

        tabTrade.setOnClickListener {
            showFragment("trade")
            updateBottomNavigationUI("trade")
        }

        tabHistory.setOnClickListener {
            showFragment("history")
            updateBottomNavigationUI("history")
        }

        tabMessages.setOnClickListener {
            showFragment("messages")
            updateBottomNavigationUI("messages")
        }

        updateBottomNavigationUI("trade")
    }

    private fun updateBottomNavigationUI(activeTab: String) {
        setTabInactive(tabQuotes)
        setTabInactive(tabCharts)
        setTabInactive(tabTrade)
        setTabInactive(tabHistory)
        setTabInactive(tabMessages)

        when (activeTab) {
            "quotes" -> setTabActive(tabQuotes)
            "charts" -> setTabActive(tabCharts)
            "trade" -> setTabActive(tabTrade)
            "history" -> setTabActive(tabHistory)
            "messages" -> setTabActive(tabMessages)
        }
    }

    private fun setTabActive(tabView: View) {
        val accentColor = ContextCompat.getColor(this, R.color.accent_active)
        val blueColor = ContextCompat.getColor(this, R.color.blue)

        val imageView = getTabImageView(tabView)
        val textView = getTabTextView(tabView)

        imageView?.setColorFilter(blueColor)
        textView?.setTextColor(blueColor)
        textView?.setTypeface(null, android.graphics.Typeface.BOLD)

        val iconSize = 24
        val circleSize = (iconSize * resources.displayMetrics.density * 3.6).toInt()

        val circularDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accentColor)
            setSize(circleSize, circleSize)
        }

        tabView.background = circularDrawable

        tabView.postDelayed({
            val alphaAnimator = ObjectAnimator.ofInt(
                circularDrawable,
                "alpha",
                255,
                0
            ).apply {
                duration = 300
                addUpdateListener {
                    tabView.invalidate()
                }
            }
            alphaAnimator.start()

            tabView.postDelayed({
                tabView.background = null
                textView?.setTypeface(null, android.graphics.Typeface.BOLD)
            }, 150)
        }, 150)
    }

    private fun setTabInactive(tabView: View) {
        val grayColor = ContextCompat.getColor(this, R.color.black)

        val imageView = getTabImageView(tabView)
        val textView = getTabTextView(tabView)

        imageView?.setColorFilter(grayColor)
        textView?.setTextColor(grayColor)
        textView?.setTypeface(null, android.graphics.Typeface.NORMAL)

        tabView.background = null
    }

    private fun getTabImageView(tabView: View): ImageView? {
        return when (tabView.id) {
            R.id.tab_quotes -> tabView.findViewById(R.id.tab_quotes_icon)
            R.id.tab_charts -> tabView.findViewById(R.id.tab_charts_icon)
            R.id.tab_trade -> tabView.findViewById(R.id.tab_trade_icon)
            R.id.tab_history -> tabView.findViewById(R.id.tab_history_icon)
            R.id.tab_messages -> tabView.findViewById(R.id.tab_messages_icon)
            else -> null
        }
    }

    private fun getTabTextView(tabView: View): TextView? {
        return when (tabView.id) {
            R.id.tab_quotes -> tabView.findViewById(R.id.tab_quotes_text)
            R.id.tab_charts -> tabView.findViewById(R.id.tab_charts_text)
            R.id.tab_trade -> tabView.findViewById(R.id.tab_trade_text)
            R.id.tab_history -> tabView.findViewById(R.id.tab_history_text)
            R.id.tab_messages -> tabView.findViewById(R.id.tab_messages_text)
            else -> null
        }
    }

    /**
     * Initialize all fragments once and add them to the container
     * All fragments are hidden except the default (trade)
     */
    private fun initializeFragments() {
        quotesFragment = QuotesFragment()
        chartsFragment = ChartsFragment()
        tradeFragment = TradeFragment()
        historyFragment = HistoryFragment()
        messagesFragment = MessagesFragment()

        val transaction = supportFragmentManager.beginTransaction()

        // Add all fragments but hide them initially
        transaction.add(R.id.fragment_container, quotesFragment!!, "quotes").hide(quotesFragment!!)
        transaction.add(R.id.fragment_container, chartsFragment!!, "charts").hide(chartsFragment!!)
        transaction.add(R.id.fragment_container, historyFragment!!, "history").hide(historyFragment!!)
        transaction.add(R.id.fragment_container, messagesFragment!!, "messages").hide(messagesFragment!!)
        // Trade fragment is shown by default
        transaction.add(R.id.fragment_container, tradeFragment!!, "trade")

        transaction.commit()

        activeFragment = tradeFragment
        currentFragmentTag = "trade"

        Log.d("MainActivity", "✓ All fragments initialized with hide/show strategy")
    }

    /**
     * Show fragment using hide/show for instant switching (no recreation)
     */
    private fun showFragment(tag: String) {
        val targetFragment: Fragment? = when (tag) {
            "quotes" -> quotesFragment
            "charts" -> chartsFragment
            "trade" -> tradeFragment
            "history" -> historyFragment
            "messages" -> messagesFragment
            else -> null
        }

        if (targetFragment == null || targetFragment == activeFragment) {
            return
        }

        val transaction = supportFragmentManager.beginTransaction()

        // Hide current active fragment
        activeFragment?.let { transaction.hide(it) }

        // Also hide AccountsFragment if it exists (non-bottom-nav fragment)
        supportFragmentManager.findFragmentByTag("accounts")?.let { accountsFrag ->
            if (accountsFrag.isVisible) {
                transaction.hide(accountsFrag)
            }
        }

        // Show target fragment
        transaction.show(targetFragment)
        transaction.commit()

        activeFragment = targetFragment
        currentFragmentTag = tag
        updateMenuIconVisibility(tag)

        Log.d("MainActivity", "⚡ Switched to fragment: $tag (instant)")
    }

    /**
     * Legacy loadFragment for non-bottom-nav fragments (e.g., AccountsFragment)
     * Uses add/show since these are not part of the main navigation
     */
    fun loadFragment(fragment: Fragment, tag: String) {
        // Hide all bottom nav fragments
        val transaction = supportFragmentManager.beginTransaction()
        quotesFragment?.let { transaction.hide(it) }
        chartsFragment?.let { transaction.hide(it) }
        tradeFragment?.let { transaction.hide(it) }
        historyFragment?.let { transaction.hide(it) }
        messagesFragment?.let { transaction.hide(it) }

        // Add or show the new fragment
        val existingFragment = supportFragmentManager.findFragmentByTag(tag)
        if (existingFragment != null) {
            transaction.show(existingFragment)
        } else {
            transaction.add(R.id.fragment_container, fragment, tag)
        }

        transaction.commit()

        activeFragment = fragment
        currentFragmentTag = tag
        updateMenuIconVisibility(tag)

        Log.d("MainActivity", "Loaded fragment: $tag")
    }

    private fun updateMenuIconVisibility(fragmentTag: String) {
        when (fragmentTag) {
            "trade", "history", "accounts" -> {
                menuIcon.visibility = View.VISIBLE
                Log.d("MainActivity", "Menu icon shown for $fragmentTag")
            }
            "quotes", "charts", "messages" -> {
                menuIcon.visibility = View.GONE
                Log.d("MainActivity", "Menu icon hidden for $fragmentTag")
            }
        }
    }

    fun navigateToTradeFragment() {
        showFragment("trade")
        updateBottomNavigationUI("trade")
        Log.d("MainActivity", "Navigated to Trade fragment")
    }

    fun hideMenuIcon() {
        menuIcon.visibility = View.GONE
        Log.d("MainActivity", "Menu icon hidden")
    }

    fun showMenuIcon() {
        if (currentFragmentTag in listOf("trade", "history", "accounts")) {
            menuIcon.visibility = View.VISIBLE
            Log.d("MainActivity", "Menu icon shown")
        }
    }

    private fun testNetworkConnection() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val testRequest = Request.Builder()
                    .url(BASE_URL)
                    .build()

                okHttpClient.newCall(testRequest).execute().use { response ->
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            Log.d("MainActivity", "✓ Successfully connected to server")
                            Toast.makeText(
                                this@MainActivity,
                                "Connected to MT5 Simulator",
                                Toast.LENGTH_SHORT
                            ).show()
                            isConnected = true
                        } else {
                            Log.w("MainActivity", "Server error: ${response.code}")
                            showConnectionError("Server error: ${response.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("MainActivity", "Cannot reach server", e)
                    showConnectionError("Cannot connect to server\nCheck IP address and network")
                }
            }
        }
    }

    private fun showConnectionError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onStart() {
        super.onStart()
        if (webSocket == null && isConnected) {
            openWebSocket()
        }
    }

    override fun onStop() {
        super.onStop()
        closeWebSocket()
        stopSummaryPolling()
        stopTradesPolling()
        stopHistoryPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeWebSocket()
        stopSummaryPolling()
        stopTradesPolling()
        stopHistoryPolling()
        switchTimeoutJob?.cancel()
        accountDataCache.clear()
    }

    /**
     * OPTIMIZED: Network client with reduced timeouts and connection pooling
     */
    private fun provideOkHttp(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(5, TimeUnit.SECONDS) // Reduced from 15s
            .readTimeout(10, TimeUnit.SECONDS)   // Reduced from 20s
            .writeTimeout(10, TimeUnit.SECONDS)  // Reduced from 20s
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES)) // Reuse connections
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) // Prefer HTTP/2
            .build()
    }

    private fun provideApi(): Mt5SimApi {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(Mt5SimApi::class.java)
    }

    // Cache to Room database for persistence (async, non-blocking)
    private suspend fun cacheToRoom(accountType: String) {
        withContext(Dispatchers.IO) {
            try {
                val cachedData = accountDataCache[accountType] ?: return@withContext

                val dao = TradeDatabase.getDatabase(this@MainActivity).tradeCacheDao()
                val entities = cachedData.historySummary.all_trades
                    .filter { it.status in listOf("COMPLETED", "STOPPED") }
                    .map { it.toCacheEntity(accountType) }

                dao.clearCacheForAccount(accountType)
                dao.insertTrades(entities)

                Log.d("MainActivity", "✓ Cached ${entities.size} trades to Room for $accountType")
            } catch (e: Exception) {
                Log.e("MainActivity", "⚠️ Error caching to Room", e)
            }
        }
    }

    // Get cached data instantly
    fun getCachedAccountData(accountType: String): AccountData? {
        return accountDataCache[accountType]
    }

    fun startSummaryPolling(intervalMs: Long = 1_000L) {
        stopSummaryPolling()
        pollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val accountType = currentAccount
                    val metrics = api.getAccountMetrics()

                    if (accountType == currentAccount) {
                        Log.d("MainActivity", "Polling update ($accountType): Balance=${metrics.balance}, Deposit=${metrics.deposit}")

                        // Update cache
                        accountDataCache[accountType]?.let { cachedData ->
                            accountDataCache[accountType] = cachedData.copy(
                                metrics = metrics,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }

                        withContext(Dispatchers.Main) {
                            broadcastMetricsUpdate(metrics, accountType)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Polling error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopSummaryPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun startTradesPolling(intervalMs: Long = 1_000L) {
        stopTradesPolling()
        tradesPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val accountType = currentAccount
                    val trades = api.getActiveTrades()

                    if (accountType == currentAccount) {
                        Log.d("MainActivity", "Fetched ${trades.size} active trades ($accountType)")

                        // Update cache
                        accountDataCache[accountType]?.let { cachedData ->
                            accountDataCache[accountType] = cachedData.copy(
                                activeTrades = trades,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }

                        withContext(Dispatchers.Main) {
                            broadcastTradesUpdate(trades, accountType)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "Trades polling error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopTradesPolling() {
        tradesPollingJob?.cancel()
        tradesPollingJob = null
    }

    private fun startHistoryPolling(intervalMs: Long = 2_000L) {
        stopHistoryPolling()
        historyPollingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val accountType = currentAccount
                    val history = api.getTradesSummary()

                    if (accountType == currentAccount && history.account_type == accountType) {
                        Log.d("MainActivity", "History update ($accountType): ${history.all_trades.size} trades")

                        // Trim history before caching
                        val trimmedHistory = trimHistoryForCache(history, accountType)

                        // Update cache
                        accountDataCache[accountType]?.let { cachedData ->
                            accountDataCache[accountType] = cachedData.copy(
                                historySummary = trimmedHistory,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }

                        // Cache to Room in background (non-blocking)
                        launch {
                            cacheToRoom(accountType)
                        }

                        withContext(Dispatchers.Main) {
                            broadcastHistoryUpdate(trimmedHistory, accountType)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "History polling error: ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private fun stopHistoryPolling() {
        historyPollingJob?.cancel()
        historyPollingJob = null
    }

    private fun wsUrlFromBase(): String {
        val wsUrl = BASE_URL.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")
        val base = if (wsUrl.endsWith("/")) wsUrl else "$wsUrl/"
        return "${base}ws/android-client"
    }

    private fun openWebSocket() {
        closeWebSocket()

        val wsUrl = wsUrlFromBase()
        Log.d("MainActivity", "Opening WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("MainActivity", "✓ WebSocket connected")
                val ping = JsonObject().apply {
                    addProperty("type", "get_account_status")
                }
                webSocket.send(ping.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = gson.fromJson(text, JsonObject::class.java)
                    val type = json.get("type")?.asString ?: ""
                    val messageAccountType = json.get("account_type")?.asString

                    when (type) {
                        "price_update", "account_status" -> {
                            if (messageAccountType == currentAccount) {
                                val account = json.getAsJsonObject("account_metrics")
                                if (account != null) {
                                    val metrics = gson.fromJson(account, AccountMetrics::class.java)

                                    // Update cache
                                    accountDataCache[currentAccount]?.let { cachedData ->
                                        accountDataCache[currentAccount] = cachedData.copy(
                                            metrics = metrics,
                                            lastUpdated = System.currentTimeMillis()
                                        )
                                    }

                                    runOnUiThread {
                                        broadcastMetricsUpdate(metrics, currentAccount)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("MainActivity", "WS parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("MainActivity", "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect(delayMs: Long = 5_000L) {
        wsReconnectJob?.cancel()
        wsReconnectJob = lifecycleScope.launch {
            delay(delayMs)
            if (isConnected) {
                openWebSocket()
            }
        }
    }

    private fun closeWebSocket() {
        wsReconnectJob?.cancel()
        wsReconnectJob = null
        webSocket?.close(1000, null)
        webSocket = null
    }

    private fun broadcastMetricsUpdate(metrics: AccountMetrics, accountType: String) {
        if (accountType != currentAccount) return

        // Update TradeFragment if it exists (always update even if hidden for cache)
        tradeFragment?.let {
            if (it.isAdded) it.updateMetrics(metrics)
        }
        // Update HistoryFragment deposit if it exists
        historyFragment?.let {
            if (it.isAdded) it.updateDeposit(metrics.deposit)
        }
    }

    private fun broadcastTradesUpdate(trades: List<TradeData>, accountType: String) {
        if (accountType != currentAccount) return

        // Update TradeFragment if it exists
        tradeFragment?.let {
            if (it.isAdded) it.updateTrades(trades)
        }
    }

    private fun broadcastHistoryUpdate(history: TradesSummaryResponse, accountType: String) {
        if (accountType != currentAccount) return

        // Update HistoryFragment if it exists
        historyFragment?.let {
            if (it.isAdded) it.updateHistoryData(history)
        }
    }

    /**
     * OPTIMIZED: Account switching with lazy loading and instant cache retrieval
     */
    suspend fun switchAccount(accountType: String): SwitchAccountResponse {
        return withContext(Dispatchers.IO) {
            if (!isSwitchingAccount.compareAndSet(false, true)) {
                throw IllegalStateException("Account switch already in progress")
            }

            switchTimeoutJob?.cancel()
            switchTimeoutJob = lifecycleScope.launch {
                delay(10_000)
                if (isSwitchingAccount.get()) {
                    Log.e("MainActivity", "⚠️ Switch timeout - force releasing lock")
                    isSwitchingAccount.set(false)
                }
            }

            val oldAccount = currentAccount

            try {
                Log.d("MainActivity", "=== ACCOUNT SWITCH: $oldAccount -> $accountType ===")

                // Step 1: Stop polling temporarily
                withContext(Dispatchers.Main) {
                    stopSummaryPolling()
                    stopTradesPolling()
                    stopHistoryPolling()
                }

                delay(100) // Minimal delay

                // Step 2: Switch backend account
                val response = api.switchAccount(SwitchAccountRequest(accountType))
                Log.d("MainActivity", "✓ Backend switched: ${response.message}")

                // Step 3: Update current account
                currentAccount = accountType

                // Step 4: Check cache, lazy-load if not present
                val cachedData = accountDataCache[accountType]
                if (cachedData == null) {
                    Log.d("MainActivity", "⟳ Account not cached, lazy-loading $accountType...")

                    // Parallel fetch for speed
                    val metrics = async { api.getAccountMetrics() }
                    val trades = async { api.getActiveTrades() }
                    val history = async { api.getTradesSummary() }

                    val newAccountData = AccountData(
                        metrics = metrics.await(),
                        activeTrades = trades.await(),
                        historySummary = trimHistoryForCache(history.await(), accountType),
                        lastUpdated = System.currentTimeMillis()
                    )

                    accountDataCache[accountType] = newAccountData

                    // Async cache to Room
                    launch { cacheToRoom(accountType) }
                } else {
                    Log.d("MainActivity", "✓ Using cached data for $accountType (age: ${System.currentTimeMillis() - cachedData.lastUpdated}ms)")
                }

                // Step 5: Restart polling for new account
                withContext(Dispatchers.Main) {
                    startSummaryPolling()
                    startTradesPolling()
                    startHistoryPolling()
                }

                // Step 6: Broadcast cached data INSTANTLY to fragments
                val finalCachedData = accountDataCache[accountType]!!
                withContext(Dispatchers.Main) {
                    broadcastMetricsUpdate(finalCachedData.metrics, accountType)
                    broadcastTradesUpdate(finalCachedData.activeTrades, accountType)
                    broadcastHistoryUpdate(finalCachedData.historySummary, accountType)
                }

                Log.d("MainActivity", "=== SWITCH COMPLETED: Now on $accountType (${if (cachedData != null) "INSTANT" else "LOADED"}) ===")
                response

            } catch (e: Exception) {
                currentAccount = oldAccount
                Log.e("MainActivity", "❌ Switch failed, reverted to $oldAccount", e)

                withContext(Dispatchers.Main) {
                    startSummaryPolling()
                    startTradesPolling()
                    startHistoryPolling()
                }

                throw e
            } finally {
                switchTimeoutJob?.cancel()
                isSwitchingAccount.set(false)
            }
        }
    }
}

// API Interface
interface Mt5SimApi {
    @GET("api/account-metrics")
    suspend fun getAccountMetrics(): AccountMetrics

    @GET("api/trades/active")
    suspend fun getActiveTrades(): List<TradeData>

    @GET("api/summary/trades")
    suspend fun getTradesSummary(): TradesSummaryResponse

    @POST("api/switch-account")
    suspend fun switchAccount(@Body request: SwitchAccountRequest): SwitchAccountResponse

    @GET("api/accounts/list")
    suspend fun listAccounts(): AccountsList

    @GET("api/profile/image/{category}")
    suspend fun getProfileImage(@Path("category") category: String): ProfileImageResponse
}

// Data Classes
data class AccountMetrics(
    val balance: Double = 10000.0,
    val equity: Double = 10000.0,
    val margin: Double = 0.0,
    val free_margin: Double = 10000.0,
    val margin_level: Double = 0.0,
    val profit: Double = 0.0,
    val total_swap: Double = 0.0,
    val total_profit_loss: Double = 0.0,
    val deposit: Double = 0.0  // ADDED: Deposit field from backend
)

data class TradeData(
    val trade_id: String,
    val symbol: String,
    val entry_price: Double,
    val current_buy_price: Double,
    val current_sell_price: Double,
    val start_time: String,
    val end_time: String? = null,
    val status: String = "RUNNING",
    val target_price: Double,
    val target_type: String,
    val target_amount: Double,
    val lot_size: Double,
    val trade_direction: String,
    val profit_loss: Double = 0.0,
    val margin_used: Double = 0.0,
    val swap: Double = 0.0,
    val commission: Double = 0.0,
    val bias_factor: Double = 0.0,
    val closing_price: Double? = null
)

data class TradesSummaryResponse(
    val account_type: String,
    val trades_summary: TradesSummary,
    val financial_summary: FinancialSummary,
    val all_trades: List<TradeData>,
    val message: String
)

data class TradesSummary(
    val total_trades: Int,
    val running_trades: Int,
    val completed_trades: Int,
    val stopped_trades: Int,
    val win_rate_percentage: Double,
    val profitable_trades: Int,
    val losing_trades: Int
)

data class FinancialSummary(
    val total_realized_pnl: Double,
    val current_unrealized_pnl: Double,
    val total_swap_fees: Double,
    val account_balance: Double,
    val account_equity: Double,
    val free_margin: Double,
    val margin_level_percentage: Double
)

data class AccountsList(
    val accounts: Map<String, AccountInfo>,
    val current_account: String,
    val message: String
)

data class AccountInfo(
    val balance: Double,
    val equity: Double,
    val active_trades: Int,
    val margin: Double,
    val free_margin: Double
)

data class SwitchAccountRequest(
    val account_type: String
)

data class SwitchAccountResponse(
    val status: String,
    val message: String,
    val old_account: String,
    val new_account: String,
    val account_metrics: AccountMetrics
)

data class ProfileImageResponse(
    val status: String,
    val category: String,
    val image_id: String,
    val content_type: String,
    val image_data: String,
    val uploaded_at: String
)