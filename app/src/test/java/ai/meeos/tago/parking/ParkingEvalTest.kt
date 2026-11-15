package ai.meeos.tago.parking

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 주차 층 추정 평가(11-13 채점 2바퀴 — "정확도의 근거"): **지금 앱 코드(ParkingTracker) 그대로**에 잡음 섞인 합성 주행을 넣어
 * 층 일치·범위 포함·층 모름·자신 있게 틀림 비율을 센다. 고정 씨앗(칸마다 200회)이라 다시 돌려도 같은 표가 나온다.
 *
 * 합성 주행(1초 간격, 앱과 같은 틱): 좋은 GPS 로 접근 20 s → 입구에서 GPS 끊김 → 주차장 모양별 주행 → 기어 P.
 *  - SPIRAL k: 나선 경사로 k 바퀴(주차장마다 반지름 5.5~9 m 하나·바퀴마다 ±5 %, 속도 2.2~3.8 m/s, 조향 잡음 σ4°, 바퀴 사이 직진 3~10 s) → 정답 B k
 *  - SPIRAL_CIRCLING k: 위와 같고, 층 안에서 자리를 찾아 큰 원(반지름 10~14 m, 3~4.5 m/s) 한 바퀴를 더 돈다 → 정답 B k
 *  - SWITCHBACK k: 일자 경사로 + 반 바퀴 되돌기(층마다 90°×2, 반지름 6 m) → 정답 B k(회전으로는 셀 수 없는 모양)
 *  - FLAT: 일자 경사로로 B1 에 내려가 층 안 직각 회전 2~4번 → 정답 B1(회전으로는 셀 수 없는 모양)
 * 건물 정보(방법 C): 없음 / 맞음(지하 층수 = k~k+2) / 틀림(지하 층수 = k−1, 실제보다 얕게 — 오래된 대장 등 가정).
 * 이것은 **합성 대본이다** — 실차·실제 주차장 측정이 아니고, 별도 연구(docs/parking-floor-preregistration.md)의 학습기와도 다르다.
 * 표는 app/build/reports/park-eval.md 에 쓴다(docs/dev/40 A-28 에 옮겨 적음).
 */
class ParkingEvalTest {
    private companion object { const val CIRCLING_WRONG_MAX_PCT = 0 }   // 채점 3바퀴 뒤 기준선(자리 찾기 원 칸의 자신 있게 틀림 %)
    private enum class Shape { SPIRAL, SPIRAL_CIRCLING, SWITCHBACK, FLAT }
    private enum class Bldg { NONE, RIGHT, WRONG }
    private data class Tick(val v: Double, val steer: Double, val fix: Boolean)

    private fun wheel(r: Double) = Math.toDegrees(atan(ParkingConst.WHEELBASE_M / r)) * ParkingConst.STEER_RATIO

    private fun MutableList<Tick>.turn(rnd: Random, deg: Double, r: Double, v: Double, sign: Int, noise: Double = 4.0) {
        val n = (deg * PI / 180 * r / v).roundToInt().coerceAtLeast(1)
        repeat(n) { add(Tick(v, sign * wheel(r) + rnd.nextGaussian() * noise, false)) }
    }

    private fun MutableList<Tick>.straight(rnd: Random, sec: Int, v: Double) { repeat(sec) { add(Tick(v, rnd.nextGaussian() * 6.0, false)) } }

