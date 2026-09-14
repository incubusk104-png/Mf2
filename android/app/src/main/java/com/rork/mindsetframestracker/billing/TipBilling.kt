package com.rork.mindsetframestracker.billing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.IapApiException
import com.huawei.hms.iap.entity.ConsumeOwnedPurchaseReq
import com.huawei.hms.iap.entity.OrderStatusCode
import com.huawei.hms.iap.entity.OwnedPurchasesReq
import com.huawei.hms.iap.entity.ProductInfo
import com.huawei.hms.iap.entity.ProductInfoReq
import com.huawei.hms.iap.entity.PurchaseIntentReq
import org.json.JSONObject

sealed class TipPurchaseResult {
    data class Success(val purchaseData: String, val signature: String) : TipPurchaseResult()
    data object Cancelled : TipPurchaseResult()
    data class Error(val message: String) : TipPurchaseResult()
}

/** A tip tier with the price Huawei actually reports for this account/region. */
data class TipProduct(
    val productId: String,
    val price: String,
    val currency: String,
    val microsPrice: Long,
) {
    /** "Small Tip" / "Medium Tip" / "Large Tip" — derived from the product id. */
    val label: String
        get() = when (productId) {
            "tip_small" -> "Small Tip"
            "tip_medium" -> "Medium Tip"
            "tip_large" -> "Large Tip"
            else -> productId
        }
}

/**
 * Native Huawei IAP client for the tip feature.
 *
 * IMPORTANT: purchase() launches the payment sheet via the classic
 * Activity.startActivityForResult(requestCode) path — status.startResolutionForResult()
 * — NOT the Compose ActivityResultContracts.StartIntentSenderForResult
 * launcher. An earlier version tried to extract status.resolution as a
 * PendingIntent/IntentSender manually; in practice Huawei's IAP Status can
 * report hasResolution()=true with resolution=null even on a successful
 * (code 0) status, which silently broke the purchase flow. This is
 * Huawei's own documented pattern for IAP specifically — mirror it exactly,
 * the same way HuaweiAuthClient does for sign-in.
 *
 * Because of this, the result must be caught in MainActivity.onActivityResult
 * (see PURCHASE_REQUEST_CODE), not in a Composable launcher.
 *
 * ## Consumable lifecycle
 *
 * Tips are consumable products (priceType = 0). After a successful purchase,
 * the product MUST be consumed via [consumePurchase] so that:
 * 1. The user can buy the same tip again in the future.
 * 2. Huawei's system does not flag the product as "already owned".
 *
 * [handlePurchaseResult] automatically consumes on success.
 *
 * ## Error-code mapping (verified against the bundled IAP SDK 6.13.0.300)
 *
 * The previous implementation compared returnCode against hardcoded 0 / -1 /
 * else. That was wrong in two ways:
 *  - ORDER_STATE_CANCEL is 60000, not -1 (ORDER_STATE_FAILED is -1). A user
 *    cancelling the payment sheet was reported as a failure.
 *  - 60002 is ORDER_STATE_IAP_NOT_ACTIVATED (IAP not enabled in AppGallery
 *    Connect, or a wrong appid/cpid), not a generic failure. The user saw the
 *    bare "Purchase failed with code: 60002" snackbar from the screenshot.
 *
 * The mapping below uses the SDK constants and gives each code a message the
 * user can actually act on.
 */
object TipBilling {

    private const val TAG = "TipBilling"

    /** Request code for the HMS IAP purchase intent — handled in MainActivity.onActivityResult. */
    const val PURCHASE_REQUEST_CODE = 8889

    /** Request code for IAP environment readiness (sign-in to Huawei ID). */
    const val ENV_READY_REQUEST_CODE = 8892

    val KNOWN_TIP_PRODUCT_IDS = setOf("tip_small", "tip_medium", "tip_large")

