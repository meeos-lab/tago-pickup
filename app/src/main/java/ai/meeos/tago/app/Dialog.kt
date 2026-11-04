package ai.meeos.tago.app

import ai.meeos.tago.voice.VoiceAssistant

/**
 * 운전자에게 묻는 질문 하나. 버튼(칩)과 음성("응"/"다른 곳")이 같은 onAnswer 로 들어온다.
 * onText 가 있으면 예/아니오가 아니라 말한 문장을 그대로 받는다(주문 듣기).
 */
class Question(
    val kind: Kind,
    val yesLabel: String? = null,
    val noLabel: String? = null,
    val listenHint: String,
    val onAnswer: ((Boolean) -> Unit)? = null,
    val onText: ((String) -> Unit)? = null,
) {
    enum class Kind { POINT, ROUTINE, REST, PARKING, DRIVETHRU, ORDER_TEXT, ORDER_CONFIRM, CRASH }   // CRASH: 충돌 뒤 "괜찮으세요?"
}

/** 한 번에 한 질문만. 질문을 말한 뒤(VoiceAssistant.shouldListen) 듣고, 답을 받으면 칩을 감추고 듣기를 멈춘다 */
class Dialog(private val voice: VoiceAssistant, private val onChanged: () -> Unit) {
    var pending: Question? = null
        private set
    val awaiting: Boolean get() = pending != null

    fun ask(q: Question, speech: String? = null) {
        pending = q
        onChanged()
        if (speech != null) voice.say(speech)
    }

    /** 운전자의 예/아니오(버튼 또는 음성) */
    fun answer(yes: Boolean) {
        val q = pending ?: return
        pending = null
        voice.stopListening()
        onChanged()
        q.onAnswer?.invoke(yes)
    }

    /** 자유 문장(주문). 받는 질문이 없으면 false */
    fun text(t: String): Boolean {
        val q = pending ?: return false
        val h = q.onText ?: return false
        pending = null
        voice.stopListening()
        onChanged()
        h(t)
        return true
    }

    fun cancel() {
        if (pending == null) return
        pending = null
        voice.stopListening()
        onChanged()
    }
}
