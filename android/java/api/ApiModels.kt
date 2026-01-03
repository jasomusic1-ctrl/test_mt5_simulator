package com.example.mark.api

import com.google.gson.annotations.SerializedName

// Account Metrics Response
data class AccountMetrics(
    @SerializedName("balance") val balance: Double,
    @SerializedName("equity") val equity: Double,
    @SerializedName("margin") val margin: Double,
    @SerializedName("free_margin") val freeMargin: Double,
    @SerializedName("margin_level") val marginLevel: Double,
    @SerializedName("profit") val profit: Double,
    @SerializedName("deposit") val deposit: Double,
    @SerializedName("total_swap") val totalSwap: Double,
    @SerializedName("total_profit_loss") val totalProfitLoss: Double
)

// Active Trade Data - matches backend TradeData model
data class TradeData(
    @SerializedName("trade_id") val tradeId: String,
    @SerializedName("symbol") val symbol: String,
    @SerializedName("trade_direction") val tradeDirection: String,
    @SerializedName("lot_size") val lotSize: Double,
    @SerializedName("entry_price") val entryPrice: Double,
    @SerializedName("current_buy_price") val currentBuyPrice: Double,
    @SerializedName("current_sell_price") val currentSellPrice: Double,
    @SerializedName("profit_loss") val profitLoss: Double,
    @SerializedName("status") val status: String,
    @SerializedName("target_type") val targetType: String? = null,
    @SerializedName("target_amount") val targetAmount: Double? = null,
    @SerializedName("target_price") val targetPrice: Double? = null,
    @SerializedName("start_time") val startTime: String? = null,
    @SerializedName("end_time") val endTime: String? = null,
    @SerializedName("margin_used") val marginUsed: Double? = null,
    @SerializedName("swap") val swap: Double? = null,
    @SerializedName("commission") val commission: Double? = null,
    @SerializedName("closing_price") val closingPrice: Double? = null
)

// Currency Pair Stats for Sum in Exness
data class CurrencyPairStats(
    @SerializedName("active_trade_count") val activeTradeCount: Int,
    @SerializedName("running_profit_loss_sum") val runningProfitLossSum: Double,
    @SerializedName("lowest_current_price") val lowestCurrentPrice: Double?,
    @SerializedName("highest_current_price") val highestCurrentPrice: Double?,
    @SerializedName("completed_trades_today") val completedTradesToday: Int
)

// Sum in Exness Response
data class SumInExnessResponse(
    @SerializedName("account_type") val accountType: String,
    @SerializedName("currency_pair_stats") val currencyPairStats: Map<String, CurrencyPairStats>,
    @SerializedName("timestamp") val timestamp: String,
    @SerializedName("message") val message: String
)

// Switch Account Request
data class SwitchAccountRequest(
    @SerializedName("account_type") val accountType: String
)

// Switch Account Response - matches backend response structure
data class SwitchAccountResponse(
    @SerializedName("status") val status: String,
    @SerializedName("message") val message: String,
    @SerializedName("old_account") val oldAccount: String,
    @SerializedName("new_account") val newAccount: String,
    @SerializedName("account_metrics") val accountMetrics: AccountMetrics
)

// UI Model for displaying aggregated trade data per currency pair
data class OpenTradeItem(
    val currencyPair: String,
    val displayPair: String, // e.g., "USD/JPY"
    val activeTradeCount: Int,
    val profitLossSum: Double,
    val lowestPrice: Double?,
    val highestPrice: Double?,
    val dominantDirection: String, // "BUY" or "SELL"
    val dominantLotSize: Double,
    val baseFlagRes: Int,
    val quoteFlagRes: Int
)

// Individual closed trade item data
data class ClosedTradeItem(
    val tradeId: String,
    val currencyPair: String,
    val displayPair: String,    // e.g., "USD/JPY"
    val tradeDirection: String, // "BUY" or "SELL"
    val lotSize: Double,
    val entryPrice: Double,
    val closingPrice: Double,
    val profitLoss: Double,
    val baseFlagRes: Int,
    val quoteFlagRes: Int
)

// Date group containing all trades for a single day (displayed in one CardView)
data class ClosedTradesDateGroup(
    val date: String,               // e.g., "Yesterday, 22 December"
    val dailyProfitLoss: Double,    // Sum of P/L for that day
    val trades: List<ClosedTradeItem>
)

// Pending trade/order item data
data class PendingTradeItem(
    val tradeId: String,
    val currencyPair: String,
    val displayPair: String,        // e.g., "USD/JPY"
    val tradeDirection: String,     // "BUY" or "SELL"
    val lotSize: Double,
    val entryPrice: Double,
    val targetType: String,         // "LIMIT", "STOP", etc.
    val targetPrice: Double,
    val baseFlagRes: Int,
    val quoteFlagRes: Int
)
