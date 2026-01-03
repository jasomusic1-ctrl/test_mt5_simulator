package com.example.accountinfo

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Room Entity for Active Trade Cache
@Entity(tableName = "active_trades_cache")
data class ActiveTradeCacheEntity(
    @PrimaryKey val trade_id: String,
    val account_type: String,
    val symbol: String,
    val entry_price: Double,
    val current_buy_price: Double,
    val current_sell_price: Double,
    val start_time: String,
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
    val cached_at: Long = System.currentTimeMillis()
)

// Room Entity for Account Metrics Cache
@Entity(tableName = "account_metrics_cache")
data class AccountMetricsCacheEntity(
    @PrimaryKey val account_type: String,
    val balance: Double,
    val equity: Double,
    val margin: Double,
    val free_margin: Double,
    val margin_level: Double,
    val profit: Double,
    val total_swap: Double,
    val total_profit_loss: Double,
    val cached_at: Long = System.currentTimeMillis()
)

// Room DAO for Active Trades
@Dao
interface ActiveTradeCacheDao {
    @Query("SELECT * FROM active_trades_cache WHERE status = 'RUNNING' AND account_type = :accountType ORDER BY start_time DESC")
    suspend fun getActiveTradesForAccount(accountType: String): List<ActiveTradeCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrades(trades: List<ActiveTradeCacheEntity>)

    @Query("DELETE FROM active_trades_cache WHERE account_type = :accountType")
    suspend fun clearCacheForAccount(accountType: String)

    @Query("SELECT COUNT(*) FROM active_trades_cache WHERE account_type = :accountType")
    suspend fun getCacheCountForAccount(accountType: String): Int
}

// Room DAO for Account Metrics
@Dao
interface AccountMetricsCacheDao {
    @Query("SELECT * FROM account_metrics_cache WHERE account_type = :accountType LIMIT 1")
    suspend fun getMetricsForAccount(accountType: String): AccountMetricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetrics(metrics: AccountMetricsCacheEntity)

    @Query("DELETE FROM account_metrics_cache WHERE account_type = :accountType")
    suspend fun clearCacheForAccount(accountType: String)
}

