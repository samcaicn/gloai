package com.wangzan101.mika.voice.model

import com.wangzan101.mika.calls.VolcRealtimeException
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.TtsError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class VoiceErrorCategory {
    INCOMPLETE_CONFIG,
    MISSING_CREDENTIALS,
    AUTH_FAILED,
    UNSUPPORTED,
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    PROTOCOL_ERROR,
    SYSTEM_UNAVAILABLE,
    CANCELLED,
}

sealed class VoiceModelError(
    val category: VoiceErrorCategory,
    val detail: String,
    cause: Throwable? = null,
) : Exception(detail, cause) {
    val isFailure: Boolean get() = category != VoiceErrorCategory.CANCELLED

    class IncompleteConfig(detail: String) :
        VoiceModelError(VoiceErrorCategory.INCOMPLETE_CONFIG, detail)

    class MissingAccount(accountId: String) :
        VoiceModelError(VoiceErrorCategory.MISSING_CREDENTIALS, "Voice account is no longer available: $accountId")

    class MissingCredentials(detail: String = "Voice account credentials are missing or incomplete") :
        VoiceModelError(VoiceErrorCategory.MISSING_CREDENTIALS, detail)

    class Unsupported(val requested: VoiceCapability, val protocol: String? = null) :
        VoiceModelError(
            VoiceErrorCategory.UNSUPPORTED,
            "Voice capability $requested is not supported${protocol?.let { " for protocol $it" }.orEmpty()}",
        )

    class Protocol(category: VoiceErrorCategory, detail: String, cause: Throwable? = null) :
        VoiceModelError(category, detail, cause)

    class Cancelled(cause: Throwable? = null) :
        VoiceModelError(VoiceErrorCategory.CANCELLED, "Voice operation cancelled", cause)

    companion object {
        /** Maps existing protocol errors without changing their request or session implementations. */
        fun from(error: Throwable): VoiceModelError = when (error) {
            is VoiceModelError -> error
            is TimeoutCancellationException -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            is CancellationException -> Cancelled(error)
            is SocketTimeoutException -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            is UnknownHostException -> Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
            is ModelError.Auth, is ModelError.SignInExpired, is ModelError.AccountPlanNotAllowed ->
                Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
            is ModelError.IllegalHeader -> Protocol(VoiceErrorCategory.INCOMPLETE_CONFIG, detailOf(error), error)
            is ModelError.RateLimited -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
            is ModelError.ModelNotFound, is ModelError.ModelNotAvailable, is ModelError.ToolsUnsupported ->
                Protocol(VoiceErrorCategory.UNSUPPORTED, detailOf(error), error)
            is ModelError.Network -> networkError(error)
            is ModelError.Server -> when (error.status) {
                401, 403 -> Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
                408, 504 -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
                429 -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
                else -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            }
            is ModelError.BadResponse -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            is TtsError.Unsupported -> Protocol(VoiceErrorCategory.UNSUPPORTED, detailOf(error), error)
            is TtsError.BadAudio -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            is VolcRealtimeException -> when (error.kind) {
                VolcRealtimeException.Kind.AUTH -> Protocol(VoiceErrorCategory.AUTH_FAILED, detailOf(error), error)
                VolcRealtimeException.Kind.RATE_LIMITED -> Protocol(VoiceErrorCategory.RATE_LIMITED, detailOf(error), error)
                VolcRealtimeException.Kind.TIMEOUT -> Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
                VolcRealtimeException.Kind.NETWORK, VolcRealtimeException.Kind.BACKPRESSURE ->
                    Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
                VolcRealtimeException.Kind.PROTOCOL, VolcRealtimeException.Kind.PROVIDER ->
                    Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
            }
            is IOException -> networkError(error)
            is IllegalArgumentException -> Protocol(VoiceErrorCategory.INCOMPLETE_CONFIG, detailOf(error), error)
            else -> Protocol(VoiceErrorCategory.PROTOCOL_ERROR, detailOf(error), error)
        }

        private fun networkError(error: Throwable): VoiceModelError {
            val causes = generateSequence(error) { it.cause }
            if (causes.any { it is SocketTimeoutException }) {
                return Protocol(VoiceErrorCategory.TIMEOUT, detailOf(error), error)
            }
            return Protocol(VoiceErrorCategory.NETWORK, detailOf(error), error)
        }

        private fun detailOf(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
