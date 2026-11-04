package ai.meeos.tago.car

import ai.meeos.tago.app.AppLog
import ai.meeos.tago.app.Region
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler

/**
 * 차 신호 한 곳: Pleos Vehicle SDK(기어·와이퍼·좌석·벨트·도어·조향·브레이크·내기순환) + 표준 AAOS 속도(PERF_VEHICLE_SPEED).
 * 1초마다 readTick() 으로 읽고, 속도는 CarPropertyManager 콜백으로 받는다. 값은 모두 메인 스레드에서 바뀐다.
 */
class CarSignals(private val context: Context, private val main: Handler, private val log: AppLog) {
    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null

    var speedMs = 0f
        private set
    /** 시연 주행(convoy_sim): null 이 아니면 실제 차속 대신 이 값을 쓴다 */
    var simulatedSpeed: Float? = null
        set(v) { field = v; if (v != null) speedMs = v }
    var gear: VehicleGear? = null
        private set
    var wiper = "OFF"
        private set
    val seatOcc = mutableMapOf<SeatValue, Boolean?>()      // 2열 좌석별 착석
    val seatBelt = mutableMapOf<SeatValue, Boolean?>()     // 2열 좌석별 안전벨트
    val doorOpen = mutableMapOf<DoorValue, Int?>()         // 2열 문 위치(0 = 닫힘)
    /** 2열 문이 한 번이라도 열렸는지 — 픽업이 확정 시점·탑승 판정 뒤에 지운다 */
    var rearDoorOpened = false
    // 도로 링크 신호(도로 링크 중에만 읽는다): 조향각(낙하물 회피), 브레이크 페달(긴 내리막), 공조 내기순환(연기·매연)
    var readRoadSignals = false
    // 충돌 감지(docs/incident-judgment-design.md 10-1): 도로 링크 중 차속을 10 Hz 로 받아, 두 표본 사이 평균 감속률 ≥ 14 m/s² 이고 Δv ≥ 2.2 m/s(8 km/h),
    // 간격 0.08~0.5 s 이면 후보 → 그 뒤 0.5 s 동안 속도가 사건 전의 50 % 아래로 머물면 충돌(한 표본 튐 제외). 제동 한계(1.1 g = 10.8 m/s²)와 30 % 여유
    var onImpact: ((pre: Float, now: Float, samples: List<Pair<Long, Float>>) -> Unit)? = null
    private val fast = ArrayDeque<Pair<Long, Float>>()      // (ms, m/s) 최근 2.5초
    private var fastOn = false
    private var cand: Triple<Long, Float, Float>? = null    // 후보 (시각 ms, 사건 전 속도, 후보 속도)
    private var impactAt = 0L
    var fastDrops = 0                                       // 10 Hz 표본 손실(간격 > 250 ms) 수 — 개발 패널용
        private set
    var airbagSim: Boolean? = null                          // 시연 훅(에뮬레이터 VHAL 에 에어백 전개 속성이 없다)
    private var simUntil = 0L                               // impact_sim 표본을 넣는 동안(1.5 s) 실제 표본 무시
    var steerAngle: Float? = null
        private set
    var brakePedal: Boolean? = null
        private set
    var recirc: Boolean? = null
        private set
    // EV 충전(도로 링크 중, 5초마다): 충전구 열림(꽂음)·충전 스위치(충전 중)·배터리 %(Pleos SDK EvBattery, CAR_ENERGY).
    // 시연: 에뮬레이터 Virtual Console 은 충전 상태를 바꾸는 조작이 없어 `--es ev_sim` 훅으로 값을 넣는다(simCharge*)
    var simChargePort: Boolean? = null
    var simCharging: Boolean? = null
    var simSoc: Int? = null
    /** 외기 온도(°C, AAOS ENV_OUTSIDE_TEMPERATURE). 도로 링크 중 5초마다 읽는다. 시연 훅 temp_sim 이 덮는다 — 교량 결빙 주의(설계 §8) */
    private var envTemp: Float? = null
    var simTemp: Float? = null
    val outsideTemp: Float? get() = simTemp ?: envTemp
    private var evPort: Boolean? = null
    private var evCharging: Boolean? = null
    private var evSoc: Int? = null
    val chargePort: Boolean? get() = simChargePort ?: evPort
    val charging: Boolean? get() = simCharging ?: evCharging
    val soc: Int? get() = simSoc ?: evSoc
    private var recircArea: Any? = null                   // checkHvacRecirculationCapabilities가 준 영역 객체(그대로 넘겨야 읽힌다 — 실측)
    private var recircGetter: java.lang.reflect.Method? = null

