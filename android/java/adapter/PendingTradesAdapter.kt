package com.example.mark.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mark.R
import com.example.mark.api.PendingTradeItem

class PendingTradesAdapter : ListAdapter<PendingTradeItem, PendingTradesAdapter.PendingTradeViewHolder>(PendingTradeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PendingTradeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pending_trade, parent, false)
        return PendingTradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: PendingTradeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class PendingTradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCurrencyPair: TextView? = itemView.findViewById(R.id.tvCurrencyPair)
        private val tvTradeDirection: TextView? = itemView.findViewById(R.id.tvTradeDirection)
        private val tvLotSize: TextView? = itemView.findViewById(R.id.tvLotSize)
        private val tvTargetType: TextView? = itemView.findViewById(R.id.tvTargetType)
        private val tvTargetPrice: TextView? = itemView.findViewById(R.id.tvTargetPrice)
        private val tvEntryPrice: TextView? = itemView.findViewById(R.id.tvEntryPrice)
        private val ivFlagBase: ImageView? = itemView.findViewById(R.id.ivFlagBase)
        private val ivFlagQuote: ImageView? = itemView.findViewById(R.id.ivFlagQuote)

        fun bind(item: PendingTradeItem) {
            tvCurrencyPair?.text = item.displayPair

            // Trade direction with color
            tvTradeDirection?.text = item.tradeDirection
            val directionColor = if (item.tradeDirection == "BUY") {
                ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark)
            } else {
                ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
            }
            tvTradeDirection?.setTextColor(directionColor)

            // Lot size
            tvLotSize?.text = String.format("%.2f lot", item.lotSize)
            tvLotSize?.setTextColor(directionColor)

            // Target type (LIMIT, STOP, etc.)
            tvTargetType?.text = item.targetType

            // Target price
            tvTargetPrice?.text = formatPrice(item.targetPrice, item.currencyPair)

            // Entry price
            tvEntryPrice?.text = "at ~${formatPrice(item.entryPrice, item.currencyPair)}"

            // Set flag images
            try {
                ivFlagBase?.setImageResource(item.baseFlagRes)
                ivFlagQuote?.setImageResource(item.quoteFlagRes)
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

    class PendingTradeDiffCallback : DiffUtil.ItemCallback<PendingTradeItem>() {
        override fun areItemsTheSame(oldItem: PendingTradeItem, newItem: PendingTradeItem): Boolean {
            return oldItem.tradeId == newItem.tradeId
        }

        override fun areContentsTheSame(oldItem: PendingTradeItem, newItem: PendingTradeItem): Boolean {
            return oldItem == newItem
        }
    }
}
