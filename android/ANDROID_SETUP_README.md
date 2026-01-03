# Android App Setup Instructions

## Required Dependencies (add to app/build.gradle)

```gradle
dependencies {
    // Retrofit for API calls
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    implementation 'com.squareup.retrofit2:converter-gson:2.9.0'
    
    // OkHttp for networking
    implementation 'com.squareup.okhttp3:okhttp:4.12.0'
    implementation 'com.squareup.okhttp3:logging-interceptor:4.12.0'
    
    // Gson for JSON parsing
    implementation 'com.google.code.gson:gson:2.10.1'
    
    // Coroutines for async operations
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3'
    
    // Lifecycle for viewLifecycleOwner.lifecycleScope
    implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.7.0'
}
```

## Required Permissions (add to AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Network Security Config (add to AndroidManifest.xml application tag)

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="true"
    ... >
```

## Server URL Configuration

The app is configured to connect to:
- **Emulator**: `http://10.0.2.2:8080/` (maps to host machine's localhost)
- **Real Device**: Update `BASE_URL` in `RetrofitClient.kt` to your server's IP

To change the server URL, edit `android/java/api/RetrofitClient.kt`:
```kotlin
private const val BASE_URL = "http://YOUR_SERVER_IP:8080/"
```

## Backend Server

Make sure the FastAPI backend (`finalisedmain.py`) is running:
```bash
python finalisedmain.py
```

The server runs on port 8080 by default.

## API Endpoints Used

- `GET /api/account-metrics` - Get account balance, equity, margin
- `GET /api/trades/active` - Get list of active trades
- `GET /api/sum-in-exness` - Get aggregated stats per currency pair
- `POST /api/switch-account` - Switch between VIP/DEMO/PRO/MONEY accounts

## Troubleshooting

1. **Connection refused**: Ensure the backend server is running
2. **Network error on real device**: Update BASE_URL to your computer's local IP
3. **Cleartext traffic not permitted**: Ensure network_security_config.xml is properly referenced