    private fun Random.nextGaussian(): Double {   // Box-Muller
        val u = nextDouble().coerceAtLeast(1e-12); val w = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * PI * w)
    }

    private fun drive(shape: Shape, k: Int, rnd: Random): List<Tick> {
        val t = mutableListOf<Tick>()
        repeat(20) { t += Tick(8.0, 0.0, true) }             // 좋은 GPS 로 접근
        repeat(10) { t += Tick(4.0, 0.0, false) }            // 입구: GPS 끊김
        val sign = if (rnd.nextBoolean()) 1 else -1
        when (shape) {
            Shape.SPIRAL, Shape.SPIRAL_CIRCLING -> { val r0 = 5.5 + rnd.nextDouble() * 3.5; repeat(k) {   // 주차장마다 반지름 하나, 바퀴마다 ±5 %(채점 2바퀴 첫 판은 바퀴마다 5.5~9 m 를 새로 뽑았다 — 한 주차장에서 그렇게 달라지지 않아 고침)
                t.turn(rnd, 360.0, r0 * (0.95 + rnd.nextDouble() * 0.1), 2.2 + rnd.nextDouble() * 1.6, sign)
                t.straight(rnd, 3 + rnd.nextInt(8), 3.0)
            } }
            Shape.SWITCHBACK -> repeat(k) {
                t.straight(rnd, 8 + rnd.nextInt(6), 3.0)
                t.turn(rnd, 90.0, 6.0, 2.5, sign); t.straight(rnd, 2, 2.5); t.turn(rnd, 90.0, 6.0, 2.5, sign)
            }
            Shape.FLAT -> t.straight(rnd, 12 + rnd.nextInt(6), 3.0)
        }
        // 층 안: 직각 회전 2~4번(방향 무작위) + 직진, 자리 찾기 큰 원(SPIRAL_CIRCLING 만)
        repeat(2 + rnd.nextInt(3)) { t.straight(rnd, 4 + rnd.nextInt(9), 3.0); t.turn(rnd, 90.0, 5.0, 2.5, if (rnd.nextBoolean()) 1 else -1, 6.0) }
        if (shape == Shape.SPIRAL_CIRCLING) t.turn(rnd, 360.0, 10.0 + rnd.nextDouble() * 4, 3.0 + rnd.nextDouble() * 1.5, if (rnd.nextBoolean()) 1 else -1, 6.0)
        t.straight(rnd, 3, 1.5)
        return t
    }

    private fun run(ticks: List<Tick>, range: FloorRange?): ParkRecord? {
        val tr = ParkingTracker { _, _ -> range }
        var ms = 0L
        for (x in ticks) { ms += 1000; tr.tick(ms, if (x.fix) GpsFix(37.5, 127.0, 8.0, 1.0) else null, x.v, x.steer, parked = false, reversing = false) }
        repeat(2) { ms += 1000; tr.tick(ms, null, 0.0, 0.0, parked = true, reversing = false) }
        return tr.record
    }

    private class Tally { var n = 0; var exact = 0; var rangeHit = 0; var rangeMiss = 0; var unknown = 0; var wrong = 0; var noRecord = 0; var rangeWidth = 0 }

    /** 한 칸 = (모양, 층 수, 건물 정보) 200회. 결과를 표 한 줄로 */
    private fun cell(shape: Shape, k: Int, b: Bldg, n: Int = 200): Tally {
        val rnd = Random(shape.ordinal * 1000 + k * 10 + b.ordinal)
        val truth = -k
        val tally = Tally()
        repeat(n) {
            val range = when (b) { Bldg.NONE -> null; Bldg.RIGHT -> FloorRange(k + rnd.nextInt(3), 10); Bldg.WRONG -> FloorRange((k - 1).coerceAtLeast(1), 10) }
            val r = run(drive(shape, k, rnd), range)
            tally.n++
            when {
                r == null -> tally.noRecord++
                r.floor == null -> tally.unknown++
                r.alt != null -> { val lo = minOf(r.floor, r.alt); val hi = maxOf(r.floor, r.alt); tally.rangeWidth += hi - lo + 1
                    if (truth in lo..hi) tally.rangeHit++ else tally.rangeMiss++ }
                r.floor == truth -> tally.exact++
                else -> tally.wrong++
            }
        }
        return tally
    }

    @Test fun evaluateAndWriteReport() {
        val rows = mutableListOf<String>()
        var spiralWrongNoBldg = 0; var spiralN = 0
        val guards = mutableListOf<String>()   // 기준선: 아래 조건이 깨지면 실패(채점 2바퀴에서 고친 두 가지가 되돌아가지 않게)
        fun pct(a: Int, n: Int) = if (n == 0) "–" else "%.1f".format(100.0 * a / n)
        for (shape in Shape.entries) for (k in if (shape == Shape.FLAT) listOf(1) else listOf(1, 2, 3, 4)) for (b in Bldg.entries) {
            val t = cell(shape, k, b)
            if (shape == Shape.SPIRAL && b == Bldg.NONE) { spiralWrongNoBldg += t.wrong; spiralN += t.n }
            if (shape == Shape.SPIRAL && t.wrong > 0) guards += "나선 $k·$b 자신 있게 틀림 ${t.wrong}"            // 건물 정보가 얕아도 잘라 내지 않는다
            if (shape == Shape.SPIRAL && b != Bldg.WRONG && t.exact < t.n) guards += "나선 $k·$b 층 맞음 ${t.exact}/${t.n}"
            if ((shape == Shape.SWITCHBACK || shape == Shape.FLAT) && t.wrong + t.rangeMiss > 0) guards += "$shape $k·$b 틀림 ${t.wrong + t.rangeMiss}"   // 셀 수 없는 모양은 '층 모름'
            if (shape == Shape.SPIRAL_CIRCLING && t.wrong * 100 > t.n * CIRCLING_WRONG_MAX_PCT) guards += "자리 찾기 원 $k·$b 자신 있게 틀림 ${t.wrong}"   // 채점 3바퀴
            if (t.rangeMiss > 0) guards += "$shape $k·$b 범위가 정답 놓침 ${t.rangeMiss}"   // 채점 3바퀴 뒤: 범위는 늘 정답을 포함해야 한다(이 합성 대본 안에서)
            val ranged = t.rangeHit + t.rangeMiss
            rows += "| $shape | B$k | $b | ${t.n} | ${pct(t.exact, t.n)} | ${pct(t.rangeHit, t.n)} | ${pct(t.rangeMiss, t.n)} | ${pct(t.unknown + t.noRecord, t.n)} | **${pct(t.wrong, t.n)}** | ${if (ranged == 0) "–" else "%.1f".format(t.rangeWidth.toDouble() / ranged)} |"
        }
        val md = buildString {
            appendLine("| 모양 | 정답 | 건물 정보 | 회 | 층 맞음 % | 범위가 정답 포함 % | 범위가 정답 놓침 % | 층 모름·기록 없음 % | **자신 있게 틀림 %** | 범위 폭(층) |")
            appendLine("|---|---|---|---:|---:|---:|---:|---:|---:|---:|")
            rows.forEach { appendLine(it) }
        }
        File("build/reports").mkdirs()
        File("build/reports/park-eval.md").writeText(md)
        println(md)
        assertTrue(spiralN > 0)
        assertTrue(guards.joinToString("; "), guards.isEmpty())
    }
}
