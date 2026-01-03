package com.example.accountinfo

import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.*

// Room Entity for Trade Data with INDICES for faster queries
@Entity(
    tableName = "trades_cache",
    indices = [Index(value = ["account_type", "end_time"])]
)
data class TradeCacheEntity(
    @PrimaryKey val trade_id: String,
    val symbol: String,
    val entry_price: Double,
    val current_buy_price: Double,
    val current_sell_price: Double,
    val start_time: String,
    val end_time: String?,
    val status: String,
    val target_price: Double,
    val target_type: String,
    val target_amount: Double,
    val lot_size: Double,
    val trade_direction: String,
    val profit_loss: Double,
    val margin_used: Double,
    val swap: Double,
    val commission: Double,
    val bias_factor: Double,
    val account_type: String = "Fast/Acc",
    val cached_at: Long = System.currentTimeMillis()
)

// Room DAO for Trade Cache
@Dao
interface TradeCacheDao {
    @Query("SELECT * FROM trades_cache WHERE status IN ('COMPLETED', 'STOPPED') AND account_type = :accountType")
    suspend fun getCompletedTradesByAccount(accountType: String): List<TradeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrades(trades: List<TradeCacheEntity>)

    @Query("DELETE FROM trades_cache WHERE account_type = :accountType")
    suspend fun clearCacheForAccount(accountType: String)

    @Query("SELECT COUNT(*) FROM trades_cache WHERE account_type = :accountType")
    suspend fun getCacheCountForAccount(accountType: String): Int
}

// Room Database - VERSION 3
@Database(entities = [TradeCacheEntity::class], version = 3, exportSchema = false)
abstract class TradeDatabase : RoomDatabase() {
    abstract fun tradeCacheDao(): TradeCacheDao

    companion object {
        @Volatile
        private var INSTANCE: TradeDatabase? = null

        fun getDatabase(context: android.content.Context): TradeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TradeDatabase::class.java,
                    "trade_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class HistoryFragment : Fragment() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabLayout: TabLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var tvProfit: TextView
    private lateinit var tvDeposit: TextView
    private lateinit var tvSwap: TextView
    private lateinit var tvCommission: TextView
    private lateinit var tvBalance: TextView
    private lateinit var adapter: HistoryAdapter

    private var currentTab = 0
    private var allTrades: List<TradeData> = emptyList()
    private var currentAccount = "Fast/Acc"

    // OPTIMIZATION: Cached filtered list to avoid redundant filtering
    private var cachedFilteredList: List<TradeData> = emptyList()
    private var lastFilteredTab = -1

    private val api: Mt5SimApi by lazy { (requireActivity() as MainActivity).api }
    private val tradeDao: TradeCacheDao by lazy {
        TradeDatabase.getDatabase(requireContext()).tradeCacheDao()
    }

    // PERFORMANCE: Use java.time for 2x faster date parsing
    private val dateFormatters = ThreadLocal.withInitial {
        listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        )
    }

    private val outputFormatter = ThreadLocal.withInitial {
        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss")
    }

    // CRITICAL OPTIMIZATION: Bounded LRU cache for formatted dates (max 50 entries)
    private val visibleDateCache = LruCache<String, String>(50)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val fragmentLoadStart = System.currentTimeMillis()

        initializeViews(view)
        setupRecyclerView()
        setupTabLayout()

        currentAccount = (requireActivity() as MainActivity).currentAccount
        Log.d("HistoryFragment", "🚀 Fragment initialized for account: $currentAccount")

        // CRITICAL: INSTANT LOAD from MainActivity memory cache (0-5ms)
        loadDataInstantlyFromMemoryCache()

