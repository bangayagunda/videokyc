package com.videokyc.sdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
/**
 * VideoKYC SDK
 *
 * Project Owner: Misbahul Kadery
 * Phone: 9876543210
 * Email: abc@gmail.com
 * Social Media: https://in.linkedin.com/in/misbahul-kadery-66248b185
 *
 * Version: 1.0.0
 */
object KycSdk {
    const val VERSION = "1.0.0"

    fun start(activity: Activity, request: KycSdkRequest) {
        activity.startActivityForResult(
            Intent(activity, KycActivity::class.java).putExtra(KycActivity.EXTRA_REQUEST, request),
            KycActivity.REQUEST_CODE
        )
    }

    fun start(context: Context, request: KycSdkRequest) {
        context.startActivity(Intent(context, KycActivity::class.java).putExtra(KycActivity.EXTRA_REQUEST, request))
    }
}

class KycSdkContract : ActivityResultContract<KycSdkRequest, KycSdkResult?>() {
    override fun createIntent(context: Context, input: KycSdkRequest): Intent =
        Intent(context, KycActivity::class.java).putExtra(KycActivity.EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): KycSdkResult? =
        if (resultCode == Activity.RESULT_OK) intent?.getParcelableExtra(KycActivity.EXTRA_RESULT) else null
}
