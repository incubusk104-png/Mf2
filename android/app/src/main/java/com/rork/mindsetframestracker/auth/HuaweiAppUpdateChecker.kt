package com.rork.mindsetframestracker.auth

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.huawei.hms.jos.AppUpdateClient
import com.huawei.hms.jos.JosApps
import com.huawei.updatesdk.service.appmgr.bean.ApkUpgradeInfo
import com.huawei.updatesdk.service.otaupdate.CheckUpdateCallBack
import com.huawei.updatesdk.service.otaupdate.UpdateKey
import java.io.Serializable

/**
 * Checks AppGallery for newer published version, shows Huawei update
 * dialog if found. Fixes review item "Integrate version update API
 * (checkAppUpdate)". Call once per cold start. Fully best-effort — no
 * HMS Core, no listing yet, no network all fail silent.
 */
object HuaweiAppUpdateChecker {

    private const val TAG = "HuaweiAppUpdate"

    fun checkForUpdate(activity: Activity) {
        if (!HuaweiAuthClient.isHmsAvailable(activity)) return
        runCatching {
            val client: AppUpdateClient = JosApps.getAppUpdateClient(activity)
            client.checkAppUpdate(activity, UpdateCallback(activity, client))
        }.onFailure {
            Log.w(TAG, "checkAppUpdate unavailable: ${it.message}")
        }
    }

    private class UpdateCallback(
        private val activity: Activity,
        private val client: AppUpdateClient,
    ) : CheckUpdateCallBack {

        override fun onUpdateInfo(intent: Intent?) {
            val info = intent?.getSerializableExtra(UpdateKey.INFO) as? Serializable
            if (info !is ApkUpgradeInfo) return
            val mustUpdate = intent.getBooleanExtra(UpdateKey.MUST_UPDATE, false)
            runCatching {
                client.showUpdateDialog(activity, info, mustUpdate)
            }.onFailure {
                Log.w(TAG, "Failed to show update dialog: ${it.message}")
            }
        }

        override fun onMarketInstallInfo(intent: Intent?) {
            // Joint-ops market install flow — unused.
        }

        override fun onMarketStoreError(errorCode: Int) {
            Log.w(TAG, "Update check market error: $errorCode")
        }

        override fun onUpdateStoreError(errorCode: Int) {
            Log.w(TAG, "Update check store error: $errorCode")
        }
    }
}
