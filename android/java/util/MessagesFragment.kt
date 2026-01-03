package com.example.accountinfo

import android.graphics.Bitmap
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

class ChartsFragment : Fragment() {

    private var chartsImageView: ImageView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        Log.d("ChartsFragment", "onCreateView called")
        val view = inflater.inflate(R.layout.fragment_charts, container, false)

        chartsImageView = view.findViewById(R.id.charts_image)

        // Fetch image from backend
        fetchChartsImage()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Hide the menu icon when this fragment is displayed
        (activity as? MainActivity)?.hideMenuIcon()
        Log.d("ChartsFragment", "Menu icon hidden")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Show the menu icon again when leaving this fragment
        (activity as? MainActivity)?.showMenuIcon()
        Log.d("ChartsFragment", "Menu icon shown")
        chartsImageView = null
    }

    private fun fetchChartsImage() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mainActivity = activity as? MainActivity
                if (mainActivity == null) {
                    Log.e("ChartsFragment", "MainActivity not available")
                    return@launch
                }

                Log.d("ChartsFragment", "Fetching charts image from backend...")
                val response = mainActivity.api.getProfileImage("chats")

                if (response.status == "success") {
                    // Extract base64 data from data URL (format: data:image/png;base64,...)
                    val base64Data = response.image_data.substringAfter("base64,")
                    val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                    withContext(Dispatchers.Main) {
                        chartsImageView?.setImageBitmap(bitmap)
                        Log.d("ChartsFragment", "✓ Charts image loaded successfully")
                    }
                } else {
                    Log.w("ChartsFragment", "Failed to fetch image: ${response.status}")
                }
            } catch (e: Exception) {
                Log.e("ChartsFragment", "Error fetching charts image", e)
                // Keep default drawable on error
            }
        }
    }
}