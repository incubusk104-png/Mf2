package com.rork.mindsetframestracker.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rork.mindsetframestracker.BuildConfig
import com.rork.mindsetframestracker.billing.Entitlements
import com.rork.mindsetframestracker.billing.Feature
import com.rork.mindsetframestracker.billing.SubscriptionTier
import com.rork.mindsetframestracker.data.ActivityRecord
import com.rork.mindsetframestracker.data.MindsetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strava OAuth2 client. Client ID/secret are NOT stored in this app —
 * token exchange happens through the strava-token-exchange Supabase Edge
 * Function, which reads STRAVA_CLIENT_ID / STRAVA_CLIENT_SECRET from
 * encrypted Edge Function secrets. Nothing sensitive ships in the APK.
 *
 * Gated to SubscriptionTier.REGULAR only per Entitlements — Founding tier
 * does not get Strava (deliberate cost-control decision).
 */
object StravaAuthClient {

    private const val TAG = "StravaAuthClient"

    private const val STRAVA_CLIENT_ID_PUBLIC = "a7f174d89a804f26415155772aaabe2a9cb8"
    private const val REDIRECT_URI = "mindsetframes://strava-callback"
    private const val AUTH_URL = "https://www.strava.com/oauth/mobile/authorize"

    private val EDGE_FUNCTION_URL = "${BuildConfig.SUPABASE_URL}/functions/v1/strava-token-exchange"

    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    fun buildAuthIntent(): Intent {
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", STRAVA_CLIENT_ID_PUBLIC)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", "activity:read_all")
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** Call after redirect intercepted in MainActivity.onNewIntent, with the "code" query param. */
    suspend fun exchangeCodeForToken(code: String): Result<StravaTokens> = withContext(Dispatchers.IO) {
        callEdgeFunction(
            JSONObject().apply {
                put("grantType", "authorization_code")
                put("code", code)
            },
        )
    }

    suspend fun refreshTokenIfNeeded(tokens: StravaTokens): Result<StravaTokens> = withContext(Dispatchers.IO) {
        if (tokens.expiresAt > System.currentTimeMillis() / 1000 + 300) {
            return@withContext Result.success(tokens)
        }
        callEdgeFunction(
            JSONObject().apply {
                put("grantType", "refresh_token")
                put("refreshToken", tokens.refreshToken)
            },
        )
    }

    /** Fetches recent activities from Strava API, persists them locally, and returns saved count. */
    suspend fun fetchRecentActivities(
        context: Context,
        accessToken: String,
        habitId: String,
        activityType: String,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://www.strava.com/api/v3/athlete/activities?per_page=10")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Strava fetch failed: ${response.code}"))
                }
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                val repo = MindsetRepository(context)
                var saved = 0
                
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val record = ActivityRecord(
                        id = "strava_${obj.optLong("id")}",
                        habitId = habitId,
                        source = "strava",
                        activityType = activityType,
                        timestamp = System.currentTimeMillis(),
                        durationMinutes = obj.optInt("moving_time", 0) / 60,
                        distanceMeters = obj.optDouble("distance", 0.0),
                        heartRateAvg = if (obj.has("average_heartrate")) obj.getInt("average_heartrate") else null,
                        calories = obj.optInt("calories", 0),
                    )
                    repo.saveActivityRecord(record)
                    saved++
                }
                Result.success(saved)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecentActivities failed", e)
            Result.failure(e)
        }
    }

    private fun callEdgeFunction(payload: JSONObject): Result<StravaTokens> {
        return try {
            val body = payload.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(EDGE_FUNCTION_URL)
                .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                .addHeader("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(Exception("Strava exchange failed: ${response.code}"))
                }
                val json = JSONObject(response.body?.string() ?: "{}")
                Result.success(
                    StravaTokens(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        expiresAt = json.getLong("expires_at"),
                    ),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "callEdgeFunction failed", e)
            Result.failure(e)
        }
    }

    fun canConnect(tier: SubscriptionTier): Boolean =
        Entitlements.hasAccess(tier, Feature.STRAVA)
}

data class StravaTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
)