    val driving: Boolean get() = gear == VehicleGear.GEAR_DRIVE
    val reversing: Boolean get() = gear?.name == "GEAR_REVERSE"
    val stopped: Boolean get() = !driving && speedMs < 1f

    fun start() {
        runCatching { vehicle = Vehicle(context).also { it.initialize() } }
            .onFailure { log.d("Vehicle 초기화 실패: $it") }
        // 차량 속도(PERF_VEHICLE_SPEED)는 Pleos SDK에 없어 표준 API 사용 (FAQ 안내 방식)
        runCatching {
            car = Car.createCar(context)
            cpm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            // subscribePropertyEvents 는 API 35 부터라 차량 이미지(API 34)에서는 registerCallback 을 써야 한다
            @Suppress("DEPRECATION")
            cpm?.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { log.d("속도 초기화 실패: $it") }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            (value.value as? Float)?.let { v -> main.post { if (simulatedSpeed == null) speedMs = v } }   // 시연 주행 중에는 대본 속도
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    /** 10 Hz 속도(도로 링크 중에만): 표본을 모으고 충돌 감지기를 돌린다. 에뮬레이터 실측 10.2 Hz */
    private val fastCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val v = value.value as? Float ?: return
            val ms = value.timestamp / 1_000_000
            main.post {
                if (simulatedSpeed == null) speedMs = v
                if (android.os.SystemClock.elapsedRealtime() >= simUntil) feedFast(ms, v)   // 충돌 시연 표본을 넣는 동안은 실제(VC) 표본을 섞지 않는다
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    fun setFastSpeed(on: Boolean) {
        if (on == fastOn) return
        fastOn = on
        val m = cpm ?: return
        @Suppress("DEPRECATION")
        runCatching { if (on) m.registerCallback(fastCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST) else m.unregisterCallback(fastCallback) }
            .onFailure { log.d("속도 10Hz ${if (on) "구독" else "해제"} 실패: $it") }
        fast.clear(); cand = null
    }

    /** 표본 하나를 감지기에 넣는다(실차 콜백·시연 훅 공용). 시각은 ms */
    fun feedFast(ms: Long, v: Float) {
        fast.lastOrNull()?.let { if (ms - it.first > 250) fastDrops++ }
        fast.addLast(ms to v)
        while (fast.size > 1 && ms - fast.first().first > 2500) fast.removeFirst()
        val c = cand
        if (c != null) {
            // 후보 뒤 0.5 s: 하나라도 사건 전 속도의 50 % 이상이면 튐으로 버린다. 0.5 s 를 넘기면 충돌 확정
            if (v >= 0.5f * c.second) { cand = null; return }
            if (ms - c.first >= IMPACT_HOLD_MS) {
                cand = null; impactAt = ms
                log.d("감지: 충돌 — %.1f→%.1f m/s (10Hz 표본, 0.5초 유지)".format(c.second, c.third))
                onImpact?.invoke(c.second, c.third, fast.toList())
            }
            return
        }
        if (ms - impactAt < 10_000) return                  // 10 s 에 한 번
        var k = fast.size - 2
        while (k >= 0) {
            val (tk, vk) = fast.elementAt(k)
            val dt = (ms - tk) / 1000.0
            if (dt > IMPACT_DT_MAX) break
            if (dt >= IMPACT_DT_MIN && vk - v >= IMPACT_DV && (vk - v) / dt >= IMPACT_A) { cand = Triple(ms, vk, v); return }
            k--
        }
    }

    /** 시연 훅: 속도 pre→post 충돌 표본을 감지기에 넣고 차를 post 로 세운다(VC 는 순간 변화만 가능하지만 이 훅은 실차 표본 모양과 같다) */
    fun simImpact(pre: Float, post: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        simUntil = now + 1500; fast.clear(); cand = null
        for (i in 0 until 5) feedFast(now - 700 + i * 100L, pre)
        feedFast(now - 200, post); feedFast(now - 100, post); feedFast(now, post)
        simulatedSpeed = post
        for (i in 1..6) main.postDelayed({ feedFast(android.os.SystemClock.elapsedRealtime(), post) }, i * 100L)
    }

    /** 에어백 전개(운전석·조수석). 못 읽는 차·에뮬레이터는 null(시연 훅 airbagSim 이 있으면 그 값) */
    fun readAirbag(cb: (Boolean?) -> Unit) {
        airbagSim?.let { cb(it); return }
        val v = vehicle ?: run { cb(null); return }
        runCatching {
            v.getSafety().hasSeatAirbagBeenDeployed(SeatArea(SeatValue.ROW_1_LEFT), { b -> main.post { cb(b) } }, { main.post { cb(null) } })
        }.onFailure { cb(null) }
    }

    companion object {
        const val IMPACT_A = 14.0         // m/s² — 제동으로는 못 내는 감속률(ETSI TR 103 460 표 3: 승용차 12 m/s² 초과는 불가능)
        const val IMPACT_DV = 2.2f        // m/s — 8 km/h(EDR 기록 최소 Δv, 49 CFR 563)
        const val IMPACT_DT_MIN = 0.08
        const val IMPACT_DT_MAX = 0.5
        const val IMPACT_HOLD_MS = 500L
    }

    /** 탑승·하차를 판단하는 동안(픽업 확정~하차) 좌석·벨트·문을 매초 읽는다. 아니면 5초마다만(Pleos: 불필요한 폴링 최소화) */
    @Volatile var readCabin = false
    private var readN = 0

    /** 1초마다 불린다: 기어는 매초, 와이퍼는 3초마다, 2열 좌석·벨트·문은 readCabin 일 때 매초(아니면 5초마다), 도로 링크 중이면 조향·브레이크·내기순환 매초.
     *  예전에는 매초 IPC 10건(SDK 로그 분당 600줄)이었다 */
    fun readTick() {
        val v = vehicle ?: return
        val n = readN++
        runCatching {
            v.getGear().getCurrentGearSelectionState({ g -> main.post { gear = g } }, { })
            if (n % 3 == 0) v.getWiper().getCurrentWiperSwitch(WindowArea(WindowValue.FRONT_WINDSHIELD), { w -> main.post { wiper = w?.name ?: "OFF" } }, { })
            if (readCabin || n % 5 == 0) for (seat in Region.REAR_SEATS) {
                v.getSeat().getCurrentSeatOccupancy(SeatArea(seat), { o -> main.post { seatOcc[seat] = o } }, { })
                v.getSeat().getCurrentSeatBeltBuckledState(SeatArea(seat), { b -> main.post { seatBelt[seat] = b } }, { })
            }
            if (readCabin || n % 5 == 0) for (door in Region.REAR_DOORS)
                v.getDoor().getCurrentDoorPosition(DoorArea(door), { d -> main.post { doorOpen[door] = d; if ((d ?: 0) > 0) rearDoorOpened = true } }, { })
            if (readRoadSignals) {
                v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> main.post { steerAngle = (a as? Number)?.toFloat() } }, { })
                v.getBrake().getCurrentBrakePedalStatus({ b -> main.post { brakePedal = b } }, { })
                readRecirc(v)
                if (n % 5 == 0) readEv(v)
                if (n % 5 == 0) readOutsideTemp()
            }
        }
    }

    /**
     * 설계 실측(디버그 훅 probe_signals): ① 차속을 SENSOR_RATE_FASTEST 로 10초 받아 초당 몇 번 오는지(충돌 감지는 10Hz 가 필요하다),
     * ② 에어백 전개 읽기(Safety.hasSeatAirbagBeenDeployed, 운전석)가 에뮬레이터에서 값을 주는지. 결과는 로그로
     */
    fun probeSignals() {
        val m = cpm ?: run { log.d("실측: CarPropertyManager 없음"); return }
        var n = 0; var first = 0L; var last = 0L; var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val t = android.os.SystemClock.elapsedRealtime(); if (first == 0L) first = t; last = t; n++
                (value.value as? Float)?.let { v -> lo = minOf(lo, v); hi = maxOf(hi, v) }
            }
            override fun onErrorEvent(propId: Int, zone: Int) { log.d("실측: 속도 콜백 오류 $propId") }
        }
        @Suppress("DEPRECATION")
        val ok = runCatching { m.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST) }.getOrElse { log.d("실측: FASTEST 등록 실패 $it"); false }
        log.d("실측: 속도 FASTEST 등록 $ok — 10초 측정")
        main.postDelayed({
            @Suppress("DEPRECATION") runCatching { m.unregisterCallback(cb) }
            val sec = (last - first) / 1000.0
            log.d("실측: 속도 콜백 ${n}회 / ${"%.1f".format(sec)}초 = ${if (sec > 0) "%.1f".format((n - 1) / sec) else "?"} Hz, 값 ${lo}~${hi} m/s")
        }, 10_000)
        val v = vehicle ?: run { log.d("실측: Vehicle 없음"); return }
        runCatching {
            v.getSafety().hasSeatAirbagBeenDeployed(SeatArea(SeatValue.ROW_1_LEFT), { b -> main.post { log.d("실측: 운전석 에어백 전개 = $b") } }, { e -> main.post { log.d("실측: 에어백 읽기 실패 ${e.javaClass.simpleName}: ${e.message}") } })
        }.onFailure { log.d("실측: 에어백 호출 예외 $it") }
        runCatching {
            v.getSafety().hasSeatAirbagEnabled(SeatArea(SeatValue.ROW_1_LEFT), { b -> main.post { log.d("실측: 운전석 에어백 활성 = $b") } }, { e -> main.post { log.d("실측: 에어백 활성 읽기 실패 ${e.message}") } })
        }
    }

    /** EV 충전 상태(충전구·충전 스위치·배터리 %) — 없는 차(내연·HEV)는 실패 콜백이 와서 값이 null 로 남는다 */
    private fun readOutsideTemp() {
        val m = cpm ?: return
        runCatching { m.getProperty<Float>(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)?.value }
            .onSuccess { t -> if (t != null) envTemp = t }
            .onFailure { if (envTemp == null && readN < 10) log.d("외기 온도 읽기 실패: ${it.message}") }
    }

    private fun readEv(v: Vehicle) {
        runCatching {
            val ev = v.getEvBattery()
            ev.isEvChargePortOpen({ b -> main.post { evPort = b } }, { })
            ev.getEvChargeSwitchState({ b -> main.post { evCharging = b } }, { })
            ev.getEvBatteryStateOfCharge({ p -> main.post { evSoc = p } }, { })
        }
    }

    /** 내기순환 상태. 공식 샘플(VehicleApiExplorer)처럼 공개 API를 이름으로 찾아 부른다: checkHvacRecirculationCapabilities → 영역 → getHvacRecirculationState(영역) */
    private fun readRecirc(v: Vehicle) {
        runCatching {
            val hvac = v.getHVAC()
            if (recircArea == null) {
                val cap = hvac.javaClass.methods.firstOrNull { it.name.startsWith("checkHvacRecirculation") && !it.name.contains("Auto") && it.parameterTypes.size == 2 } ?: return
                val ok: (Any?) -> Unit = { a ->
                    val areas = (a as? Collection<*>)?.filterNotNull() ?: (a as? Map<*, *>)?.keys?.filterNotNull().orEmpty()
                    recircArea = areas.firstOrNull()
                }
                val fail: (Any?) -> Unit = { }
                cap.invoke(hvac, ok, fail)
                recircGetter = hvac.javaClass.methods.firstOrNull { (it.name == "getHvacRecirculationState" || it.name == "getHvacRecirculation") && it.parameterTypes.size == 3 }
                return
            }
            val g = recircGetter ?: return
            val ok: (Any?) -> Unit = { r -> main.post { recirc = r as? Boolean } }
            val fail: (Any?) -> Unit = { }
            g.invoke(hvac, recircArea, ok, fail)
        }
    }

    /** 응급 카드용: 전 좌석 착석을 읽어 0.8초 뒤 모인 값으로 콜백한다 */
    fun readAllSeatOccupancy(onDone: (Map<SeatValue, Boolean>) -> Unit) {
        val v = vehicle
        val result = mutableMapOf<SeatValue, Boolean>()
        if (v != null) Region.ALL_SEATS.forEach { seat ->
            runCatching { v.getSeat().getCurrentSeatOccupancy(SeatArea(seat), { o -> main.post { if (o != null) result[seat] = o } }, { }) }
        }
        main.postDelayed({ onDone(result.toMap()) }, 800)
    }

    fun rearDoorOpen(): Boolean = Region.REAR_DOORS.any { (doorOpen[it] ?: 0) > 0 }
    fun doorIsOpen(door: DoorValue): Boolean = (doorOpen[door] ?: 0) > 0

    fun release() {
        @Suppress("DEPRECATION")   // registerCallback 짝 (API 34)
        runCatching { cpm?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        runCatching { vehicle?.release() }
    }
}
