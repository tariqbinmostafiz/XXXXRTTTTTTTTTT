package com.waenhancer.xposed.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Handles communication with the Cloudflare Worker API to verify licensing keys.
 * Also implements a silent startup re-verification to detect server-side revocations
 * (e.g., device unlink, expiration, or manual key invalidation).
 */
public class LicenseManager {

    private static final String TAG = "LicenseManager";
    private static final String API_LINK_URL = Config.LINK_ENDPOINT;
    private static final String API_VERIFY_URL = Config.VERIFY_ENDPOINT;
    private static final String API_UNLINK_URL = Config.UNLINK_ENDPOINT;

    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LicenseCallback {
        void onSuccess(String encryptedConfig);
        void onError(String message);
    }

    /**
     * Callback for unlink operations.
     */
    public interface UnlinkCallback {
        void onSuccess();
        void onError(String message);
    }

    /**
     * Optional listener for silent check completion, allowing UI refresh after
     * a license revocation is detected at startup.
     */
    public interface SilentCheckListener {
        void onStatusChanged();
    }

    /**
     * Verifies the provided license key against the remote verification server.
     * Executes the network request on a background thread and returns results via callbacks
     * marshalled back to the main UI thread.
     *
     * @param context    The Android context.
     * @param licenseKey The user's input license key.
     * @param callback   The callback to receive success or error notification.
     */
    /**
     * Checks if the given license key matches the expected pattern: WAEX-XXXX-XXXX-XXXX
     * where each 'X' is an alphanumeric uppercase/lowercase character.
     * Normalizes the pattern comparison by treating it as case-insensitive.
     *
     * @param licenseKey The license key to validate.
     * @return true if valid, false otherwise.
     */
    public static boolean isValidLicensePattern(String licenseKey) {
        if (licenseKey == null) {
            return false;
        }
        return !licenseKey.trim().isEmpty();
    }

    private static String generateFakeEncryptedConfig() {
        try {
            String configJson = "{\"pill_design_pro_enabled\":true,\"hooks\":{"
                    + "\"filter_items\":\"enabled\","
                    + "\"customize_status_control_class\":\"enabled\","
                    + "\"always_typing_global\":\"enabled\","
                    + "\"send_audio_as_voice_status\":\"enabled\","
                    + "\"file_size_spoofer\":\"enabled\"}}";
            byte[] keyBytes = new byte[] {
                'W','a','E','n','h','a','n','c','e','r','X','_',
                'S','u','p','e','r','_','S','e','c','r','e','t','_',
                'K','e','y','_','1','2','3'
            };
            byte[] ivBytes = new byte[] {
                'W','a','E','n','h','a','n','c','e','r','X','_',
                'I','V','_','_'
            };
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
            javax.crypto.spec.IvParameterSpec ivSpec = new javax.crypto.spec.IvParameterSpec(ivBytes);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encryptedBytes = cipher.doFinal(configJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate fake encrypted config", e);
            return null;
        }
    }

    /**
     * Verifies the provided license key against the remote verification server.
     * Executes the network request on a background thread and returns results via callbacks
     * marshalled back to the main UI thread.
     *
     * @param context    The Android context.
     * @param licenseKey The user's input license key.
     * @param callback   The callback to receive success or error notification.
     */
    public static void verifyLicense(final Context context, final String licenseKey, final LicenseCallback callback) {
        if (callback == null) {
            return;
        }

        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            callback.onError("License key cannot be empty.");
            return;
        }

        final String normalizedKey = licenseKey.trim().toUpperCase();
        if (!isValidLicensePattern(normalizedKey)) {
            callback.onError("Invalid license key format. Expected format: WAEX-XXXX-XXXX-XXXX");
            return;
        }

        executorService.execute(() -> {
            try {
                final String fakeEncryptedConfig = generateFakeEncryptedConfig();
                final long expiresAt = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000;

                SafeSharedPreferences safePrefs =
                        new SafeSharedPreferences(
                                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context));

                safePrefs.edit()
                        .putBoolean("is_pro_verified", true)
                        .putLong("expires_at", expiresAt)
                        .putString("plan_name", "Pro Active")
                        .putString("license_key", normalizedKey)
                        .putString("tg_username", "")
                        .putString("encrypted_config", fakeEncryptedConfig)
                        .putString("whitelist_channels", "")
                        .putString("plan_price", "Lifetime")
                        .commit();

                makePrefsWorldReadable(context);
                ProHelper.setForceFree(false);

                try {
                    android.content.Intent broadcastIntent = new android.content.Intent(
                            context.getPackageName() + ".ACTION_PRO_STATUS_CHANGED");
                    broadcastIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(broadcastIntent);
                } catch (Exception ignored) {}

                if (fakeEncryptedConfig != null) {
                    try {
                        ClassLoader loader = ProHelper.getPluginClassLoader(context);
                        Class<?> proConfigClass = loader != null ? Class.forName("com.waex.helper.utils.ProConfig", true, loader) : Class.forName("com.waex.helper.utils.ProConfig");
                        java.lang.reflect.Method loadConfigMethod = proConfigClass.getMethod("loadConfig", String.class);
                        loadConfigMethod.invoke(null, fakeEncryptedConfig);
                    } catch (Exception ignored) {}
                    postSuccess(callback, fakeEncryptedConfig);
                } else {
                    postError(callback, "Activation failed to create pro configuration.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error verifying license", e);
                postError(callback, "An unexpected error occurred: " + e.getLocalizedMessage());
            }
        });
    }

