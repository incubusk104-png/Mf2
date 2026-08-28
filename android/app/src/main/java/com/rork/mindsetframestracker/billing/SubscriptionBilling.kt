package com.rork.mindsetframestracker.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.entity.PurchaseIntentReq

sealed class SubscriptionResult {
    data class Success(val purchaseData: String, val signature: String, val productId: String) : SubscriptionResult()
    data object Cancelled : SubscriptionResult()
    data class Error(val message: String) : SubscriptionResult()
}

/**
 * Real subscription purchase flow for the 4 live products:
 * mindset_premium_monthly, mindset_premium_yearly,
 * mindset_premium_founding_monthly, mindset_premium_founding_yearly.
 *
 * Follows the same resolution-for-result pattern as TipBilling —
 * priceType = 2 for subscriptions (not 0, which is consumable).
 */
object SubscriptionBilling {

    private const val TAG = "SubscriptionBilling"
    const val SUBSCRIPTION_REQUEST_CODE = 8890

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
            0 -> onResult(SubscriptionResult.Success(info.inAppPurchaseData, info.inAppDataSignature, productId))
            -1 -> onResult(SubscriptionResult.Cancelled)
            else -> onResult(SubscriptionResult.Error("Purchase failed with code: ${info.returnCode}"))
        }
    }
}
