package com.example.accountinfo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat

class AccountsFragment : Fragment() {

    private val api: Mt5SimApi by lazy { (requireActivity() as MainActivity).api }

    // Account card views
    private lateinit var cardVip: CardView
    private lateinit var cardDemo: CardView
    private lateinit var cardMoney: CardView
    private lateinit var cardPro: CardView

    // Container for reordering
    private lateinit var accountsContainer: LinearLayout

    // Track account selection order (most recent first)
    private val accountSelectionOrder = mutableListOf<String>()

    // Top large CardView elements
    private lateinit var currentAccountName: TextView
    private lateinit var brokerNameTop: TextView
    private lateinit var accountNumberTop: TextView
    private lateinit var balanceCurrentTop: TextView

    // VIP Account TextViews
    private lateinit var tvAccountVip: TextView
    private lateinit var tvBrokerName1: TextView
    private lateinit var tvAccountDetails1: TextView
    private lateinit var tvBalance1: TextView
    private lateinit var tvCurrency1: TextView

    // DEMO Account TextViews
    private lateinit var tvAccountDemo: TextView
    private lateinit var tvBrokerName2: TextView
    private lateinit var tvAccountDetails2: TextView
    private lateinit var tvBalance2: TextView
    private lateinit var tvCurrency2: TextView

    // MONEY Account TextViews
    private lateinit var tvAccountMoney: TextView
    private lateinit var tvBrokerName3: TextView
    private lateinit var tvAccountDetails3: TextView
    private lateinit var tvBalance3: TextView
    private lateinit var tvCurrency3: TextView

    // PRO Account TextViews
    private lateinit var tvAccountPro: TextView
    private lateinit var tvBrokerName4: TextView
    private lateinit var tvAccountDetails4: TextView
    private lateinit var tvBalance4: TextView
    private lateinit var tvCurrency4: TextView

    private var isSwitching = false

    // Store account data for top card display
    private var accountsData: AccountsList? = null

    // Account to drawable mapping
    private val accountImageMap = mapOf(
        "Hunter/Acc" to R.drawable.hunter,
        "Demo/Acc" to R.drawable.elite,
        "Fast/Acc" to R.drawable.vip,
        "LivePro/Acc" to R.drawable.livepro
    )