// Room Database
@Database(
    entities = [ActiveTradeCacheEntity::class, AccountMetricsCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ActiveTradeDatabase : RoomDatabase() {
    abstract fun activeTradeCacheDao(): ActiveTradeCacheDao
    abstract fun accountMetricsCacheDao(): AccountMetricsCacheDao

    companion object {
        @Volatile
        private var INSTANCE: ActiveTradeDatabase? = null

        fun getDatabase(context: android.content.Context): ActiveTradeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ActiveTradeDatabase::class.java,
                    "active_trade_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class TradeFragment : Fragment() {

    private lateinit var tvPnl: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvEquity: TextView
    private lateinit var tvMargin: TextView
    private lateinit var tvFreeMargin: TextView
    private lateinit var tvMarginLevel: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var tradesAdapter: TradesAdapter

    private var currentAccount: String = "Fast/Acc"

    private val activeTradeCacheDao: ActiveTradeCacheDao by lazy {
        ActiveTradeDatabase.getDatabase(requireContext()).activeTradeCacheDao()
    }

    private val accountMetricsCacheDao: AccountMetricsCacheDao by lazy {
        ActiveTradeDatabase.getDatabase(requireContext()).accountMetricsCacheDao()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_trade, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindViews(view)
        setupRecyclerView()

        currentAccount = (requireActivity() as MainActivity).currentAccount

        // Load with optimization: memory cache -> Room cache -> API
        loadDataWithOptimization()
    }

    private fun bindViews(view: View) {
        tvPnl = view.findViewById(R.id.tv_pnl)
        tvBalance = view.findViewById(R.id.tv_balance)
        tvEquity = view.findViewById(R.id.tv_equity)
        tvMargin = view.findViewById(R.id.tv_margin)
        tvFreeMargin = view.findViewById(R.id.tv_free_margin)
        tvMarginLevel = view.findViewById(R.id.tv_margin_level)
        recyclerView = view.findViewById(R.id.history_recycler_view)

        showPlaceholdersOnError()
    }

    private fun setupRecyclerView() {
        tradesAdapter = TradesAdapter(emptyList())
        recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = tradesAdapter
        }
    }

    private fun loadDataWithOptimization() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // STEP 1: Check memory cache (INSTANT - 0ms)
                val mainActivity = requireActivity() as MainActivity
                val cachedData = mainActivity.getCachedAccountData(currentAccount)

                if (cachedData != null) {
                    Log.d("TradeFragment", "✓ Using memory cache for $currentAccount")
                    updateMetrics(cachedData.metrics)
                    updateTrades(cachedData.activeTrades)

                    // Cache to Room in background
                    launch(Dispatchers.IO) {
                        cacheMetrics(cachedData.metrics, currentAccount)
                        cacheTrades(cachedData.activeTrades, currentAccount)
                    }

                    return@launch // Exit early
                }

                // STEP 2: Try Room cache (FAST - ~30ms)
                val roomMetricsDeferred = async(Dispatchers.IO) {
                    accountMetricsCacheDao.getMetricsForAccount(currentAccount)
                }
                val roomTradesDeferred = async(Dispatchers.IO) {
                    activeTradeCacheDao.getActiveTradesForAccount(currentAccount)
                }

                // STEP 3: Fetch API data in parallel (SLOW - ~300-1000ms)
                val apiMetricsDeferred = async(Dispatchers.IO) {
                    try {
                        mainActivity.api.getAccountMetrics()
                    } catch (e: Exception) {
                        Log.e("TradeFragment", "API metrics error: ${e.message}")
                        null
                    }
                }
                val apiTradesDeferred = async(Dispatchers.IO) {
                    try {
                        mainActivity.api.getActiveTrades()
                    } catch (e: Exception) {
                        Log.e("TradeFragment", "API trades error: ${e.message}")
                        null
                    }
                }

                // Display Room cache first
                val roomMetrics = roomMetricsDeferred.await()
                val roomTrades = roomTradesDeferred.await()

                if (roomMetrics != null) {
                    updateMetrics(roomMetrics.toAccountMetrics())
                    Log.d("TradeFragment", "✓ Displayed Room cached metrics")
                }

                if (roomTrades.isNotEmpty()) {
                    updateTrades(roomTrades.map { it.toTradeData() })
                    Log.d("TradeFragment", "✓ Displayed ${roomTrades.size} Room cached trades")
                }

                // Then update with fresh API data
                val apiMetrics = apiMetricsDeferred.await()
                val apiTrades = apiTradesDeferred.await()

                if (apiMetrics != null) {
                    updateMetrics(apiMetrics)
                    launch(Dispatchers.IO) { cacheMetrics(apiMetrics, currentAccount) }
                    Log.d("TradeFragment", "✓ Updated with fresh metrics")
                }

                if (apiTrades != null) {
                    updateTrades(apiTrades)
                    launch(Dispatchers.IO) { cacheTrades(apiTrades, currentAccount) }
                    Log.d("TradeFragment", "✓ Updated with ${apiTrades.size} fresh trades")
                }

            } catch (e: Exception) {
                Log.e("TradeFragment", "Error loading data: ${e.message}", e)
            }
        }
    }

    private suspend fun cacheMetrics(metrics: AccountMetrics, accountType: String) {
        try {
            accountMetricsCacheDao.insertMetrics(metrics.toCacheEntity(accountType))
        } catch (e: Exception) {
            Log.e("TradeFragment", "Cache metrics error: ${e.message}")
        }
    }

    private suspend fun cacheTrades(trades: List<TradeData>, accountType: String) {
        try {
            val entities = trades
                .filter { it.status == "RUNNING" }
                .map { it.toActiveCacheEntity(accountType) }

            activeTradeCacheDao.clearCacheForAccount(accountType)
            activeTradeCacheDao.insertTrades(entities)
        } catch (e: Exception) {
            Log.e("TradeFragment", "Cache trades error: ${e.message}")
        }
    }

    fun updateMetrics(metrics: AccountMetrics) {
        if (!isAdded) return

        updatePnlText(metrics.total_profit_loss)
        tvBalance.text = String.format("%,.2f", metrics.balance)
        tvEquity.text = String.format("%,.2f", metrics.equity)
        tvMargin.text = String.format("%,.2f", metrics.margin)
        tvFreeMargin.text = String.format("%,.2f", metrics.free_margin)
        tvMarginLevel.text = String.format("%,.2f%%", metrics.margin_level)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            cacheMetrics(metrics, currentAccount)
        }
    }

    fun updateTrades(trades: List<TradeData>) {
        if (!isAdded) return

        tradesAdapter.updateTrades(trades)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            cacheTrades(trades, currentAccount)
        }
    }

    private fun updatePnlText(total: Double) {
        val formatted = String.format("%.2f USD", total)
        val color = if (total >= 0.0) {
            ContextCompat.getColor(requireContext(), R.color.blue)
        } else {
            ContextCompat.getColor(requireContext(), R.color.loss_negative)
        }
        tvPnl.text = formatted
        tvPnl.setTextColor(color)
        tvPnl.setTypeface(null, Typeface.BOLD)
    }

    private fun showPlaceholdersOnError() {
        tvPnl.text = "--"
        tvPnl.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
        tvBalance.text = "--"
        tvEquity.text = "--"
        tvMargin.text = "--"
        tvFreeMargin.text = "--"
        tvMarginLevel.text = "--"
    }

    override fun onResume() {
        super.onResume()

        val newAccount = (requireActivity() as MainActivity).currentAccount

        if (newAccount != currentAccount) {
            Log.d("TradeFragment", "Account changed: $currentAccount -> $newAccount")
            currentAccount = newAccount

            showPlaceholdersOnError()
            updateTrades(emptyList())

            loadDataWithOptimization()
        }
    }
}

// Extension functions
fun TradeData.toActiveCacheEntity(accountType: String): ActiveTradeCacheEntity {
    return ActiveTradeCacheEntity(
        trade_id = this.trade_id,
        account_type = accountType,
        symbol = this.symbol,
        entry_price = this.entry_price,
        current_buy_price = this.current_buy_price,
        current_sell_price = this.current_sell_price,
        start_time = this.start_time,
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

fun ActiveTradeCacheEntity.toTradeData(): TradeData {
    return TradeData(
        trade_id = this.trade_id,
        symbol = this.symbol,
        entry_price = this.entry_price,
        current_buy_price = this.current_buy_price,
        current_sell_price = this.current_sell_price,
        start_time = this.start_time,
        end_time = null,
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

fun AccountMetrics.toCacheEntity(accountType: String): AccountMetricsCacheEntity {
    return AccountMetricsCacheEntity(
        account_type = accountType,
        balance = this.balance,
        equity = this.equity,
        margin = this.margin,
        free_margin = this.free_margin,
        margin_level = this.margin_level,
        profit = this.profit,
        total_swap = this.total_swap,
        total_profit_loss = this.total_profit_loss
    )
}

fun AccountMetricsCacheEntity.toAccountMetrics(): AccountMetrics {
    return AccountMetrics(
        balance = this.balance,
        equity = this.equity,
        margin = this.margin,
        free_margin = this.free_margin,
        margin_level = this.margin_level,
        profit = this.profit,
        total_swap = this.total_swap,
        total_profit_loss = this.total_profit_loss
    )
}