    /**
     * Queries Huawei for the localized price of each tip tier. Returns a map
     * keyed by product id, or an empty map when the query fails (the sheet
     * then falls back to its built-in labels).
     */
    fun fetchTipProducts(
        context: Context,
        onResult: (Map<String, TipProduct>) -> Unit,
    ) {
        runCatching {
            val req = ProductInfoReq().apply {
                priceType = 0 // consumable
                productIds = KNOWN_TIP_PRODUCT_IDS.toList()
            }
            Iap.getIapClient(context).obtainProductInfo(req)
                .addOnSuccessListener { result ->
                    val products = result?.productInfoList.orEmpty()
                        .mapNotNull { info: ProductInfo ->
                            val id = info.productId ?: return@mapNotNull null
                            if (id !in KNOWN_TIP_PRODUCT_IDS) return@mapNotNull null
                            TipProduct(
                                productId = id,
                                price = info.price ?: "",
                                currency = info.currency ?: "",
                                microsPrice = info.microsPrice,
                            )
                        }
                        .associateBy { it.productId }
                    Log.i(TAG, "obtainProductInfo returned ${products.size} tip product(s)")
                    onResult(products)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "obtainProductInfo failed: ${e.message}")
                    onResult(emptyMap())
                }
        }.onFailure {
            Log.w(TAG, "obtainProductInfo unavailable: ${it.message}")
            onResult(emptyMap())
        }
    }

    /**
     * Checks whether the IAP environment is ready (HMS Core installed, user
     * signed in to Huawei ID, region supports IAP). If a resolution is needed
     * (e.g. sign-in), it is launched automatically and [onReady] is called
     * when the user returns — the caller should then retry the purchase.
     */
    fun checkEnvironment(
        activity: Activity,
        onReady: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        runCatching {
            Iap.getIapClient(activity).isEnvReady
                .addOnSuccessListener {
                    Log.i(TAG, "IAP environment is ready")
                    onReady()
                }
                .addOnFailureListener { e ->
                    val apiException = e as? IapApiException
                    val status = apiException?.status
                    if (status != null && status.hasResolution()) {
                        Log.i(TAG, "IAP env not ready — launching resolution (code=${status.statusCode})")
                        try {
                            status.startResolutionForResult(activity, ENV_READY_REQUEST_CODE)
                        } catch (ex: IntentSender.SendIntentException) {
                            Log.e(TAG, "isEnvReady resolution failed", ex)
                            onError("Could not set up Huawei payment. Try updating HMS Core.")
                        }
                    } else {
                        Log.w(TAG, "IAP env not ready, no resolution: ${e.message}")
                        onError("Huawei payment is not available. Make sure HMS Core is installed and you're signed in to your Huawei ID.")
                    }
                }
        }.onFailure { e ->
            Log.e(TAG, "isEnvReady threw: ${e.message}", e)
            onError("Could not check Huawei payment availability.")
        }
    }

    fun purchase(
        activity: Activity,
        productId: String,
        onError: (String) -> Unit,
    ) {
        // First ensure the IAP environment is ready (user signed in, HMS Core ok).
        // Without this gate, purchases fail with ORDER_STATE_IAP_NOT_ACTIVATED
        // (60002) or ORDER_HWID_NOT_LOGIN (60050) on devices where the user has
        // HMS Core but hasn't signed in yet — the exact failure from the
        // screenshot.
        checkEnvironment(
            activity = activity,
            onReady = {
                doPurchase(activity, productId, onError)
            },
            onError = onError,
        )
    }

    /** Internal: actually create the purchase intent after env check passes. */
    private fun doPurchase(
        activity: Activity,
        productId: String,
        onError: (String) -> Unit,
    ) {
        try {
            val req = PurchaseIntentReq().apply {
                priceType = 0 // 0 = consumable product (tips)
                this.productId = productId
                reservedInfor = "mindset_frames_tip"
            }

            Iap.getIapClient(activity).createPurchaseIntent(req)
                .addOnSuccessListener { result ->
                    val status = result.status
                    Log.i(
                        TAG,
                        "createPurchaseIntent result for $productId — " +
                            "statusCode=${status.statusCode}, message=${status.statusMessage}",
                    )
                    if (status.hasResolution()) {
                        try {
                            status.startResolutionForResult(activity, PURCHASE_REQUEST_CODE)
                        } catch (e: IntentSender.SendIntentException) {
                            Log.e(TAG, "startResolutionForResult failed for $productId", e)
                            onError("Could not open the payment sheet. Try again.")
                        }
                    } else {
                        onError(
                            "Payment unavailable (code ${status.statusCode}): " +
                                "${status.statusMessage ?: "no details"}",
                        )
                    }
                }
                .addOnFailureListener { e ->
                    val code = (e as? IapApiException)?.statusCode
                    Log.e(TAG, "createPurchaseIntent failed for $productId (code=$code)", e)
                    onError(messageForCode(code, e.message))
                }
        } catch (e: Exception) {
            Log.e(TAG, "createPurchaseIntent threw synchronously for $productId", e)
            onError(e.message ?: "Could not start the purchase. Try again.")
        }
    }

    /** Call from Activity.onActivityResult when requestCode == PURCHASE_REQUEST_CODE. */
    fun handlePurchaseResult(
        context: Context,
        data: Intent?,
        onResult: (TipPurchaseResult) -> Unit,
    ) {
        val purchaseResultInfo = Iap.getIapClient(context).parsePurchaseResultInfoFromIntent(data)
        when (purchaseResultInfo.returnCode) {
            OrderStatusCode.ORDER_STATE_SUCCESS -> { // 0
                // Consume the purchase immediately so it can be re-purchased.
                consumePurchase(context, purchaseResultInfo.inAppPurchaseData)
                onResult(
                    TipPurchaseResult.Success(
                        purchaseResultInfo.inAppPurchaseData,
                        purchaseResultInfo.inAppDataSignature,
                    ),
                )
            }
            OrderStatusCode.ORDER_STATE_CANCEL -> onResult(TipPurchaseResult.Cancelled) // 60000
            OrderStatusCode.ORDER_PRODUCT_OWNED -> { // 60051 — already owned (e.g. consume failed earlier)
                // Consume the owned purchase so the user can buy again, then
                // treat it as a silent cancel — the user didn't pay just now.
                consumePurchase(context, purchaseResultInfo.inAppPurchaseData)
                onResult(TipPurchaseResult.Cancelled)
            }
            else -> onResult(
                TipPurchaseResult.Error(messageForCode(purchaseResultInfo.returnCode, null)),
            )
        }
    }

    /**
     * Maps a Huawei IAP return code to a user-actionable message. Verified
     * against the constants in the bundled IAP SDK 6.13.0.300.
     */
    private fun messageForCode(code: Int?, fallback: String?): String = when (code) {
        OrderStatusCode.ORDER_STATE_IAP_NOT_ACTIVATED -> // 60002
            "In-app purchases aren't enabled for this app yet. Please update the app or try again later."
        OrderStatusCode.ORDER_STATE_NET_ERROR -> // 60005
            "Network error — check your connection and try again."
        OrderStatusCode.ORDER_HWID_NOT_LOGIN -> // 60050
            "Please sign in to your Huawei ID first."
        OrderStatusCode.ORDER_PRODUCT_OWNED -> // 60051
            "You already own this tip — it's being restored. Try again in a moment."
        OrderStatusCode.ORDER_ACCOUNT_AREA_NOT_SUPPORTED -> // 60054
            "In-app purchases aren't available in your region yet."
        OrderStatusCode.ORDER_STATE_PRODUCT_COUNTRY_NOT_SUPPORTED -> // 60007
            "This tip isn't available in your region yet."
        OrderStatusCode.ORDER_STATE_CALLS_FREQUENT -> // 60004
            "Too many attempts — wait a moment and try again."
        OrderStatusCode.ORDER_STATE_PRODUCT_INVALID -> // 60003
            "This product isn't available. Please update the app."
        OrderStatusCode.ORDER_STATE_PARAM_ERROR -> // 60001
            "The purchase request was invalid. Please update the app."
        else -> fallback ?: "Purchase failed with code: $code"
    }

    /**
     * Consumes a tip purchase so the same product can be bought again.
     *
     * For consumable products (priceType = 0), Huawei requires calling
     * `consumeOwnedPurchase` after a successful purchase. Without this,
     * the product stays in "owned" state and subsequent purchases fail.
     */
    fun consumePurchase(context: Context, inAppPurchaseData: String?) {
        if (inAppPurchaseData.isNullOrBlank()) {
            Log.w(TAG, "consumePurchase: no purchaseData — skipping")
            return
        }
        val purchaseToken = runCatching {
            JSONObject(inAppPurchaseData).optString("purchaseToken")
        }.getOrNull()

        if (purchaseToken.isNullOrBlank()) {
            Log.w(TAG, "consumePurchase: no purchaseToken in data — skipping")
            return
        }

        val req = ConsumeOwnedPurchaseReq().apply {
            this.purchaseToken = purchaseToken
        }

        Iap.getIapClient(context).consumeOwnedPurchase(req)
            .addOnSuccessListener {
                Log.i(TAG, "Tip consumed successfully (token=$purchaseToken)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "consumeOwnedPurchase failed: ${e.message}", e)
            }
    }

    /**
     * Consumes any un-consumed tip purchases left from previous sessions.
     * Call at app startup to clean up orphaned consumables that were bought
     * but not properly consumed (e.g. process killed between purchase and
     * consume).
     */
    fun consumeUnfinishedPurchases(context: Context) {
        runCatching {
            val req = OwnedPurchasesReq().apply {
                priceType = 0 // consumable
            }
            Iap.getIapClient(context).obtainOwnedPurchases(req)
                .addOnSuccessListener { result ->
                    val dataList = result?.inAppPurchaseDataList.orEmpty()
                    if (dataList.isEmpty()) {
                        Log.i(TAG, "No un-consumed tip purchases found")
                        return@addOnSuccessListener
                    }
                    Log.i(TAG, "Found ${dataList.size} un-consumed tip(s) — consuming now")
                    for (purchaseData in dataList) {
                        consumePurchase(context, purchaseData)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "obtainOwnedPurchases (consumable) failed: ${e.message}")
                }
        }.onFailure {
            Log.w(TAG, "consumeUnfinishedPurchases unavailable: ${it.message}")
        }
    }
}