        val sideMenuImage: ImageView? = view.findViewById(R.id.side_menu_image)
        sideMenuImage?.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, AccountsFragment())
                .addToBackStack("accounts")
                .commit()
        }

        val fragmentLoadEnd = System.currentTimeMillis()
        Log.d("HistoryFragment", "⚡ Fragment ready in ${fragmentLoadEnd - fragmentLoadStart}ms")
    }

    private fun initializeViews(view: View) {
        toolbar = view.findViewById(R.id.toolbar)
        tabLayout = view.findViewById(R.id.tab_layout)
        recyclerView = view.findViewById(R.id.history_recycler_view)
        tvProfit = view.findViewById(R.id.value_profit)
        tvDeposit = view.findViewById(R.id.value_deposit)
        tvSwap = view.findViewById(R.id.value_swap)
        tvCommission = view.findViewById(R.id.value_commission)
        tvBalance = view.findViewById(R.id.value_balance)

        toolbar.navigationIcon = null
        toolbar.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.white))
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter()
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        // OPTIMIZATION: Disable item animations for faster initial render
        recyclerView.itemAnimator = null

        // OPTIMIZATION: Fixed size for better performance
        recyclerView.setHasFixedSize(true)

        // OPTIMIZATION: Increase recycled view pool
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 20)
    }

    private fun setupTabLayout() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                filterAndDisplayTrades()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    /**
     * CRITICAL OPTIMIZATION: Load data INSTANTLY from MainActivity memory cache
     * This eliminates ALL lag - data is already in memory and in backend order
     */
    private fun loadDataInstantlyFromMemoryCache() {
        val loadStart = System.currentTimeMillis()

        try {
            // STEP 1: Get data from MainActivity memory cache (INSTANT - 0-2ms)
            val mainActivity = requireActivity() as MainActivity
            val cachedData = mainActivity.getCachedAccountData(currentAccount)

            if (cachedData != null) {
                Log.d("HistoryFragment", "✓ Using MainActivity memory cache for $currentAccount")

                // Data is in BACKEND ORDER - display as-is with NO sorting
                allTrades = cachedData.historySummary.all_trades

                // Display immediately on main thread (5-10ms)
                filterAndDisplayTrades()

                // UPDATED: Pass the full cached data to get deposit from metrics
                updateSummarySection(cachedData.historySummary.financial_summary, cachedData.metrics.deposit)

                val loadEnd = System.currentTimeMillis()
                Log.d("HistoryFragment", "⚡ INSTANT load complete in ${loadEnd - loadStart}ms")

                return
            }

            // FALLBACK: If memory cache unavailable (shouldn't happen after MainActivity preload)
            Log.w("HistoryFragment", "⚠️ Memory cache miss - loading from Room/API")
            loadDataWithFallback()

        } catch (e: Exception) {
            Log.e("HistoryFragment", "Error loading from memory cache", e)
            loadDataWithFallback()
        }
    }

    /**
     * FALLBACK: Only used if MainActivity memory cache is unavailable
     * This should NEVER happen after the MainActivity preload completes
     */
    private fun loadDataWithFallback() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("HistoryFragment", "Loading from Room cache...")

                // Try Room cache
                val roomData = withContext(Dispatchers.IO) {
                    loadFromRoomCache()
                }

                if (roomData != null && roomData.isNotEmpty()) {
                    allTrades = roomData
                    filterAndDisplayTrades()

                    // Fetch metrics to get deposit
                    val metrics = withContext(Dispatchers.IO) {
                        try {
                            api.getAccountMetrics()
                        } catch (e: Exception) {
                            Log.e("HistoryFragment", "Error fetching metrics", e)
                            null
                        }
                    }

                    val deposit = metrics?.deposit ?: 0.0
                    updateSummarySection(calculateSummaryFromTrades(roomData), deposit)
                    Log.d("HistoryFragment", "✓ Loaded ${roomData.size} trades from Room")
                    return@launch
                }

                // Last resort: Fetch from API
                Log.d("HistoryFragment", "Fetching from API...")
                val apiData = withContext(Dispatchers.IO) {
                    fetchFromApi()
                }

                if (apiData != null && apiData.account_type == currentAccount) {
                    allTrades = apiData.all_trades
                    filterAndDisplayTrades()

                    // Fetch metrics to get deposit
                    val metrics = withContext(Dispatchers.IO) {
                        try {
                            api.getAccountMetrics()
                        } catch (e: Exception) {
                            Log.e("HistoryFragment", "Error fetching metrics", e)
                            null
                        }
                    }

                    val deposit = metrics?.deposit ?: 0.0
                    updateSummarySection(apiData.financial_summary, deposit)

                    // Cache for next time
                    launch(Dispatchers.IO) {
                        cacheTrades(apiData.all_trades, currentAccount)
                    }
                }

            } catch (e: Exception) {
                Log.e("HistoryFragment", "Error in fallback load", e)
                showError("Failed to load history: ${e.message}")
            }
        }
    }

    private suspend fun loadFromRoomCache(): List<TradeData>? {
        return try {
            val cached = tradeDao.getCompletedTradesByAccount(currentAccount)

            if (cached.isNotEmpty()) {
                Log.d("HistoryFragment", "Found ${cached.size} trades in Room cache")
                cached.map { it.toTradeData() }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("HistoryFragment", "Room cache error: ${e.message}")
            null
        }
    }

    private suspend fun fetchFromApi(): TradesSummaryResponse? {
        return try {
            Log.d("HistoryFragment", "Fetching from API for $currentAccount...")
            val summary = api.getTradesSummary()

            if (summary.account_type != currentAccount) {
                Log.w("HistoryFragment", "API returned wrong account: ${summary.account_type}")
                return null
            }

            Log.d("HistoryFragment", "API returned ${summary.all_trades.size} trades")
            summary
        } catch (e: Exception) {
            Log.e("HistoryFragment", "API error: ${e.message}")
            null
        }
    }

    private suspend fun cacheTrades(trades: List<TradeData>, accountType: String) {
        try {
            val entities = trades
                .filter { it.status in listOf("COMPLETED", "STOPPED") }
                .map { it.toCacheEntity(accountType) }

            tradeDao.clearCacheForAccount(accountType)
            tradeDao.insertTrades(entities)

            Log.d("HistoryFragment", "✓ Cached ${entities.size} trades for $accountType")
        } catch (e: Exception) {
            Log.e("HistoryFragment", "Cache error: ${e.message}")
        }
    }

    /**
     * Called by MainActivity when polling updates history
     * OPTIMIZED: Only updates if data actually changed (skip unnecessary filtering)
     */
    fun updateHistoryData(summary: TradesSummaryResponse) {
        if (summary.account_type != currentAccount) return

        val updateStart = System.currentTimeMillis()

        // Quick check: Has data actually changed?
        if (allTrades.size == summary.all_trades.size) {
            // Check if first and last trades are same (quick comparison)
            val firstMatch = allTrades.firstOrNull()?.trade_id == summary.all_trades.firstOrNull()?.trade_id
            val lastMatch = allTrades.lastOrNull()?.trade_id == summary.all_trades.lastOrNull()?.trade_id

            if (firstMatch && lastMatch) {
                Log.d("HistoryFragment", "⊘ History data unchanged, skipping update")
                return
            }
        }

        Log.d("HistoryFragment", "⟳ Updating history: ${summary.all_trades.size} trades")

        // Update data
        allTrades = summary.all_trades

        // CRITICAL: Invalidate cached filtered list to force re-filter
        lastFilteredTab = -1

        // Update immediately (data is in backend order - display as-is with NO sorting)
        filterAndDisplayTrades()

        // Fetch current deposit from MainActivity cache
        val mainActivity = requireActivity() as MainActivity
        val cachedData = mainActivity.getCachedAccountData(currentAccount)
        val deposit = cachedData?.metrics?.deposit ?: 0.0

        updateSummarySection(summary.financial_summary, deposit)

        // Cache in background
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            cacheTrades(summary.all_trades, currentAccount)
        }

        val updateEnd = System.currentTimeMillis()
        Log.d("HistoryFragment", "⚡ History updated in ${updateEnd - updateStart}ms")
    }

    /**
     * Called by MainActivity when metrics are updated
     * Updates the deposit value in real-time
     */
    fun updateDeposit(deposit: Double) {
        tvDeposit.text = String.format("%.2f", deposit)
        Log.d("HistoryFragment", "✓ Deposit updated: $deposit")
    }

    private fun calculateSummaryFromTrades(trades: List<TradeData>): FinancialSummary {
        val totalPnL = trades.sumOf { it.profit_loss }
        val totalSwap = trades.sumOf { it.swap }

        return FinancialSummary(
            total_realized_pnl = totalPnL,
            current_unrealized_pnl = 0.0,
            total_swap_fees = totalSwap,
            account_balance = 10000.0 + totalPnL,
            account_equity = 10000.0 + totalPnL,
            free_margin = 10000.0 + totalPnL,
            margin_level_percentage = 0.0
        )
    }

    /**
     * CRITICAL OPTIMIZATION: Smart filtering with caching
     * Only re-filters if tab changed or data changed
     */
    private fun filterAndDisplayTrades() {
        val filterStart = System.currentTimeMillis()

        // OPTIMIZATION: Skip filtering if tab hasn't changed and we have cached result
        if (currentTab == lastFilteredTab && cachedFilteredList.isNotEmpty()) {
            Log.d("HistoryFragment", "⊘ Using cached filtered list (${cachedFilteredList.size} items)")
            adapter.submitList(cachedFilteredList)
            return
        }

        // Filter only when necessary
        val filteredTrades = when (currentTab) {
            0 -> allTrades.filter { it.status in listOf("COMPLETED", "STOPPED") }
            1 -> emptyList() // Orders tab (not implemented)
            2 -> emptyList() // Deals tab (not implemented)
            else -> emptyList()
        }

        // Cache the filtered result
        cachedFilteredList = filteredTrades
        lastFilteredTab = currentTab

        // AsyncListDiffer handles DiffUtil off main thread
        adapter.submitList(filteredTrades)

        val filterEnd = System.currentTimeMillis()
        Log.d("HistoryFragment", "⚡ Filtered ${filteredTrades.size} trades in ${filterEnd - filterStart}ms")
    }

    /**
     * UPDATED: Now accepts deposit parameter from AccountMetrics
     */
    private fun updateSummarySection(financialSummary: FinancialSummary, deposit: Double) {
        tvProfit.text = String.format("%.2f", financialSummary.total_realized_pnl)
        tvProfit.setTextColor(
            if (financialSummary.total_realized_pnl >= 0)
                ContextCompat.getColor(requireContext(), R.color.accent_active)
            else
                ContextCompat.getColor(requireContext(), R.color.loss_negative)
        )

        // UPDATED: Display actual deposit from AccountMetrics
        tvDeposit.text = String.format("%.2f", deposit)
        Log.d("HistoryFragment", "✓ Deposit displayed: $deposit")

        tvSwap.text = String.format("%.2f", financialSummary.total_swap_fees)

        val totalCommission = allTrades
            .filter { it.status in listOf("COMPLETED", "STOPPED") }
            .sumOf { it.commission }
        tvCommission.text = String.format("%.2f", totalCommission)

        tvBalance.text = String.format("%.2f", financialSummary.account_balance)
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        val newAccount = (requireActivity() as MainActivity).currentAccount

        if (newAccount != currentAccount) {
            Log.d("HistoryFragment", "Account changed: $currentAccount -> $newAccount")
            currentAccount = newAccount

            // Clear cached filtered list on account change
            lastFilteredTab = -1
            cachedFilteredList = emptyList()

            // Clear date cache on account change
            visibleDateCache.evictAll()

            // INSTANT reload from MainActivity cache
            loadDataInstantlyFromMemoryCache()
        }
    }

    /**
     * CRITICAL OPTIMIZATION: Format datetime with bounded LRU cache
     * Maximum 50 entries to prevent unbounded memory growth
     */
    private fun formatDateTime(dateTimeString: String?): String {
        if (dateTimeString.isNullOrEmpty()) return ""

        // Check LRU cache first (instant!)
        visibleDateCache.get(dateTimeString)?.let { return it }

        return try {
            var parsedDateTime: LocalDateTime? = null

            for (formatter in dateFormatters.get()!!) {
                try {
                    parsedDateTime = LocalDateTime.parse(dateTimeString, formatter)
                    if (parsedDateTime != null) break
                } catch (e: Exception) {
                    continue
                }
            }

            val formatted = if (parsedDateTime != null) {
                outputFormatter.get()!!.format(parsedDateTime)
            } else {
                dateTimeString
            }

            // Cache with automatic LRU eviction
            visibleDateCache.put(dateTimeString, formatted)
            formatted
        } catch (e: Exception) {
            Log.e("HistoryFragment", "Error formatting datetime: ${e.message}")
            dateTimeString
        }
    }

    /**
     * CRITICAL OPTIMIZATION: RecyclerView.Adapter with AsyncListDiffer
     * DiffUtil calculations happen OFF the main thread for smooth UI
     */
    inner class HistoryAdapter : RecyclerView.Adapter<HistoryAdapter.TradeViewHolder>() {

        // CRITICAL: AsyncListDiffer for off-main-thread diff calculations
        private val differ = AsyncListDiffer(this, TradeDiffCallback())

        private val expandedStates = mutableMapOf<String, Boolean>()

        fun submitList(list: List<TradeData>) {
            differ.submitList(list)
        }

        override fun getItemCount() = differ.currentList.size

        inner class TradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvSymbol: TextView = itemView.findViewById(R.id.tv_trade_symbol)
            private val tvDirection: TextView = itemView.findViewById(R.id.tv_trade_direction)
            private val tvLotSize: TextView = itemView.findViewById(R.id.tv_lot_size)
            private val tvDateTime: TextView = itemView.findViewById(R.id.tv_trade_datetime)
            private val tvOpenPrice: TextView = itemView.findViewById(R.id.tvOpenPrice)
            private val tvClosePrice: TextView = itemView.findViewById(R.id.tvClosePrice)
            private val tvPnL: TextView = itemView.findViewById(R.id.tv_trade_pnl)

            private val expansionLayout: ConstraintLayout = itemView.findViewById(R.id.expansionLayout)
            private val tvSlValue: TextView = itemView.findViewById(R.id.tvSlValue)
            private val tvTpValue: TextView = itemView.findViewById(R.id.tvTpValue)
            private val tvOpenTimeValue: TextView = itemView.findViewById(R.id.tvOpenTimeValue)
            private val tvSwapValue: TextView = itemView.findViewById(R.id.tvSwapValue)
            private val tvIdValue: TextView = itemView.findViewById(R.id.tvIdValue)
            private val tvTradedByValue: TextView = itemView.findViewById(R.id.tvTradedByValue)
            private val tvCommissionValue: TextView = itemView.findViewById(R.id.tvCommissionValue)

            // Pre-cache colors for performance
            private val lossColor = ContextCompat.getColor(itemView.context, R.color.loss_negative)
            private val profitColor = ContextCompat.getColor(itemView.context, R.color.accent_active)

            fun bind(trade: TradeData) {
                tvSymbol.text = trade.symbol

                val direction = trade.trade_direction.uppercase()
                tvDirection.text = direction

                val isSell = trade.trade_direction.lowercase() == "sell"
                val directionColor = if (isSell) lossColor else profitColor

                tvDirection.setTextColor(directionColor)
                tvLotSize.text = String.format("%.2f", trade.lot_size)
                tvLotSize.setTextColor(directionColor)

                tvDateTime.text = formatDateTime(trade.end_time ?: trade.start_time)
                tvOpenPrice.text = String.format("%.5f", trade.entry_price)

                val closePrice = if (trade.trade_direction.uppercase() == "BUY")
                    trade.current_buy_price
                else
                    trade.current_sell_price
                tvClosePrice.text = String.format("%.5f", closePrice)

                tvPnL.text = String.format("%.2f", trade.profit_loss)
                tvPnL.setTextColor(if (trade.profit_loss >= 0) profitColor else lossColor)

                tvSlValue.text = "—"
                tvTpValue.text = String.format("%.5f", trade.target_price)
                tvOpenTimeValue.text = formatDateTime(trade.start_time)
                tvSwapValue.text = String.format("%.2f", trade.swap)
                tvIdValue.text = "#${trade.trade_id}"
                tvTradedByValue.text = "PIPS HUNTER AI"
                tvCommissionValue.text = String.format("%.2f", trade.commission)

                val isExpanded = expandedStates[trade.trade_id] ?: false
                expansionLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE

                itemView.setOnClickListener {
                    toggleExpansion(trade)
                }
            }

            private fun toggleExpansion(trade: TradeData) {
                val isExpanded = expandedStates[trade.trade_id] ?: false
                expandedStates[trade.trade_id] = !isExpanded

                // Get the current position
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TradeViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_completed_trade, parent, false)
            return TradeViewHolder(view)
        }

        override fun onBindViewHolder(holder: TradeViewHolder, position: Int) {
            holder.bind(differ.currentList[position])
        }
    }

    /**
     * DiffUtil callback for efficient RecyclerView updates
     * Used by AsyncListDiffer to calculate diffs off main thread
     */
    private class TradeDiffCallback : DiffUtil.ItemCallback<TradeData>() {
        override fun areItemsTheSame(oldItem: TradeData, newItem: TradeData): Boolean {
            return oldItem.trade_id == newItem.trade_id
        }

        override fun areContentsTheSame(oldItem: TradeData, newItem: TradeData): Boolean {
            // Compare relevant fields for display updates
            return oldItem.trade_id == newItem.trade_id &&
                    oldItem.profit_loss == newItem.profit_loss &&
                    oldItem.status == newItem.status &&
                    oldItem.end_time == newItem.end_time
        }
    }
}

