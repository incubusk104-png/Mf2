package com.rork.mindsetframestracker.billing

import android.content.Context
import android.util.Log
import com.huawei.hms.iap.Iap
import com.huawei.hms.iap.IapApiException
import com.huawei.hms.iap.entity.OrderStatusCode

/**
 * Shared mapping from a Huawei IAP return/status code to a message the user
 * can act on.
 *
 * This lives in one place because the same codes arrive from three different
 * call sites — [TipBilling] (consumables), [SubscriptionBilling] (auto-renewable
 * subscriptions) and the env-ready resolution path — and they must not drift.
 * The bug the user hit was exactly a drift of this kind: the old code compared
 * the return code against hardcoded `0 / -1 / else`, so:
 *
 *  - ORDER_STATE_CANCEL (60000) was reported as a failure, because only -1 was
 *    treated as a cancel (ORDER_STATE_FAILED is -1, not the cancel code);
 *  - 60002 — ORDER_STATE_IAP_NOT_ACTIVATED — fell through to the generic
 *    branch and surfaced as the bare "Purchase failed with code: 60002" the
 *    user saw.
 *
 * Every constant below is read off the bundled IAP SDK (6.13.0.300), so a
 * rename in the SDK is a compile error rather than a silent wrong message.
 */
object HuaweiIapErrors {

    private const val TAG = "HuaweiIapErrors"

    /**
     * True when the code means "the user backed out" and deserves no error UI.
     * Covers both the explicit cancel and the already-owned case, which is a
     * success-shaped outcome rather than a failure.
     */
    fun isSilentCancel(code: Int?): Boolean =
        code == OrderStatusCode.ORDER_STATE_CANCEL ||
            code == OrderStatusCode.ORDER_PRODUCT_OWNED

    /**
     * Maps a Huawei IAP code to a user-actionable message. [fallback] is used
     * only when the code is unknown *and* the caller supplied its own text;
     * the generic branch deliberately does NOT echo the raw numeric code,
     * because "code: 60002" tells the user nothing they can act on.
     */
    fun message(
        code: Int?,
        fallback: String? = null,
        context: String = "purchase",
    ): String = when (code) {
        OrderStatusCode.ORDER_STATE_IAP_NOT_ACTIVATED -> // 60002
            "In-app purchases aren't available for this app right now. " +
                "Please update to the latest version and try again."
        OrderStatusCode.ORDER_HWID_NOT_LOGIN -> // 60050
            "Please sign in to your Huawei ID first."
        OrderStatusCode.ORDER_STATE_NET_ERROR -> // 60005
            "Network error — check your connection and try again."
        OrderStatusCode.ORDER_STATE_CALLS_FREQUENT -> // 60004
            "Too many attempts — wait a moment and try again."
        OrderStatusCode.ORDER_STATE_PRODUCT_INVALID -> // 60003
            "This product isn't available right now. Please update the app."
        OrderStatusCode.ORDER_STATE_PARAM_ERROR -> // 60001
            "The purchase request was invalid. Please update the app."
        OrderStatusCode.ORDER_STATE_PRODUCT_COUNTRY_NOT_SUPPORTED -> // 60007
            "This item isn't available in your region yet."
        OrderStatusCode.ORDER_ACCOUNT_AREA_NOT_SUPPORTED -> // 60054
            "In-app purchases aren't available in your region yet."
        OrderStatusCode.ORDER_PRODUCT_OWNED -> // 60051
            "You already own this — it's being restored. Try again in a moment."
        OrderStatusCode.ORDER_NOT_ACCEPT_AGREEMENT -> // 60055
            "Please accept the Huawei in-app purchase agreement, then try again."
        OrderStatusCode.ORDER_PRODUCT_CONSUMED -> // 60053
            "This purchase was already used up. Please try again."
        OrderStatusCode.ORDER_STATE_FAILED -> // -1 (a real failure, NOT a cancel)
            "Huawei couldn't complete the $context. Please update HMS Core and try again."
        else -> fallback ?: "Couldn't complete the $context. Please try again."
    }

    /**
     * Logs the raw code at warning level. The numeric code is useful in logcat
     * and in a bug report, but it must never reach the user's screen.
     */
    fun log(code: Int?, what: String) {
        Log.w(TAG, "$what failed — Huawei IAP code=$code (${describe(code)})")
    }

    private fun describe(code: Int?): String = when (code) {
        OrderStatusCode.ORDER_STATE_SUCCESS -> "ORDER_STATE_SUCCESS"
        OrderStatusCode.ORDER_STATE_FAILED -> "ORDER_STATE_FAILED"
        OrderStatusCode.ORDER_STATE_CANCEL -> "ORDER_STATE_CANCEL"
        OrderStatusCode.ORDER_STATE_PARAM_ERROR -> "ORDER_STATE_PARAM_ERROR"
        OrderStatusCode.ORDER_STATE_IAP_NOT_ACTIVATED -> "ORDER_STATE_IAP_NOT_ACTIVATED"
        OrderStatusCode.ORDER_STATE_PRODUCT_INVALID -> "ORDER_STATE_PRODUCT_INVALID"
        OrderStatusCode.ORDER_STATE_CALLS_FREQUENT -> "ORDER_STATE_CALLS_FREQUENT"
        OrderStatusCode.ORDER_STATE_NET_ERROR -> "ORDER_STATE_NET_ERROR"
        OrderStatusCode.ORDER_STATE_PRODUCT_COUNTRY_NOT_SUPPORTED -> "ORDER_STATE_PRODUCT_COUNTRY_NOT_SUPPORTED"
        OrderStatusCode.ORDER_HWID_NOT_LOGIN -> "ORDER_HWID_NOT_LOGIN"
        OrderStatusCode.ORDER_PRODUCT_OWNED -> "ORDER_PRODUCT_OWNED"
        OrderStatusCode.ORDER_ACCOUNT_AREA_NOT_SUPPORTED -> "ORDER_ACCOUNT_AREA_NOT_SUPPORTED"
        else -> "unknown code"
    }
}
