package com.rork.mindsetframestracker.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.IapApiException
import com.huawei.hms.iap.entity.IsSandboxActivatedReq
import com.huawei.hms.iap.entity.OrderStatusCode
import com.huawei.hms.iap.entity.OwnedPurchasesReq
import com.huawei.hms.iap.entity.PurchaseIntentReq
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject

sealed class SubscriptionResult {
    data class Success(val purchaseData: String, val signature: String, val productId: String) : SubscriptionResult()
    data object Cancelled : SubscriptionResult()
    data class Error(val message: String) : SubscriptionResult()
}

/** Outcome of a restore / owned-subscription query at app start or on demand. */
sealed class RestoreResult {
    /** An active, non-expired subscription was found. */
    data class Active(val productId: String) : RestoreResult()

    /** The store answered and there is no active subscription. */
    data object NotSubscribed : RestoreResult()

    /** IAP unavailable (no HMS, not signed in, region unsupported) — keep current state. */
    data object Unavailable : RestoreResult()
}

/**
 * Real subscription purchase flow for the 4 live products:
 * mindset_premium_monthly, mindset_premium_yearly,
 * mindset_premium_founding_monthly, mindset_premium_founding_yearly.
 *
 * Follows the same resolution-for-result pattern as TipBilling —
 * priceType = 2 for subscriptions (not 0, which is consumable).
 *
 * SANDBOX TESTING (purchase without being charged): add your Huawei ID as a
 * test account in AppGallery Connect > Users and permissions > Sandbox, and
 * upload this build with a HIGHER versionCode than the released one. Sandbox
 * accounts then see a "sandbox environment" payment sheet and are never
 * charged; subscriptions renew on an accelerated schedule. [checkSandbox]
 * logs whether the current account+build hits the sandbox.
 */
object SubscriptionBilling {

    private const val TAG = "SubscriptionBilling"
    const val SUBSCRIPTION_REQUEST_CODE = 8890

    val KNOWN_PRODUCT_IDS = setOf(
        "mindset_premium_monthly",
        "mindset_premium_yearly",
        "mindset_premium_founding_monthly",
        "mindset_premium_founding_yearly",
    )

    fun purchase(
        activity: Activity,
        productId: String,
        onError: (String) -> Unit,
    ) {
        try {
            val req = PurchaseIntentReq().apply {
                priceType = 2 // 2 = auto-renewable subscription
                this.productId = productId
                reservedInfor = "mindset_frames_subscription"
            }

            Iap.getIapClient(activity).createPurchaseIntent(req)
                .addOnSuccessListener { result ->
                    val status = result.status
                    Log.i(TAG, "createPurchaseIntent for $productId — code=${status.statusCode}")
                    if (status.hasResolution()) {
                        try {
                            status.startResolutionForResult(activity, SUBSCRIPTION_REQUEST_CODE)
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e(TAG, "startResolutionForResult failed for $productId", e)
                            onError("Could not open the payment sheet. Try again.")
                        }
                    } else {
                        onError("Payment unavailable (code ${status.statusCode}): ${status.statusMessage ?: "no details"}")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "createPurchaseIntent failed for $productId", e)
                    onError(e.message ?: "Unknown billing error")
                }
        } catch (e: Exception) {
            Log.e(TAG, "createPurchaseIntent threw synchronously for $productId", e)
            onError(e.message ?: "Could not start the purchase. Try again.")
        }
    }

    /** Call from Activity.onActivityResult when requestCode == SUBSCRIPTION_REQUEST_CODE. */
    fun handlePurchaseResult(
        context: Context,
        productId: String,
        data: Intent?,
        onResult: (SubscriptionResult) -> Unit,
    ) {
        val info = Iap.getIapClient(context).parsePurchaseResultInfoFromIntent(data)
        when (info.returnCode) {
            OrderStatusCode.ORDER_STATE_SUCCESS -> {
                // Prefer the productId inside the signed purchase data — it is
                // authoritative even if the pending id was lost to process death.
                val purchasedId = runCatching {
                    JSONObject(info.inAppPurchaseData).optString("productId")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: productId
                onResult(SubscriptionResult.Success(info.inAppPurchaseData, info.inAppDataSignature, purchasedId))
            }
            OrderStatusCode.ORDER_STATE_CANCEL -> onResult(SubscriptionResult.Cancelled)
            OrderStatusCode.ORDER_PRODUCT_OWNED -> {
                // Already subscribed (e.g. re-tap after a sandbox renewal) —
                // treat as success and let restore pick up the entitlement.
                onResult(SubscriptionResult.Success(info.inAppPurchaseData ?: "", info.inAppDataSignature ?: "", productId))
            }
            else -> onResult(SubscriptionResult.Error("Purchase failed with code: ${info.returnCode}"))
        }
    }

    /**
     * Queries Huawei IAP for currently-owned auto-renewable subscriptions.
     * Drives both "Restore purchase" and the silent entitlement check at app
     * start, so an update install (or a sandbox renewal) keeps premium in
     * sync with the store. Never throws.
     */
    suspend fun queryActiveSubscription(context: Context): RestoreResult =
        suspendCancellableCoroutine { cont ->
            runCatching {
                val req = OwnedPurchasesReq().apply { priceType = 2 }
                Iap.getIapClient(context).obtainOwnedPurchases(req)
                    .addOnSuccessListener { result ->
                        val active = result?.inAppPurchaseDataList.orEmpty().firstNotNullOfOrNull { raw ->
                            runCatching {
                                val obj = JSONObject(raw)
                                val productId = obj.optString("productId")
                                // purchaseState 0 = purchased; subIsvalid true = renewing/active
                                val purchased = obj.optInt("purchaseState", -1) == 0
                                val valid = obj.optBoolean("subIsvalid", purchased)
                                if (purchased && valid && productId in KNOWN_PRODUCT_IDS) productId else null
                            }.getOrNull()
                        }
                        if (cont.isActive) {
                            cont.resume(
                                if (active != null) RestoreResult.Active(active)
                                else RestoreResult.NotSubscribed,
                            )
                        }
                    }
                    .addOnFailureListener { e ->
                        val code = (e as? IapApiException)?.statusCode
                        Log.w(TAG, "obtainOwnedPurchases failed (code=$code): ${e.message}")
                        if (cont.isActive) cont.resume(RestoreResult.Unavailable)
                    }
            }.onFailure {
                Log.w(TAG, "queryActiveSubscription unavailable: ${it.message}")
                if (cont.isActive) cont.resume(RestoreResult.Unavailable)
            }
        }

    /**
     * Logs whether the signed-in Huawei ID + this build hit the IAP sandbox
     * (test account & versionCode higher than the released build). Purely
     * diagnostic — sandbox purchases work with zero code changes.
     */
    fun checkSandbox(context: Context) {
        runCatching {
            Iap.getIapClient(context).isSandboxActivated(IsSandboxActivatedReq())
                .addOnSuccessListener { result ->
                    Log.i(
                        TAG,
                        "IAP sandbox — accountInSandbox=${result.isSandboxUser}, " +
                            "apkInSandbox=${result.isSandboxApk}, " +
                            "apkVersionOnline=${result.versionInApk}/store=${result.versionFrMarket}",
                    )
                }
                .addOnFailureListener { Log.i(TAG, "isSandboxActivated: ${it.message}") }
        }
    }
}
