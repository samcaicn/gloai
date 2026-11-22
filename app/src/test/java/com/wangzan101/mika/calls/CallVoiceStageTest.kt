package com.wangzan101.mika.calls

import org.junit.Assert.assertEquals
import org.junit.Test

class CallVoiceStageTest {
    private fun run(vararg events: VolcRealtimeEvent): CallVoiceStage =
        events.fold(CallVoiceStage.Listening) { stage, event -> CallVoiceStage.after(stage, event) }

    @Test fun `caller speech then a final transcript moves to thinking`() {
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.UserSpeaking))
        assertEquals(CallVoiceStage.Thinking, run(VolcRealtimeEvent.UserSpeaking, VolcRealtimeEvent.UserSpeechEnded))
        assertEquals(CallVoiceStage.Thinking, run(
            VolcRealtimeEvent.UserSpeaking,
            VolcRealtimeEvent.UserTranscript("你好", isFinal = true, isCompleteFinal = true, isCurrentTurn = true),
        ))
    }

    @Test fun `a partial transcript is still listening`() {
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.UserTranscript("你", isFinal = false)))
    }

    @Test fun `reply audio is speaking and its end goes back to listening`() {
        assertEquals(CallVoiceStage.Speaking, run(VolcRealtimeEvent.UserSpeechEnded, VolcRealtimeEvent.AssistantAudioStarted))
        assertEquals(CallVoiceStage.Speaking, run(VolcRealtimeEvent.Audio(ByteArray(4))))
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.Audio(ByteArray(4)), VolcRealtimeEvent.AssistantAudioEnded))
    }

    @Test fun `talking over the reply or hearing nothing returns to listening`() {
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.Audio(ByteArray(4)), VolcRealtimeEvent.AssistantInterrupted))
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.UserSpeechEnded, VolcRealtimeEvent.UserTranscriptionEmpty))
        assertEquals(CallVoiceStage.Listening, run(VolcRealtimeEvent.UserSpeechEnded, VolcRealtimeEvent.UserTranscriptionFailed))
    }

    @Test fun `text deltas do not move the stage`() {
        assertEquals(CallVoiceStage.Thinking, run(VolcRealtimeEvent.UserSpeechEnded, VolcRealtimeEvent.AssistantTranscript("好")))
    }
}
