package com.videokyc.sdk

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class KycSdkRequest(
    val apiBaseUrl: String,
    val companyId: String,
    val customerId: String,
    val customerName: String,
    val dateOfBirth: String = "",
    val mobile: String = "",
    val email: String = "",
    val turnUrl: String = "",
    val turnUsername: String = "",
    val turnCredential: String = "",
    val title: String = "Video KYC",
    /** Shows microphone/camera controls inside the SDK call screen. Hidden by default. */
    val showMediaControls: Boolean = false
) : Parcelable

@Parcelize
data class KycSdkResult(
    val resultCode: String,
    val sessionId: String? = null,
    val sessionCode: String? = null,
    val status: String? = null,
    val finalActionCode: String? = null,
    val endedReason: String? = null,
    val message: String? = null
) : Parcelable

data class KycSession(
    val sessionId: String,
    val sessionCode: String,
    val status: String,
    val agentId: String? = null,
    val userWsToken: String? = null,
    val queuePosition: Int? = null,
    val finalActionCode: String? = null,
    val endedReason: String? = null
)
