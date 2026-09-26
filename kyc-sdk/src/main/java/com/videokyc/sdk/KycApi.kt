package com.videokyc.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal class KycApi(
    private val config: KycSdkRequest
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var wsToken: String? = null

    suspend fun createKyc(): KycSession =
        withContext(Dispatchers.IO) {

            val customer = JSONObject().apply {
                put("customerId", config.customerId)
                put("name", config.customerName)

                if (config.dateOfBirth.isNotBlank()) {
                    put("dateOfBirth", config.dateOfBirth)
                }

                if (config.mobile.isNotBlank()) {
                    put("mobile", config.mobile)
                }

                if (config.email.isNotBlank()) {
                    put("email", config.email)
                }
            }

            val body = JSONObject().apply {
                put("companyId", config.companyId)
                put("externalUserId", config.customerId)
                put("clientReference", config.customerId)
                put(
                    "metadata",
                    JSONObject().apply {
                        put("customer", customer)
                    }
                )
            }

            execute(
                method = "POST",
                path = "/api/v1/kyc/request",
                body = body.toString()
            ).toSession()
        }

    suspend fun getSession(
        sessionId: String
    ): KycSession =
        withContext(Dispatchers.IO) {
            require(sessionId.isNotBlank()) {
                "sessionId cannot be empty"
            }
            val url = buildUrl(
                "/api/v1/kyc/sessions/${encodePath(sessionId)}"
            ) +
                    "?companyId=${encodeQuery(config.companyId)}" +
                    "&externalUserId=${encodeQuery(config.customerId)}"

            executeUrl(
                method = "GET",
                url = url,
                body = null
            ).toSession()
        }

    suspend fun refreshWsToken(
        sessionId: String,
        currentToken: String
    ): String =
        withContext(Dispatchers.IO) {
            if (currentToken.isBlank()) {
                throw KycSdkException(
                    "Current WebSocket token is empty"
                )
            }
            val body = JSONObject().apply {
                put("wsToken", currentToken)
            }
            val response = execute(
                method = "POST",
                path = "/api/v1/kyc/sessions/${encodePath(sessionId)}/ws-token",
                body = body.toString()
            )
            val data = response.optJSONObject("data")
            val token =
                data?.optString("userWsToken")
                    ?.takeIf { it.isNotBlank() }
                    ?: data?.optString("user_ws_token")
                        ?.takeIf { it.isNotBlank() }
            if (token.isNullOrBlank()) {
                throw KycSdkException(
                    "Server returned an empty realtime token"
                )
            }
            wsToken = token
            token
        }
    suspend fun cancel(sessionId: String) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("companyId", config.companyId)
            wsToken?.let {
                put("wsToken", it)
            }
        }
        execute(
            method = "POST",
            path = "/api/v1/kyc/sessions/${encodePath(sessionId)}/cancel",
            body = body.toString()
        )
    }

    fun setWsToken(token: String) {
        wsToken = token.takeIf { it.isNotBlank() }
    }
    fun getWsToken(): String? = wsToken
    private fun execute(
        method: String,
        path: String,
        body: String?
    ): JSONObject {
        return executeUrl(
            method = method,
            url = buildUrl(path),
            body = body
        )
    }
    private fun executeUrl(
        method: String,
        url: String,
        body: String?
    ): JSONObject {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        when (method.uppercase()) {
            "POST" -> {
                requestBuilder
                    .post(
                        (body ?: "{}").toRequestBody(
                            "application/json; charset=utf-8".toMediaType()
                        )
                    )
            }
            "GET" -> {
                requestBuilder.get()
            }
            else -> {
                throw KycSdkException(
                    "Unsupported HTTP method: $method"
                )
            }
        }
        val request = requestBuilder.build()
        val response = try {
            client.newCall(request).execute()
        } catch (t: Throwable) {
            throw KycSdkException(
                "Network error: ${t.message ?: "Unable to reach KYC server"}"
            )
        }

        response.use { httpResponse ->
            val statusCode = httpResponse.code
            val rawBody =httpResponse.body?.string().orEmpty()
            val json = try {
                JSONObject(
                    if (rawBody.isBlank()) "{}" else rawBody
                )
            } catch (t: Throwable) {
                throw KycSdkException(
                    "Invalid server response ($statusCode)"
                )
            }
            if (!httpResponse.isSuccessful) {
                throw KycSdkException(
                    extractErrorMessage(
                        json,
                        "KYC request failed ($statusCode)"
                    )
                )
            }
            /*
             * Some backend endpoints may return success without
             * explicitly returning { success: true }.
             *
             * Only reject explicit success=false.
             */
            if (
                json.has("success") &&
                !json.isNull("success") &&
                !json.optBoolean("success", false)
            ) {

                throw KycSdkException(
                    extractErrorMessage(
                        json,
                        "KYC request failed ($statusCode)"
                    )
                )
            }

            return json
        }
    }

    private fun extractErrorMessage(
        json: JSONObject,
        fallback: String
    ): String {
        val error = json.optJSONObject("error")
        return error?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: json.optString("message")
                .takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun JSONObject.toSession(): KycSession {
        val data =optJSONObject("data")
                ?: throw KycSdkException(
                    "Missing session data in server response"
                )
        val sessionId =data.optString("sessionId")
                .takeIf { it.isNotBlank() }
                ?: data.optString("id")
                    .takeIf { it.isNotBlank() }
                ?: throw KycSdkException(
                    "Server response does not contain sessionId"
                )
        val sessionCode =
            data.optString("sessionCode")
                .takeIf { it.isNotBlank() }
                ?: data.optString("session_code")
                    .orEmpty()
        return KycSession(
            sessionId = sessionId,
            sessionCode = sessionCode,
            status = data.optString("status", "WAITING").uppercase(),
            agentId =data.optStringOrNull("agentId")
                    ?: data.optStringOrNull("assigned_agent_id"),

            userWsToken =data.optStringOrNull("userWsToken")
                    ?: data.optStringOrNull("user_ws_token"),
            queuePosition =
                if (
                    data.has("queuePosition") &&
                    !data.isNull("queuePosition")
                ) {
                    data.optInt("queuePosition")
                } else {
                    null
                },
            finalActionCode =
                data.optStringOrNull("finalActionCode")
                    ?: data.optStringOrNull("final_action_code"),
            endedReason =
                data.optStringOrNull("endedReason")
                    ?: data.optStringOrNull("ended_reason")
        )
    }

    private fun JSONObject.optStringOrNull(
        key: String
    ): String? {
        if (!has(key) || isNull(key)) {
            return null
        }
        return optString(key)
            .takeIf { it.isNotBlank() }
    }

    private fun buildUrl(path: String): String =
        config.apiBaseUrl.trimEnd('/') +
                "/" +
                path.trimStart('/')

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name()
        )

    private fun encodePath(value: String): String =
        java.net.URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name()
        )
}

internal class KycSdkException(
    message: String
) : Exception(message)