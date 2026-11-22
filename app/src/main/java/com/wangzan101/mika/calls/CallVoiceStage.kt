package com.wangzan101.mika.calls

/**
 * What Mika is doing in a call right now: hearing the caller, working out a reply, or saying it.
 * Read from the session's own events, so both standard and realtime calls report it the same way.
 */
enum class CallVoiceStage {
    Listening, Thinking, Speaking;

    companion object {
        fun after(stage: CallVoiceStage, event: VolcRealtimeEvent): CallVoiceStage = when (event) {
            VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.AssistantInterrupted,
            VolcRealtimeEvent.UserTranscriptionEmpty, VolcRealtimeEvent.UserTranscriptionFailed,
            VolcRealtimeEvent.AssistantAudioEnded -> Listening
            VolcRealtimeEvent.UserSpeechEnded -> Thinking
            is VolcRealtimeEvent.UserTranscript -> if (event.isFinal && event.isCurrentTurn) Thinking else stage
            VolcRealtimeEvent.AssistantAudioStarted, is VolcRealtimeEvent.Audio -> Speaking
            else -> stage
        }
    }
}
