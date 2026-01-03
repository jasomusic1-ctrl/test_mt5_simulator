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
import com.example.mark.api.OpenTradeItem

class OpenTradesAdapter : ListAdapter<OpenTradeItem, OpenTradesAdapter.OpenTradeViewHolder>(OpenTradeDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OpenTradeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.open_active_trades, parent, false)
        return OpenTradeViewHolder(view)
    }

    override fun onBindViewHolder(holder: OpenTradeViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: OpenTradeViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Partial update - only update changed fields
            holder.bindPartial(getItem(position))
        }
    }

    class OpenTradeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCurrencyPair: TextView? = itemView.findViewById(R.id.tvCurrencyPair)
        private val tvPositionCount: TextView? = itemView.findViewById(R.id.tvPositionCount)
        private val tvProfitLoss: TextView? = itemView.findViewById(R.id.tvProfitLoss)
        private val tvBuyInfo: TextView? = itemView.findViewById(R.id.tvBuyInfo)
        private val tvSellInfo: TextView? = itemView.findViewById(R.id.tvSellInfo)
        private val tvLowestPrice: TextView? = itemView.findViewById(R.id.tvLowestPrice)
        private val tvHighestPrice: TextView? = itemView.findViewById(R.id.tvHighestPrice)
        private val ivFlagBase: ImageView? = itemView.findViewById(R.id.ivFlagBase)
        private val ivFlagQuote: ImageView? = itemView.findViewById(R.id.ivFlagQuote)

        fun bind(item: OpenTradeItem) {
            // Set currency pair display (e.g., "USD/JPY")
            tvCurrencyPair?.text = item.displayPair

            // Set position count
            tvPositionCount?.text = item.activeTradeCount.toString()

            // Set profit/loss with formatting
            val plValue = item.profitLossSum
            val plText = if (plValue >= 0) {
                String.format("%.2f USD", plValue)
            } else {
                String.format("%.2f USD", plValue)
            }
            tvProfitLoss?.text = plText
            
            // Set color based on profit/loss
            val plColor = if (plValue >= 0) {
                ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
            } else {
                ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
            }
            tvProfitLoss?.setTextColor(plColor)

            // Set direction and lot size info
            val directionText = "${item.dominantDirection} ${String.format("%.2f", item.dominantLotSize)} lot"
            tvBuyInfo?.text = directionText
            
            // Set color based on direction
            if (item.dominantDirection == "BUY") {
                tvBuyInfo?.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_dark))
            } else {
                tvBuyInfo?.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
            }
            
            // Hide sell info as we're showing dominant direction only
            tvSellInfo?.visibility = View.GONE

            // Set lowest and highest prices
            tvLowestPrice?.text = item.lowestPrice?.let { String.format("%.5f", it) } ?: "—"
            tvHighestPrice?.text = item.highestPrice?.let { String.format("%.5f", it) } ?: "—"

            // Set flag images
            try {
                ivFlagBase?.setImageResource(item.baseFlagRes)
                ivFlagQuote?.setImageResource(item.quoteFlagRes)
            } catch (e: Exception) {
                // Use default flags if resource not found
            }
        }

        fun bindPartial(item: OpenTradeItem) {
            // Only update dynamic fields without touching static elements
            tvPositionCount?.text = item.activeTradeCount.toString()

            val plValue = item.profitLossSum
            tvProfitLoss?.text = String.format("%.2f USD", plValue)
            val plColor = if (plValue >= 0) {
                ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark)
            } else {
                ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark)
            }
            tvProfitLoss?.setTextColor(plColor)

            tvLowestPrice?.text = item.lowestPrice?.let { String.format("%.5f", it) } ?: "—"
            tvHighestPrice?.text = item.highestPrice?.let { String.format("%.5f", it) } ?: "—"
        }
    }

    class OpenTradeDiffCallback : DiffUtil.ItemCallback<OpenTradeItem>() {
        override fun areItemsTheSame(oldItem: OpenTradeItem, newItem: OpenTradeItem): Boolean {
            return oldItem.currencyPair == newItem.currencyPair
        }

        override fun areContentsTheSame(oldItem: OpenTradeItem, newItem: OpenTradeItem): Boolean {
            return oldItem == newItem
        }

        override fun getChangePayload(oldItem: OpenTradeItem, newItem: OpenTradeItem): Any? {
            // Return a non-null payload to trigger partial bind instead of full rebind
            return Unit
        }
    }
}