    // Account ID mapping for display
    private val accountIdMap = mapOf(
        "Fast/Acc" to "410007254",
        "Demo/Acc" to "297738583",
        "Hunter/Acc" to "410000129",
        "LivePro/Acc" to "223253111"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_accounts, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            initializeViews(view)
            setupCardListeners()

            // Load initial accounts data
            loadAccountsData()

            // Reorder and highlight current account
            val mainActivity = requireActivity() as MainActivity
            reorderAccountCards(mainActivity.currentAccount)
            highlightSelectedAccount(mainActivity.currentAccount)

            // Update drawer image for current account (on fragment creation)
            updateDrawerImage(mainActivity.currentAccount)

            Log.d("AccountsFragment", "Fragment initialized successfully")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error initializing fragment", e)
            Toast.makeText(requireContext(), "Error loading accounts: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun initializeViews(view: View) {
        try {
            // Get the container
            accountsContainer = view.findViewById(R.id.accounts_container)
                ?: throw IllegalStateException("accounts_container not found")

            // Initialize top large CardView elements
            currentAccountName = view.findViewById(R.id.current_account_name)
                ?: throw IllegalStateException("current_account_name not found")
            brokerNameTop = view.findViewById(R.id.broker_name)
                ?: throw IllegalStateException("broker_name not found")
            accountNumberTop = view.findViewById(R.id.account_number)
                ?: throw IllegalStateException("account_number not found")
            balanceCurrentTop = view.findViewById(R.id.balance_current)
                ?: throw IllegalStateException("balance_current not found")

            // Find CardViews
            cardVip = view.findViewById(R.id.card_vip)
                ?: throw IllegalStateException("card_vip CardView not found")
            cardDemo = view.findViewById(R.id.card_demo)
                ?: throw IllegalStateException("card_demo CardView not found")
            cardMoney = view.findViewById(R.id.card_money)
                ?: throw IllegalStateException("card_money CardView not found")
            cardPro = view.findViewById(R.id.card_pro)
                ?: throw IllegalStateException("card_pro CardView not found")

            // Fast/Acc account views
            tvAccountVip = view.findViewById(R.id.tv_account_VIP)
            tvBrokerName1 = view.findViewById(R.id.tv_broker_name_1)
            tvAccountDetails1 = view.findViewById(R.id.tv_account_details_1)
            tvBalance1 = view.findViewById(R.id.tv_balance_1)
            tvCurrency1 = view.findViewById(R.id.tv_currency_last_known_1)

            // Demo/Acc account views
            tvAccountDemo = view.findViewById(R.id.tv_account_DEMO)
            tvBrokerName2 = view.findViewById(R.id.tv_broker_name_2)
            tvAccountDetails2 = view.findViewById(R.id.tv_account_details_2)
            tvBalance2 = view.findViewById(R.id.tv_balance_2)
            tvCurrency2 = view.findViewById(R.id.tv_currency_last_known_2)

            // LivePro/Acc account views
            tvAccountMoney = view.findViewById(R.id.tv_account_MONEY)
            tvBrokerName3 = view.findViewById(R.id.tv_broker_name_3)
            tvAccountDetails3 = view.findViewById(R.id.tv_account_details_3)
            tvBalance3 = view.findViewById(R.id.tv_balance_3)
            tvCurrency3 = view.findViewById(R.id.tv_currency_last_known_3)

            // Hunter/Acc account views
            tvAccountPro = view.findViewById(R.id.tv_account_PRO)
            tvBrokerName4 = view.findViewById(R.id.tv_broker_name_4)
            tvAccountDetails4 = view.findViewById(R.id.tv_account_details_4)
            tvBalance4 = view.findViewById(R.id.tv_balance_4)
            tvCurrency4 = view.findViewById(R.id.tv_currency_last_known_4)

            // Set default account names
            tvAccountVip.text = "Fast/Acc"
            tvAccountDemo.text = "Demo/Acc"
            tvAccountMoney.text = "LivePro/Acc"
            tvAccountPro.text = "Hunter/Acc"

            // Set broker names
            tvBrokerName1.text = "Exness Technologies Ltd"
            tvBrokerName2.text = "Exness Technologies Ltd"
            tvBrokerName3.text = "Exness Technologies Ltd"
            tvBrokerName4.text = "Exness Technologies Ltd"

            Log.d("AccountsFragment", "All views initialized successfully")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error in initializeViews", e)
            throw e
        }
    }

    private fun setupCardListeners() {
        cardVip.setOnClickListener {
            Log.d("AccountsFragment", "Fast/Acc card clicked")
            switchAccountAndNavigate("Fast/Acc")
        }

        cardDemo.setOnClickListener {
            Log.d("AccountsFragment", "Demo/Acc card clicked")
            switchAccountAndNavigate("Demo/Acc")
        }

        cardMoney.setOnClickListener {
            Log.d("AccountsFragment", "LivePro/Acc card clicked")
            switchAccountAndNavigate("LivePro/Acc")
        }

        cardPro.setOnClickListener {
            Log.d("AccountsFragment", "Hunter/Acc card clicked")
            switchAccountAndNavigate("Hunter/Acc")
        }

        Log.d("AccountsFragment", "Card listeners set up")
    }

    private fun updateAccountSelectionOrder(selectedAccount: String) {
        // Remove if already in list, then add to front (most recent)
        accountSelectionOrder.remove(selectedAccount)
        accountSelectionOrder.add(0, selectedAccount)

        // Ensure all accounts are in the list
        val allAccounts = listOf("Fast/Acc", "Demo/Acc", "LivePro/Acc", "Hunter/Acc")
        allAccounts.forEach { account ->
            if (!accountSelectionOrder.contains(account)) {
                accountSelectionOrder.add(account)
            }
        }

        Log.d("AccountsFragment", "Selection order updated: $accountSelectionOrder")
    }

    private fun reorderAccountCards(selectedAccount: String) {
        try {
            // Update selection order tracking
            updateAccountSelectionOrder(selectedAccount)

            // Remove all cards from container
            accountsContainer.removeAllViews()

            // Map account types to their cards
            val accountCardMap = mapOf(
                "Fast/Acc" to cardVip,
                "Demo/Acc" to cardDemo,
                "LivePro/Acc" to cardMoney,
                "Hunter/Acc" to cardPro
            )

            // Add cards in selection order, but HIDE the selected account's card
            // Only show 3 cards (the non-selected ones)
            accountSelectionOrder.forEach { accountType ->
                accountCardMap[accountType]?.let { card ->
                    // Remove from parent if it has one
                    (card.parent as? ViewGroup)?.removeView(card)
                    
                    if (accountType == selectedAccount) {
                        // Hide the selected account's card - it's shown in top large card
                        card.visibility = View.GONE
                    } else {
                        // Show non-selected cards
                        card.visibility = View.VISIBLE
                    }
                    
                    accountsContainer.addView(card)
                }
            }

            Log.d("AccountsFragment", "Reordered cards - $selectedAccount hidden (shown in top card), 3 cards visible")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error reordering cards", e)
        }
    }

    /**
     * Update the top large CardView with selected account information
     */
    private fun updateTopCardView(accountType: String) {
        try {
            accountsData?.let { data ->
                val account = data.accounts[accountType]

                if (account != null) {
                    // Update account name
                    currentAccountName.text = "$accountType"

                    // Update broker name
                    brokerNameTop.text = "Exness Technologies Ltd"

                    // Update account number and server (removed active trades count)
                    accountNumberTop.text = "Exness-MT5Trial9"

                    // Update balance
                    balanceCurrentTop.text = String.format("%.2f", account.balance)

                    Log.d("AccountsFragment", " Top card updated with $accountType account info")
                } else {
                    // Fallback if account data is not available
                    currentAccountName.text = "$accountType"
                    brokerNameTop.text = "Exness Technologies Ltd"
                    accountNumberTop.text = "Exness-MT5Trial9"
                    balanceCurrentTop.text = "--"

                    Log.w("AccountsFragment", "Account data not available for $accountType")
                }
            } ?: run {
                // No data loaded yet
                currentAccountName.text = "$accountType"
                brokerNameTop.text = "Exness Technologies Ltd"
                accountNumberTop.text = "Exness-MT5Trial9"
                balanceCurrentTop.text = "--"

                Log.w("AccountsFragment", "No accounts data loaded yet")
            }
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error updating top card view", e)
        }
    }

    /**
     * Update the drawer image based on the selected account
     * This happens in the background after account selection
     */
    private fun updateDrawerImage(accountType: String) {
        try {
            val mainActivity = requireActivity() as MainActivity
            val drawerLayout = mainActivity.findViewById<DrawerLayout>(R.id.drawer_layout)
            val drawerView = drawerLayout?.getChildAt(1)
            val drawerImageView = drawerView?.findViewById<ImageView>(R.id.imageView)

            // Get the drawable resource for this account
            val drawableRes = accountImageMap[accountType] ?: R.drawable.side_menu

            // Update the image
            drawerImageView?.setImageResource(drawableRes)

            // Ensure it's still clickable and navigates to AccountsFragment
            drawerImageView?.setOnClickListener {
                Log.d("AccountsFragment", "Drawer image clicked - navigating to AccountsFragment")
                drawerLayout.closeDrawer(GravityCompat.START)

                // If we're already on AccountsFragment, just refresh
                if (this.isVisible) {
                    loadAccountsData()
                    reorderAccountCards(mainActivity.currentAccount)
                    highlightSelectedAccount(mainActivity.currentAccount)
                    updateTopCardView(mainActivity.currentAccount)
                } else {
                    mainActivity.loadFragment(AccountsFragment(), "accounts")
                }
            }

            Log.d("AccountsFragment", " Drawer image updated to ${accountType} account image (background)")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error updating drawer image", e)
        }
    }

    private fun switchAccountAndNavigate(accountType: String) {
        if (isSwitching) {
            Log.d("AccountsFragment", "Account switch already in progress, ignoring request")
            return
        }

        val mainActivity = requireActivity() as MainActivity
        val currentAccount = mainActivity.currentAccount

        if (accountType == currentAccount) {
            Log.d("AccountsFragment", "$accountType account already active - reordering cards")

            // Reorder cards to bring selected to top
            reorderAccountCards(accountType)
            highlightSelectedAccount(accountType)
            updateTopCardView(accountType)

            Toast.makeText(
                requireContext(),
                "$accountType account (active)",
                Toast.LENGTH_SHORT
            ).show()

            // Stay on AccountsFragment - do NOT navigate away
            return
        }

        isSwitching = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("AccountsFragment", "=== USER INITIATED SWITCH: $currentAccount -> $accountType ===")

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "Switching to $accountType account...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Call the optimized switchAccount function
                val response = withContext(Dispatchers.IO) {
                    mainActivity.switchAccount(accountType)
                }

                Log.d("AccountsFragment", "Switch response: ${response.message}")

                withContext(Dispatchers.Main) {
                    // Reorder cards - selected account goes to top, followed by most recent
                    reorderAccountCards(accountType)

                    // Update highlight
                    highlightSelectedAccount(accountType)

                    // Update top card view with new account info
                    updateTopCardView(accountType)

                    // Refresh account data to show updated balances
                    loadAccountsData()

                    // UPDATE DRAWER IMAGE IN BACKGROUND (non-blocking)
                    launch {
                        delay(300) // Small delay to ensure smooth UI transition
                        updateDrawerImage(accountType)
                    }

                    Toast.makeText(
                        requireContext(),
                        "Switched to $accountType account",
                        Toast.LENGTH_SHORT
                    ).show()

                    Log.d("AccountsFragment", "=== SWITCH COMPLETED: Now on $accountType (drawer image updating in background) ===")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("AccountsFragment", "Failed to switch account", e)

                    Toast.makeText(
                        requireContext(),
                        "Failed to switch account: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    highlightSelectedAccount(mainActivity.currentAccount)
                    updateTopCardView(mainActivity.currentAccount)
                }
            } finally {
                isSwitching = false
            }
        }
    }