// Extension functions
fun TradeData.toCacheEntity(accountType: String): TradeCacheEntity {
    return TradeCacheEntity(
        trade_id = this.trade_id,
        symbol = this.symbol,
        entry_price = this.entry_price,
        current_buy_price = this.current_buy_price,
        current_sell_price = this.current_sell_price,
        start_time = this.start_time,
        end_time = this.end_time,
        status = this.status,
        target_price = this.target_price,
        target_type = this.target_type,
        target_amount = this.target_amount,
        lot_size = this.lot_size,
        trade_direction = this.trade_direction,
        profit_loss = this.profit_loss,
        margin_used = this.margin_used,
        swap = this.swap,
        commission = this.commission,
        bias_factor = this.bias_factor,
        account_type = accountType
    )
}

fun TradeCacheEntity.toTradeData(): TradeData {
    return TradeData(
        trade_id = this.trade_id,
        symbol = this.symbol,
        entry_price = this.entry_price,
        current_buy_price = this.current_buy_price,
        current_sell_price = this.current_sell_price,
        start_time = this.start_time,
        end_time = this.end_time,
        status = this.status,
        target_price = this.target_price,
        target_type = this.target_type,
        target_amount = this.target_amount,
        lot_size = this.lot_size,
        trade_direction = this.trade_direction,
        profit_loss = this.profit_loss,
        margin_used = this.margin_used,
        swap = this.swap,
        commission = this.commission,
        bias_factor = this.bias_factor
    )
}