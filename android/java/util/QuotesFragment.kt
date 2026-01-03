package com.example.accountinfo

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuotesFragment : Fragment() {

    private var quotesImageView: ImageView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("QuotesFragment", "onCreateView called")
        val view = inflater.inflate(R.layout.fragment_quotes, container, false)

        quotesImageView = view.findViewById(R.id.quotes_image)

        // Fetch image from backend
        fetchQuotesImage()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Hide the menu icon when this fragment is displayed
        (activity as? MainActivity)?.hideMenuIcon()
        Log.d("QuotesFragment", "Menu icon hidden")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Show the menu icon again when leaving this fragment
        (activity as? MainActivity)?.showMenuIcon()
        Log.d("QuotesFragment", "Menu icon shown")
        quotesImageView = null
    }

    /**
     * Refresh the image - called by MainActivity when WebSocket receives image update
     */
    fun refreshImage() {
        Log.d("QuotesFragment", "🔄 Refreshing quotes image...")
        fetchQuotesImage()
    }

    private fun fetchQuotesImage() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mainActivity = activity as? MainActivity
                if (mainActivity == null) {
                    Log.e("QuotesFragment", "MainActivity not available")
                    return@launch
                }

                Log.d("QuotesFragment", "Fetching quotes image from backend...")
                val response = mainActivity.api.getProfileImage("quotes")

                if (response.status == "success") {
                    // Extract base64 data from data URL (format: data:image/png;base64,...)
                    val base64Data = response.image_data.substringAfter("base64,")
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    withContext(Dispatchers.Main) {
                        quotesImageView?.setImageBitmap(bitmap)
                        Log.d("QuotesFragment", "✓ Quotes image loaded successfully")
                    }
                } else {
                    Log.w("QuotesFragment", "Failed to fetch image: ${response.status}")
                }
            } catch (e: Exception) {
                Log.e("QuotesFragment", "Error fetching quotes image", e)
                // Keep default drawable on error
            }
        }
    }
}