    private fun highlightSelectedAccount(accountType: String) {
        try {
            // Reset all cards to default elevation
            cardVip.cardElevation = 2f
            cardDemo.cardElevation = 2f
            cardMoney.cardElevation = 2f
            cardPro.cardElevation = 2f

            // Highlight selected card with increased elevation
            when (accountType) {
                "Fast/Acc" -> cardVip.cardElevation = 12f
                "Demo/Acc" -> cardDemo.cardElevation = 12f
                "LivePro/Acc" -> cardMoney.cardElevation = 12f
                "Hunter/Acc" -> cardPro.cardElevation = 12f
            }

            Log.d("AccountsFragment", "Highlighted $accountType account")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error highlighting account", e)
        }
    }

    private fun loadAccountsData() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("AccountsFragment", "Loading accounts data...")
                val accountsList = api.listAccounts()

                withContext(Dispatchers.Main) {
                    // Store the data
                    accountsData = accountsList

                    // Update all account cards
                    updateAccountsUI(accountsList)

                    // Update top card with current account
                    val mainActivity = requireActivity() as MainActivity
                    updateTopCardView(mainActivity.currentAccount)

                    Log.d("AccountsFragment", "Successfully loaded data for ${accountsList.accounts.size} accounts")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e("AccountsFragment", "Failed to load accounts data", e)
                    Toast.makeText(
                        requireContext(),
                        "Failed to load account data: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun updateAccountsUI(accountsList: AccountsList) {
        try {
            val accounts = accountsList.accounts

            // Update Fast/Acc account (removed active trades count)
            accounts["Fast/Acc"]?.let { acc ->
                tvBalance1.text = String.format("%.2f", acc.balance)
                tvAccountDetails1.text = "${accountIdMap["Fast/Acc"]} — Exness-MT5Trial9"
                tvCurrency1.text = "USD, Equity: ${String.format("%.2f", acc.equity)}"
            } ?: run {
                tvBalance1.text = "--"
                tvAccountDetails1.text = "${accountIdMap["Fast/Acc"]} — Exness-MT5Trial9"
                tvCurrency1.text = "--"
            }

            // Update Demo/Acc account (removed active trades count)
            accounts["Demo/Acc"]?.let { acc ->
                tvBalance2.text = String.format("%.2f", acc.balance)
                tvAccountDetails2.text = "${accountIdMap["Demo/Acc"]} — Exness-MT5Trial9"
                tvCurrency2.text = "USD, Equity: ${String.format("%.2f", acc.equity)}"
            } ?: run {
                tvBalance2.text = "--"
                tvAccountDetails2.text = "${accountIdMap["Demo/Acc"]} — Exness-MT5Trial9"
                tvCurrency2.text = "--"
            }

            // Update LivePro/Acc account (removed active trades count)
            accounts["LivePro/Acc"]?.let { acc ->
                tvBalance3.text = String.format("%.2f", acc.balance)
                tvAccountDetails3.text = "${accountIdMap["LivePro/Acc"]} — Exness-MT5Trial9"
                tvCurrency3.text = "USD, Equity: ${String.format("%.2f", acc.equity)}"
            } ?: run {
                tvBalance3.text = "--"
                tvAccountDetails3.text = "${accountIdMap["LivePro/Acc"]} — Exness-MT5Trial9"
                tvCurrency3.text = "--"
            }

            // Update Hunter/Acc account (removed active trades count)
            accounts["Hunter/Acc"]?.let { acc ->
                tvBalance4.text = String.format("%.2f", acc.balance)
                tvAccountDetails4.text = "${accountIdMap["Hunter/Acc"]} — Exness-MT5Trial9"
                tvCurrency4.text = "USD, Equity: ${String.format("%.2f", acc.equity)}"
            } ?: run {
                tvBalance4.text = "--"
                tvAccountDetails4.text = "${accountIdMap["Hunter/Acc"]} — Exness-MT5Trial9"
                tvCurrency4.text = "--"
            }

            Log.d("AccountsFragment", "Updated UI with account data for ${accounts.size} accounts")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error updating accounts UI", e)
        }
    }

    override fun onResume() {
        super.onResume()

        try {
            loadAccountsData()

            val mainActivity = requireActivity() as MainActivity
            reorderAccountCards(mainActivity.currentAccount)
            highlightSelectedAccount(mainActivity.currentAccount)
            updateTopCardView(mainActivity.currentAccount)

            // Ensure drawer image is correct when returning to this fragment
            updateDrawerImage(mainActivity.currentAccount)

            Log.d("AccountsFragment", "Fragment resumed - active account: ${mainActivity.currentAccount}")
        } catch (e: Exception) {
            Log.e("AccountsFragment", "Error in onResume", e)
        }
    }
}