package com.example.mark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mark.R
import com.example.mark.api.ClosedTradesDateGroup
import com.example.mark.api.ClosedTradeItem

class ClosedTradesAdapter : ListAdapter<ClosedTradesDateGroup, ClosedTradesAdapter.DateGroupViewHolder>(DateGroupDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateGroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_date_group_card, parent, false)
        return DateGroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: DateGroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class DateGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDateHeader: TextView = itemView.findViewById(R.id.tvDateHeader)
        private val tvDailyProfitLoss: TextView = itemView.findViewById(R.id.tvDailyProfitLoss)
        private val tradesContainer: LinearLayout = itemView.findViewById(R.id.tradesContainer)

        fun bind(dateGroup: ClosedTradesDateGroup) {
            // Set date header
            tvDateHeader.text = dateGroup.date
            
            // Set daily P/L
            val plText = String.format("%.2f USD", dateGroup.dailyProfitLoss)
            tvDailyProfitLoss.text = plText
            
            val plColor = if (dateGroup.dailyProfitLoss >= 0) {
                ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
            } else {
                ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
            }
            tvDailyProfitLoss.setTextColor(plColor)
            
            // Clear previous trade views
            tradesContainer.removeAllViews()
            
            // Add trade rows
            val inflater = LayoutInflater.from(itemView.context)
            for (trade in dateGroup.trades) {
                val tradeView = inflater.inflate(R.layout.item_closed_trade_row, tradesContainer, false)
                bindTradeView(tradeView, trade)
                tradesContainer.addView(tradeView)
            }
        }
        
        private fun bindTradeView(view: View, trade: ClosedTradeItem) {
            val tvCurrencyPair: TextView = view.findViewById(R.id.tvCurrencyPair)
            val tvPnL: TextView = view.findViewById(R.id.tvPnL)
            val tvTradeAction: TextView = view.findViewById(R.id.tvTradeAction)
            val tvLotSize: TextView = view.findViewById(R.id.tvLotSize)
            val tvEntryPrice: TextView = view.findViewById(R.id.tvEntryPrice)
            val tvExitPrice: TextView = view.findViewById(R.id.tvExitPrice)
            val ivBaseCurrencyFlag: ImageView = view.findViewById(R.id.ivBaseCurrencyFlag)
            val ivQuoteCurrencyFlag: ImageView = view.findViewById(R.id.ivQuoteCurrencyFlag)
            
            tvCurrencyPair.text = trade.displayPair
            
            // Format P/L
            val plText = String.format("%.2f USD", trade.profitLoss)
            tvPnL.text = plText
            
            val plColor = if (trade.profitLoss >= 0) {
                ContextCompat.getColor(view.context, android.R.color.holo_green_dark)
            } else {
                ContextCompat.getColor(view.context, android.R.color.holo_red_dark)
            }
            tvPnL.setTextColor(plColor)
            
            // Trade direction
            tvTradeAction.text = trade.tradeDirection.lowercase().replaceFirstChar { it.uppercase() }
            val directionColor = if (trade.tradeDirection == "BUY") {
                ContextCompat.getColor(view.context, android.R.color.holo_blue_dark)
            } else {
                ContextCompat.getColor(view.context, android.R.color.holo_red_dark)
            }
            tvTradeAction.setTextColor(directionColor)
            tvLotSize.setTextColor(directionColor)
            
            // Lot size
            tvLotSize.text = String.format("%.2f lot", trade.lotSize)
            
            // Entry price
            tvEntryPrice.text = formatPrice(trade.entryPrice, trade.currencyPair)
            
            // Exit price
            tvExitPrice.text = formatPrice(trade.closingPrice, trade.currencyPair)
            
            // Set flags
            try {
                ivBaseCurrencyFlag.setImageResource(trade.baseFlagRes)
                ivQuoteCurrencyFlag.setImageResource(trade.quoteFlagRes)
            } catch (e: Exception) {
                // Use default flags if resource not found
            }
        }
        
        private fun formatPrice(price: Double, symbol: String): String {
            return if (symbol.endsWith("JPY") || symbol.contains("JPY")) {
                String.format("%.3f", price)
            } else {
                String.format("%.5f", price)
            }
        }
    }

    class DateGroupDiffCallback : DiffUtil.ItemCallback<ClosedTradesDateGroup>() {
        override fun areItemsTheSame(oldItem: ClosedTradesDateGroup, newItem: ClosedTradesDateGroup): Boolean {
            return oldItem.date == newItem.date
        }

        override fun areContentsTheSame(oldItem: ClosedTradesDateGroup, newItem: ClosedTradesDateGroup): Boolean {
            return oldItem == newItem
        }
    }
}
