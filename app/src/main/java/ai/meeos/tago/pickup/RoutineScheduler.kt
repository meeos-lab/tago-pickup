package ai.meeos.tago.pickup

import ai.meeos.tago.R
import ai.meeos.tago.app.Question
import ai.meeos.tago.app.Services
import java.util.Calendar

/**
 * 요일 루틴: 정해진 요일·시각에 한 번 "타고 링크를 보낼까요?"를 묻는다 (리텐션 장치).
 * 시연 데이터: 지역의 첫 가족(아이), 화·목 21:50 '학원 끝날 시간'. 실제 제품은 운전자가 등록한다.
 */
class RoutineScheduler(private val s: Services, private val pickup: PickupController, private val blocked: () -> Boolean) {
    data class Routine(val member: String, val days: Set<Int>, val hour: Int, val minute: Int, val label: String)

    val routines: List<Routine>
        get() = listOf(Routine(s.region.members.first().name, setOf(Calendar.TUESDAY, Calendar.THURSDAY), 21, 50, s.texts[R.string.routine_after_school]))

    var asked: Routine? = null
        private set
    private var promptedOn = ""            // 같은 날 두 번 묻지 않게

    /** 10초마다: 루틴 시각 뒤 10분 안이면 한 번 묻는다 */
    fun check() {
        if (pickup.active || s.dialog.awaiting || blocked()) return
        val now = Calendar.getInstance()
        val today = "%tF".format(now)
        if (promptedOn == today) return
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        routines.firstOrNull { r -> now.get(Calendar.DAY_OF_WEEK) in r.days && minutes - (r.hour * 60 + r.minute) in 0..9 }?.let { prompt(it) }
    }

    fun prompt(r: Routine) {
        if (pickup.active || s.dialog.awaiting) return
        promptedOn = "%tF".format(Calendar.getInstance())
        asked = r
        val t = s.texts
        s.dialog.ask(Question(Question.Kind.ROUTINE, t[R.string.btn_yes_send], t[R.string.btn_not_today], t[R.string.listen_routine], onAnswer = ::answer),
            t[R.string.say_routine_prompt, r.member, r.label])
    }

    private fun answer(yes: Boolean) {
        val r = asked ?: return
        asked = null
        if (yes) s.region.members.firstOrNull { it.name == r.member }?.let { pickup.start(it) }
        else s.say(s.texts[R.string.say_routine_skip])
        s.render()
    }
}
