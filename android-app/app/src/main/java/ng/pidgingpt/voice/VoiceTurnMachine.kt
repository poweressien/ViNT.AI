package ng.pidgingpt.voice

/**
 * Pure state machine for a push-to-talk voice conversation. No Android, no threads: every transition is a function of (state, event),
 * so turn-taking rules are unit-testable. Invariants (tested in tools/host-verify/VoiceHostVerify.kt):
 *  - the microphone may be open ONLY in [VoiceState.Listening];
 *  - leaving voice mode from any state ends in [VoiceState.Off];
 *  - an interrupt while Thinking/Speaking stops output and returns to Idle without starting a new recording.
 */
sealed class VoiceState {
    object Off : VoiceState()                 // voice mode inactive: nothing recording, nothing speaking
    object Idle : VoiceState()                // voice mode on, waiting for the user
    object Listening : VoiceState()           // microphone open
    object Transcribing : VoiceState()        // ASR running on the captured audio
    object Thinking : VoiceState()            // LLM generating
    object Speaking : VoiceState()            // TTS audio playing (LLM may still be generating)
    data class Error(val message: String) : VoiceState()

    val micOpen: Boolean get() = this is Listening
    val busy: Boolean get() = this is Transcribing || this is Thinking || this is Speaking
}

sealed class VoiceEvent {
    object StartVoiceMode : VoiceEvent()
    object StopVoiceMode : VoiceEvent()
    object TalkPressed : VoiceEvent()
    object TalkEnded : VoiceEvent()           // key released / silence detected / max length reached
    data class TranscriptReady(val text: String) : VoiceEvent()
    object NothingHeard : VoiceEvent()
    object FirstAudioQueued : VoiceEvent()    // first TTS chunk is playing while the LLM may still be generating
    object ReplyFinished : VoiceEvent()       // LLM done; speech may still be playing
    object SpeechFinished : VoiceEvent()
    object Interrupt : VoiceEvent()           // user taps stop
    data class Failed(val message: String) : VoiceEvent()
}

class VoiceTurnMachine(initial: VoiceState = VoiceState.Off) {
    var state: VoiceState = initial; private set
    private var llmDone = true

    /** Returns the new state. Events that make no sense in the current state are ignored (state unchanged). */
    fun on(e: VoiceEvent): VoiceState {
        val s = state
        state = when (e) {
            VoiceEvent.StartVoiceMode -> if (s is VoiceState.Off || s is VoiceState.Error) VoiceState.Idle else s
            VoiceEvent.StopVoiceMode -> VoiceState.Off
            VoiceEvent.TalkPressed -> when (s) {
                is VoiceState.Idle -> VoiceState.Listening
                is VoiceState.Thinking, is VoiceState.Speaking -> s          // must interrupt first; no overlapping turns
                else -> s
            }
            VoiceEvent.TalkEnded -> if (s is VoiceState.Listening) VoiceState.Transcribing else s
            is VoiceEvent.TranscriptReady -> if (s is VoiceState.Transcribing) { llmDone = false; VoiceState.Thinking } else s
            VoiceEvent.NothingHeard -> if (s is VoiceState.Transcribing || s is VoiceState.Listening) VoiceState.Idle else s
            VoiceEvent.FirstAudioQueued -> if (s is VoiceState.Thinking) VoiceState.Speaking else s
            VoiceEvent.ReplyFinished -> { llmDone = true; if (s is VoiceState.Thinking) VoiceState.Idle else s }   // reply with no audio (TTS missing) ends the turn
            VoiceEvent.SpeechFinished -> if (s is VoiceState.Speaking) (if (llmDone) VoiceState.Idle else VoiceState.Thinking) else s
            VoiceEvent.Interrupt -> if (s is VoiceState.Thinking || s is VoiceState.Speaking || s is VoiceState.Transcribing || s is VoiceState.Listening) VoiceState.Idle else s
            is VoiceEvent.Failed -> if (s is VoiceState.Off) s else VoiceState.Error(e.message)
        }
        return state
    }
}