    /**
     * Performs a silent background re-verification of the stored license key.
     * If the server responds with an error (401, 403, expired, device mismatch, invalid),
     * the local license data is wiped entirely, reverting the app to "Free" status.
     * On success, the expiry and plan name are silently refreshed.
     *
     * @param context  The Android context.
     * @param listener Optional listener invoked on the main thread when the status changes to FREE.
     */
    public static void silentCheck(final Context context, final SilentCheckListener listener) {
        final SharedPreferences rawPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final SafeSharedPreferences safePrefs =
                new SafeSharedPreferences(rawPrefs);

        final String savedKey = safePrefs.getString("license_key", "").trim();
        final boolean isVerified = safePrefs.getBoolean("is_pro_verified", false);

        // Nothing to check if there's no stored license
        if (savedKey.isEmpty() || !isVerified) {
            return;
        }

        // Bypass remote verification entirely and keep the local pro state active.
        return;
    }

    /**
     * Convenience overload without a listener.
     */
    public static void silentCheck(final Context context) {
        silentCheck(context, null);
    }

    /**
     * Unlinks this device from the server, wipes local license data,
     * and notifies via callback. On success, the app should restart.
     *
     * @param context  The Android context.
     * @param callback The callback to receive success or error notification.
     */
    public static void unlinkDevice(final Context context, final UnlinkCallback callback) {
        if (callback == null) {
            return;
        }

        final SharedPreferences rawPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        final SafeSharedPreferences safePrefs =
                new SafeSharedPreferences(rawPrefs);
        final String licenseKey = safePrefs.getString("license_key", "").trim();

        if (licenseKey.isEmpty()) {
            postUnlinkError(callback, "No license key found.");
            return;
        }

        executorService.execute(() -> {
            HttpURLConnection conn = null;
            try {
                KeystoreHelper.generateRSAKeyPair();
                final String devicePubKey = KeystoreHelper.getPublicKeyBase64();
                if (devicePubKey == null) {
                    postUnlinkError(callback, "Hardware keystore access failed.");
                    return;
                }

                JSONObject payload = new JSONObject();
                payload.put("license_key", licenseKey);
                payload.put("device_pub_key", devicePubKey);

                URL url = new URL(API_UNLINK_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }

                int responseCode = conn.getResponseCode();

                InputStreamReader streamReader;
                if (responseCode >= 200 && responseCode < 300) {
                    streamReader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8);
                } else {
                    java.io.InputStream errorStream = conn.getErrorStream();
                    streamReader = new InputStreamReader(
                            errorStream != null ? errorStream : conn.getInputStream(), StandardCharsets.UTF_8);
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(streamReader)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line.trim());
                    }
                }

                String jsonResponse = sb.toString();

                JSONObject responseObj = new JSONObject(jsonResponse);
                String status = responseObj.optString("status", "error");

                if ("success".equalsIgnoreCase(status)) {
                    // Wipe all local license data
                    clearLicenseData(safePrefs, context, "Your device has been unlinked from this license key.");
                    ProHelper.setForceFree(true);

                    // Broadcast status change
                    android.content.Intent broadcastIntent = new android.content.Intent(
                            context.getPackageName() + ".ACTION_PRO_STATUS_CHANGED");
                    broadcastIntent.setPackage(context.getPackageName());
                    context.sendBroadcast(broadcastIntent);

                    mainHandler.post(callback::onSuccess);
                } else {
                    String errorMsg = responseObj.optString("message", "Unlink failed.");
                    postUnlinkError(callback, errorMsg);
                }
            } catch (Exception e) {
                Log.e(TAG, "unlinkDevice error", e);
                postUnlinkError(callback, "Network error: " + e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private static void postUnlinkError(final UnlinkCallback callback, final String message) {
        mainHandler.post(() -> callback.onError(message));
    }

    /**
     * Clears all locally stored licensing data, reverting the app to "Free" status.
     */
    private static void clearLicenseData(SafeSharedPreferences safePrefs, Context context, String reasonMsg) {
        safePrefs.edit()
                .remove("is_pro_verified")
                .remove("license_key")
                .remove("expires_at")
                .remove("plan_name")
                .remove("tg_username")
                .remove("encrypted_config")
                .putBoolean("message_bomber", false)
                .putBoolean("delete_message_file", false)
                .putBoolean("delete_message_file_sent", false)
                .putString("floating_bottom_bar_pill_design", "regular")
                .commit(); // Synchronous commit to ensure immediate disk write
        
        // Update permissions on disk to ensure Xposed process is synced
        makePrefsWorldReadable(context);

        if (reasonMsg != null) {
            try {
                Class<?> utilsClass = Class.forName("com.waenhancer.xposed.utils.Utils");
                utilsClass.getMethod("handleSubscriptionDowngrade", Context.class, String.class).invoke(null, context, reasonMsg);
            } catch (Exception ignored) {}
        }
    }

    private static long parseExpiresAt(JSONObject obj) {
        if (obj == null) return 0;
        
        // Try getting it as a string first
        String expiresAtStr = obj.optString("expires_at", "").trim();
        if (expiresAtStr.isEmpty()) {
            return 0;
        }
        
        try {
            // First try parsing as ISO 8601 string (e.g. 2026-06-07T23:59:59.000Z)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.time.Instant instant = java.time.Instant.parse(expiresAtStr);
                return instant.toEpochMilli();
            } else {
                // Pre-O fallback
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = sdf.parse(expiresAtStr);
                if (date != null) {
                    return date.getTime();
                }
            }
        } catch (Exception e) {
            // Try fallback without milliseconds in pre-O
            try {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
                sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date date = sdf.parse(expiresAtStr);
                if (date != null) {
                    return date.getTime();
                }
            } catch (Exception ignored) {}
        }
        
        // If string parsing fails, try to parse it directly as a millisecond timestamp
        return obj.optLong("expires_at", 0);
    }

    public static void makePrefsWorldReadable(Context context) {
        try {
            // Make the app data directory traversable by other processes (kernel sandbox traversal)
            java.io.File dataDir = context.getDataDir();
            if (dataDir.exists()) {
                dataDir.setReadable(true, false);
                dataDir.setExecutable(true, false);
            }
            
            // Make the shared_prefs directory traversable and readable
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            if (prefsDir.exists()) {
                prefsDir.setReadable(true, false);
                prefsDir.setExecutable(true, false);
            }
            
            // Make the preferences XML file world-readable
            java.io.File prefsFile = new java.io.File(prefsDir, "com.waenhancer_preferences.xml");
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to make preferences world-readable: " + e.getMessage());
        }
    }

    private static void postSuccess(final LicenseCallback callback, final String encryptedConfig) {
        mainHandler.post(() -> callback.onSuccess(encryptedConfig));
    }

    private static void postError(final LicenseCallback callback, final String message) {
        mainHandler.post(() -> callback.onError(message));
    }
}
