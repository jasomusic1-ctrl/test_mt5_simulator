package com.example.mark.api

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TradingRepository(context: Context) {
    
    private val apiService = RetrofitClient.apiService
    private val gson = Gson()
    private val prefs: SharedPreferences = context.getSharedPreferences("trading_cache", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_ACCOUNT_METRICS = "account_metrics"
        private const val KEY_ACTIVE_TRADES = "active_trades"
        private const val KEY_HISTORICAL_TRADES = "historical_trades"
        private const val KEY_SUM_IN_EXNESS = "sum_in_exness"
        private const val KEY_CURRENT_ACCOUNT = "current_account"
        private const val CACHE_EXPIRY_MS = 5000L // 5 seconds for faster refresh
        
        @Volatile
        private var instance: TradingRepository? = null
        
        fun getInstance(context: Context): TradingRepository {
            return instance ?: synchronized(this) {
                instance ?: TradingRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // Current account management
    var currentAccount: String
        get() = prefs.getString(KEY_CURRENT_ACCOUNT, "Fast/Acc") ?: "Fast/Acc"
        set(value) = prefs.edit().putString(KEY_CURRENT_ACCOUNT, value).apply()
    
    // Account Metrics
    suspend fun getAccountMetrics(forceRefresh: Boolean = false): Result<AccountMetrics> {
        return withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh) {
                    getCachedAccountMetrics()?.let { return@withContext Result.success(it) }
                }
                
                val response = apiService.getAccountMetrics()
                if (response.isSuccessful && response.body() != null) {
                    val metrics = response.body()!!
                    cacheAccountMetrics(metrics)
                    Result.success(metrics)
                } else {
                    getCachedAccountMetrics()?.let { Result.success(it) }
                        ?: Result.failure(Exception("Failed to fetch account metrics"))
                }
            } catch (e: Exception) {
                getCachedAccountMetrics()?.let { Result.success(it) }
                    ?: Result.failure(e)
            }
        }
    }
    
    private fun cacheAccountMetrics(metrics: AccountMetrics) {
        prefs.edit()
            .putString(KEY_ACCOUNT_METRICS, gson.toJson(metrics))
            .putLong("${KEY_ACCOUNT_METRICS}_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    private fun getCachedAccountMetrics(): AccountMetrics? {
        val json = prefs.getString(KEY_ACCOUNT_METRICS, null) ?: return null
        val timestamp = prefs.getLong("${KEY_ACCOUNT_METRICS}_timestamp", 0)
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) return null
        return try {
            gson.fromJson(json, AccountMetrics::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    // Active Trades
    suspend fun getActiveTrades(forceRefresh: Boolean = false): Result<List<TradeData>> {
        return withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh) {
                    getCachedActiveTrades()?.let { return@withContext Result.success(it) }
                }
                
                val response = apiService.getActiveTrades()
                if (response.isSuccessful && response.body() != null) {
                    val trades = response.body()!!
                    cacheActiveTrades(trades)
                    Result.success(trades)
                } else {
                    getCachedActiveTrades()?.let { Result.success(it) }
                        ?: Result.failure(Exception("Failed to fetch active trades"))
                }
            } catch (e: Exception) {
                getCachedActiveTrades()?.let { Result.success(it) }
                    ?: Result.failure(e)
            }
        }
    }
    
    private fun cacheActiveTrades(trades: List<TradeData>) {
        prefs.edit()
            .putString(KEY_ACTIVE_TRADES, gson.toJson(trades))
            .putLong("${KEY_ACTIVE_TRADES}_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    private fun getCachedActiveTrades(): List<TradeData>? {
        val json = prefs.getString(KEY_ACTIVE_TRADES, null) ?: return null
        val timestamp = prefs.getLong("${KEY_ACTIVE_TRADES}_timestamp", 0)
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) return null
        return try {
            val type = object : TypeToken<List<TradeData>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }
    
    // Sum in Exness
    suspend fun getSumInExness(forceRefresh: Boolean = false): Result<SumInExnessResponse> {
        return withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh) {
                    getCachedSumInExness()?.let { return@withContext Result.success(it) }
                }
                
                val response = apiService.getSumInExness()
                if (response.isSuccessful && response.body() != null) {
                    val stats = response.body()!!
                    cacheSumInExness(stats)
                    Result.success(stats)
                } else {
                    getCachedSumInExness()?.let { Result.success(it) }
                        ?: Result.failure(Exception("Failed to fetch sum in exness"))
                }
            } catch (e: Exception) {
                getCachedSumInExness()?.let { Result.success(it) }
                    ?: Result.failure(e)
            }
        }
    }
    
    private fun cacheSumInExness(stats: SumInExnessResponse) {
        prefs.edit()
            .putString(KEY_SUM_IN_EXNESS, gson.toJson(stats))
            .putLong("${KEY_SUM_IN_EXNESS}_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    private fun getCachedSumInExness(): SumInExnessResponse? {
        val json = prefs.getString(KEY_SUM_IN_EXNESS, null) ?: return null
        val timestamp = prefs.getLong("${KEY_SUM_IN_EXNESS}_timestamp", 0)
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) return null
        return try {
            gson.fromJson(json, SumInExnessResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    // Historical/Closed Trades
    suspend fun getHistoricalTrades(forceRefresh: Boolean = false): Result<List<TradeData>> {
        return withContext(Dispatchers.IO) {
            try {
                if (!forceRefresh) {
                    getCachedHistoricalTrades()?.let { return@withContext Result.success(it) }
                }
                
                val response = apiService.getHistoricalTrades()
                if (response.isSuccessful && response.body() != null) {
                    val trades = response.body()!!
                    cacheHistoricalTrades(trades)
                    Result.success(trades)
                } else {
                    getCachedHistoricalTrades()?.let { Result.success(it) }
                        ?: Result.failure(Exception("Failed to fetch historical trades"))
                }
            } catch (e: Exception) {
                getCachedHistoricalTrades()?.let { Result.success(it) }
                    ?: Result.failure(e)
            }
        }
    }
    
    private fun cacheHistoricalTrades(trades: List<TradeData>) {
        prefs.edit()
            .putString(KEY_HISTORICAL_TRADES, gson.toJson(trades))
            .putLong("${KEY_HISTORICAL_TRADES}_timestamp", System.currentTimeMillis())
            .apply()
    }
    
    private fun getCachedHistoricalTrades(): List<TradeData>? {
        val json = prefs.getString(KEY_HISTORICAL_TRADES, null) ?: return null
        val timestamp = prefs.getLong("${KEY_HISTORICAL_TRADES}_timestamp", 0)
        if (System.currentTimeMillis() - timestamp > CACHE_EXPIRY_MS) return null
        return try {
            val type = object : TypeToken<List<TradeData>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }
    
    // Switch Account
    suspend fun switchAccount(accountType: String): Result<SwitchAccountResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val response = apiService.switchAccount(SwitchAccountRequest(accountType))
                if (response.isSuccessful && response.body() != null) {
                    currentAccount = accountType
                    // Clear cache when switching accounts
                    clearCache()
                    Result.success(response.body()!!)
                } else {
                    Result.failure(Exception("Failed to switch account"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    fun clearCache() {
        prefs.edit()
            .remove(KEY_ACCOUNT_METRICS)
            .remove("${KEY_ACCOUNT_METRICS}_timestamp")
            .remove(KEY_ACTIVE_TRADES)
            .remove("${KEY_ACTIVE_TRADES}_timestamp")
            .remove(KEY_HISTORICAL_TRADES)
            .remove("${KEY_HISTORICAL_TRADES}_timestamp")
            .remove(KEY_SUM_IN_EXNESS)
            .remove("${KEY_SUM_IN_EXNESS}_timestamp")
            .apply()
    }
}
