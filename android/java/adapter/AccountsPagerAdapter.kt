package com.example.mark.adapter

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.mark.OpenTradesFragment
import com.example.mark.PendingTradesFragment
import com.example.mark.ClosedTradesFragment

class AccountsPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> OpenTradesFragment()
            1 -> PendingTradesFragment()
            2 -> ClosedTradesFragment()
            else -> OpenTradesFragment()
        }
    }
}
