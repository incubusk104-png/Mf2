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

            // Only prompt when the store's version is STRICTLY newer than the
            // installed one.
            //
            // This is the second half of "it still tells me to update even
            // though AppGallery is up to date": AppGallery can hand back an
            // update record for a build we already have (a re-listed build, or a
            // staged rollout we are already in), and the dialog was shown on
            // receipt of the record alone — with no comparison against what is
            // actually installed. Comparing first means the prompt can only
            // appear when there is genuinely something newer to install, and a
            // version string we cannot parse is treated as "no update" rather
            // than prompting on a guess.
            val remote = runCatching { info.version_ }.getOrNull()
            val installed = runCatching {
                activity.packageManager
                    .getPackageInfo(activity.packageName, 0)
                    .versionName
            }.getOrNull()
            if (remote.isNullOrBlank() || installed.isNullOrBlank()) {
                Log.i(TAG, "Update record ignored \u2014 could not compare versions (store=$remote, installed=$installed)")
                return
            }
            if (compareVersions(remote, installed) <= 0) {
                Log.i(TAG, "Store version $remote is not newer than installed $installed \u2014 no update prompt")
                return
            }

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

    /**
     * Dotted-numeric version compare: negative when [a] is older, positive when
     * newer, 0 when equal. Non-numeric segments ("1.4.0-beta") compare as 0, and
     * an unparseable segment lands on the substring's digits the way Huawei's
     * published versionName strings are shaped.
     */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split('.', '-', '_')
        val pb = b.split('.', '-', '_')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val na = pa.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            val nb = pb.getOrNull(i)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            if (na != nb) return na.compareTo(nb)
        }
        return 0
    }
}
