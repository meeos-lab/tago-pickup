package ai.meeos.tago.voice

/**
 * 말하기 대기열(안드로이드 없이 시험하는 순수 논리). VoiceAssistant 가 쓴다.
 *
 * 긴급 안내는 오디오 포커스를 못 얻어(다른 음성·시스템 화면이 잡고 있을 때) 몇 초 기다릴 수 있다. 그동안 문장 속 거리·상태가 낡으므로
 *  - [Item.fresh] 가 있으면 [STALE_MS] 넘게 기다린 문장은 말하기 직전에 최신 상태로 다시 만든다(null = 이제 말할 필요 없음 → 건너뜀).
 *  - 같은 [Item.key] 의 새 문장이 오면 대기 중인 옛 문장을 대신한다(같은 안내를 두 번 말하지 않는다).
 *  - 못 말하고 버려지는 문장은 [Item.onDropped] 로 알린다(부른 쪽이 '말했다' 기록을 되돌려 다음 기회에 다시 말하게).
 */
class SpeechQueue(private val max: Int = MAX, private val staleMs: Long = STALE_MS) {
    class Item(
        var text: String,
        val key: String? = null,
        val fresh: (() -> String?)? = null,
        val onDropped: (() -> Unit)? = null,
        var queuedAt: Long = 0L,
    )

    private val items = ArrayDeque<Item>()
    val size: Int get() = items.size
    fun isEmpty(): Boolean = items.isEmpty()
    fun first(): Item? = items.firstOrNull()

    /** 줄 끝에 세운다. 같은 key 의 대기 문장은 버리고(알림 없음 — 새 문장이 대신한다), 상한을 넘으면 오래된 것부터 버린다 */
    fun add(item: Item, now: Long) {
        item.queuedAt = now
        if (item.key != null) items.removeAll { it.key == item.key }
        items.addLast(item)
        while (items.size > max) items.removeFirst().onDropped?.invoke()
    }

    /** 줄 맨 앞에 되돌린다(포커스 거절·포커스를 잠깐 빼앗겨 멈춘 문장). 같은 key 의 문장이 이미 줄에 있으면 그쪽이 더 새 문장이라 이것은 버린다(알림 없음) */
    fun addFirst(item: Item) {
        if (item.key != null && items.any { it.key == item.key }) return
        items.addFirst(item)
    }

    /** 다음에 말할 문장. 오래 기다린 문장은 다시 만든다(만들 게 없으면 버리고 다음 것) */
    fun poll(now: Long): Item? {
        while (items.isNotEmpty()) {
            val it = items.removeFirst()
            val f = it.fresh
            if (f != null && now - it.queuedAt > staleMs) {
                val t = f() ?: continue
                it.text = t; it.queuedAt = now
            }
            return it
        }
        return null
    }

    /** 모두 버린다(onDropped 를 부른다) */
    fun clear() {
        val old = items.toList(); items.clear()
        old.forEach { it.onDropped?.invoke() }
    }

    companion object {
        const val MAX = 3              // 대기열 상한(넘으면 오래된 것부터 버린다)
        const val STALE_MS = 5_000L    // 이만큼 기다린 문장은 말하기 전에 다시 만든다
    }
}
