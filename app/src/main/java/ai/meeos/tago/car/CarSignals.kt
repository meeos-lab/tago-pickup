package ai.meeos.tago.car

import ai.meeos.tago.app.AppLog
import ai.meeos.tago.app.InitRetry
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
    /** 실제 차속 콜백(PERF_VEHICLE_SPEED)을 마지막으로 받은 시각(elapsedRealtime ms, 0 = 아직 없음). 연구 기록이 속도 출처를 가른다 */
    @Volatile var speedEventAt = 0L
        private set
    /** 시연 주행(convoy_sim): null 이 아니면 실제 차속 대신 이 값을 쓴다 */
    var simulatedSpeed: Float? = null
        set(v) { field = v; if (v != null) speedMs = v }
    var gear: VehicleGear? = null
        private set
    /** 기어를 마지막으로 읽은 시각(elapsedRealtime ms, 0 = 아직 못 읽음). 낡으면 '모름' — DriveLock */
    private var gearAt = 0L
    var wiper = "OFF"
        private set
    val seatOcc = mutableMapOf<SeatValue, Boolean?>()      // 2열 좌석별 착석
    val seatBelt = mutableMapOf<SeatValue, Boolean?>()     // 2열 좌석별 안전벨트
    val doorOpen = mutableMapOf<DoorValue, Int?>()         // 2열 문 위치(0 = 닫힘)
    /** 2열 문이 한 번이라도 열렸는지 — 픽업이 확정 시점·탑승 판정 뒤에 지운다 */
    var rearDoorOpened = false
    // 도로 링크 신호(도로 링크 중에만 읽는다): 조향각(낙하물 회피), 브레이크 페달(긴 내리막), 공조 내기순환(연기·매연)
    var readRoadSignals = false
    /** 주차 위치 기억(새 기능 H)이 켜져 있으면 조향각만 읽는다(다른 신호 읽기가 꺼져 있어도 — 경사로 회전 적분) */
    var readParkSignals = false
    /** 하향등 켜짐(Pleos Light.getCurrentLowBeamLightState, CAR_LIGHTS) — 주차장 입구 보조 단서(자동 전조등이 어두운 곳에서 켠다). 못 읽으면 null */
    var lowBeamOn: Boolean? = null
        private set

    /** 1·2열 창문 위치(Vehicle Window.getCurrentWindowPosition, 0 = 닫힘). 도로 링크 중 3초마다 읽는다 — 비·침수·연기 안내에 '창문을 닫아 주세요'를 붙인다.
     *  앱은 창문을 닫지 않는다: SDK 에 setWindowPosition 이 있지만 조사 노트 기준 창문 닫기 제어는 '준비 중'이고, 움직이는 창문은 끼임 위험이 있어 운전자에게 맡긴다 */
    val windowPos = HashMap<WindowValue, Int>()
    val openWindows: List<WindowValue> get() = windowPos.filterValues { it > 0 }.keys.sortedBy { it.ordinal }
    /** 충돌 감지기(10 Hz 차속 → 충돌 확정). 규칙은 ImpactDetector */
    private val impact = ImpactDetector { log.d(it) }
    var onImpact: ((pre: Float, now: Float, samples: List<Pair<Long, Float>>) -> Unit)?
        get() = impact.onImpact
        set(v) { impact.onImpact = v }
    private var fastOn = false
    var airbagSim: Boolean? = null                          // 시연 훅(에뮬레이터 VHAL 에 에어백 전개 속성이 없다)
    private var simUntil = 0L                               // impact_sim 표본을 넣는 동안(1.5 s) 실제 표본 무시
    var steerAngle: Float? = null
        private set
    var brakePedal: Boolean? = null
        private set
    var recirc: Boolean? = null
        private set
    /** 방향지시등 OFF/LEFT/RIGHT(Pleos TurnSignal, CAR_LIGHTS). 연구 기록(차로 변경 후보)이 켜져 있을 때만 읽는다. 못 읽으면 null */
    var turnSignal: String? = null
        private set
    var onTurnSignal: ((String) -> Unit)? = null
    private var turnListener: ai.pleos.playground.vehicle.listener.TurnSignalStateListener? = null
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
    /** 주행 중이거나 **모름**(기어를 아직·계속 못 읽음) — 정차 전용 화면을 여는 곳이 이것을 본다(V-01, DriveLock) */
    val locked: Boolean get() = DriveLock.lockedOut(gear?.name, if (gearAt == 0L) null else android.os.SystemClock.elapsedRealtime() - gearAt)
    /** 정차 전용 화면을 열어도 되는 정차: 기어를 알고 주행이 아니며 속도도 느림 */
    val stoppedKnown: Boolean get() = !locked && speedMs < 1f
    val reversing: Boolean get() = gear?.name == "GEAR_REVERSE"
    val stopped: Boolean get() = !driving && speedMs < 1f

    private var vehicleRetry: InitRetry? = null
    private var carRetry: InitRetry? = null
    /** Pleos Vehicle SDK 가 붙었나 / 표준 차량 속도 서비스가 붙었나 — 화면이 '차량 신호를 쓸 수 없음'을 띄우는 근거(V-04) */
    val vehicleReady: Boolean get() = vehicleRetry?.ready == true
    val speedReady: Boolean get() = carRetry?.ready == true
    /** 두 서비스 중 시도 횟수가 가장 많은 쪽(2 이상이면 첫 시도가 실패했다는 뜻) */
    val initAttempts: Int get() = maxOf(vehicleRetry?.attempts ?: 0, carRetry?.attempts ?: 0)
    /** 시연 훅 init_fail: 이 시각(elapsedRealtime ms)까지는 서비스 연결 시도가 실패한 것처럼 군다(늦게 뜨는 서비스 재현) */
    @Volatile var simInitFailUntil = 0L
    private fun simFail() = android.os.SystemClock.elapsedRealtime() < simInitFailUntil
    private var turnWatchWanted = false

    /** 서비스 연결은 실패해도 5초마다 다시 시도한다(시동 직후 서비스가 늦게 뜨는 경우, V-04). 성공할 때까지 */
    fun start() {
        val sched: (Long, () -> Unit) -> Unit = { d, f -> main.postDelayed(f, d) }
        vehicleRetry = InitRetry(schedule = sched) { r -> initVehicle(r) }.also { it.start() }
        carRetry = InitRetry(schedule = sched) { r -> initCar(r) }.also { it.start() }
    }

    private fun initVehicle(r: InitRetry) {
        var v: Vehicle? = null
        try {
            if (simFail()) throw IllegalStateException("시연: 서비스 연결 실패")
            v = Vehicle(context).also { it.initialize() }
            vehicle = v
            if (r.attempts > 1) log.d("Vehicle 연결됨(${r.attempts}번째 시도)")
            r.succeeded()
            if (turnWatchWanted) setTurnSignalWatch(true)   // 서비스가 늦게 붙기 전에 요청된 방향지시등 구독
        } catch (e: Throwable) {
            runCatching { v?.release() }
            log.d("Vehicle 초기화 실패(${r.attempts}번째 시도, 5초 뒤 재시도): $e")
            r.failed()
        }
    }

    private fun initCar(r: InitRetry) {
        var c: Car? = null
        try {
            if (simFail()) throw IllegalStateException("시연: 서비스 연결 실패")
            // 차량 속도(PERF_VEHICLE_SPEED)는 Pleos SDK에 없어 표준 API 사용 (FAQ 안내 방식)
            c = Car.createCar(context) ?: throw IllegalStateException("Car 서비스 없음")
            val m = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: throw IllegalStateException("속성 서비스 없음")
            // subscribePropertyEvents 는 API 35 부터라 차량 이미지(API 34)에서는 registerCallback 을 써야 한다
            @Suppress("DEPRECATION")
            m.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
            car = c; cpm = m
            if (r.attempts > 1) log.d("속도 서비스 연결됨(${r.attempts}번째 시도)")
            r.succeeded()
            if (fastOn) { fastOn = false; setFastSpeed(true) }   // 서비스가 붙기 전에 요청된 10 Hz 구독
        } catch (e: Throwable) {
            runCatching { c?.disconnect() }
            log.d("속도 초기화 실패(${r.attempts}번째 시도, 5초 뒤 재시도): $e")
            r.failed()
        }
    }

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            // 상태가 AVAILABLE 이 아닌 표본은 버린다(V-02): speedEventAt 도 갱신하지 않아 값 나이가 그대로 자란다
            val v = SpeedSample.accept(value.status, value.value) ?: return
            speedEventAt = android.os.SystemClock.elapsedRealtime(); main.post { if (simulatedSpeed == null) speedMs = v }   // 시연 주행 중에는 대본 속도
        }

        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    /** 10 Hz 속도(도로 링크 중에만): 표본을 모으고 충돌 감지기를 돌린다. 에뮬레이터 실측 10.2 Hz */
    private val fastCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val ms = value.timestamp / 1_000_000; val st = value.status; val raw = value.value
            main.post { acceptFast(st, raw, ms, force = false) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) = Unit
    }

    /** 10 Hz 표본 하나(메인 스레드). 상태가 AVAILABLE 이 아니면 버리고 충돌 후보도 비운다(오류 구간의 0 이 '급정지'로 읽히지 않게, V-02) */
    private fun acceptFast(status: Int, raw: Any?, ms: Long, force: Boolean) {
        val v = SpeedSample.accept(status, raw)
        if (v == null) { impact.reset(); return }
        speedEventAt = android.os.SystemClock.elapsedRealtime()
        if (simulatedSpeed == null) speedMs = v
        if (force || android.os.SystemClock.elapsedRealtime() >= simUntil) feedFast(ms, v)   // 충돌 시연 표본을 넣는 동안은 실제(VC) 표본을 섞지 않는다
    }

    /** 시연 훅 speed_sample_sim "25,0,unavail": 속도 pre 표본 6개 뒤 post 표본 10개(0.1 s 간격)를 **같은 검증 경로**로 넣는다. 세 번째는 표본 상태(ok·unavail·error) */
    fun simSpeedSample(pre: Float, post: Float, status: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        simUntil = now + 2_500; impact.reset()
        for (i in 0 until 6) main.postDelayed({ acceptFast(SpeedSample.STATUS_AVAILABLE, pre, android.os.SystemClock.elapsedRealtime(), force = true) }, i * 100L)
        for (i in 6 until 16) main.postDelayed({ acceptFast(status, post, android.os.SystemClock.elapsedRealtime(), force = true) }, i * 100L)
    }

    /** 연구 기록 중에만: 방향지시등 변화를 받는다(콜백 + 1초 읽기 둘 다 — 콜백이 빠져도 1초 안에 잡힌다. 에뮬레이터 Virtual Console 깜빡이로 확인 2026-11-04) */
    fun setTurnSignalWatch(on: Boolean) {
        turnWatchWanted = on
        val v = vehicle ?: return
        runCatching {
            if (on && turnListener == null) {
                val l = object : ai.pleos.playground.vehicle.listener.TurnSignalStateListener {
                    override fun onTurnSignalStateUpdated(state: ai.pleos.playground.vehicle.constant.area.TurnSignalValue?) { if (state != null) main.post { setTurn(state.name) } }
                    override fun onFailed(e: Exception) { main.post { log.d("방향지시등 읽기 실패: ${e.message}") } }
                }
                turnListener = l
                v.getTurnSignal().registerTurnSignalState(l)
            } else if (!on) turnListener?.let { v.getTurnSignal().unregisterTurnSignalState(it); turnListener = null }
        }.onFailure { log.d("방향지시등 구독 실패: ${it.message}") }
        if (!on) turnSignal = null
    }

    private fun setTurn(name: String) {
        if (name == turnSignal) return
        turnSignal = name
        onTurnSignal?.invoke(name)
    }

    fun setFastSpeed(on: Boolean) {
        if (on == fastOn) return
        fastOn = on
        val m = cpm ?: return
        @Suppress("DEPRECATION")
        runCatching { if (on) m.registerCallback(fastCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FASTEST) else m.unregisterCallback(fastCallback) }
            .onFailure { log.d("속도 10Hz ${if (on) "구독" else "해제"} 실패: $it") }
        impact.reset()
    }

    /** 표본 하나를 감지기에 넣는다(실차 콜백·시연 훅 공용). 시각은 ms */
    fun feedFast(ms: Long, v: Float) = impact.feed(ms, v)

    /** 시연 훅: 속도 pre→post 충돌 표본을 감지기에 넣고 차를 post 로 세운다(VC 는 순간 변화만 가능하지만 이 훅은 실차 표본 모양과 같다) */
    fun simImpact(pre: Float, post: Float) {
        val now = android.os.SystemClock.elapsedRealtime()
        simUntil = now + SIM_HOLD_MS; impact.reset()
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
        const val SIM_HOLD_MS = 1500L       // impact_sim 표본을 넣는 동안 실제 표본 무시
        const val SEAT_GATHER_MS = 800L     // 좌석·벨트 비동기 콜백을 모으는 시간
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
            v.getGear().getCurrentGearSelectionState({ g -> main.post { gear = g; if (g != null) gearAt = android.os.SystemClock.elapsedRealtime() } }, { })
            if (n % 3 == 0) v.getWiper().getCurrentWiperSwitch(WindowArea(WindowValue.FRONT_WINDSHIELD), { w -> main.post { wiper = w?.name ?: "OFF" } }, { })
            if (readCabin || n % 5 == 0) for (seat in Region.REAR_SEATS) {
                v.getSeat().getCurrentSeatOccupancy(SeatArea(seat), { o -> main.post { seatOcc[seat] = o } }, { })
                v.getSeat().getCurrentSeatBeltBuckledState(SeatArea(seat), { b -> main.post { seatBelt[seat] = b } }, { })
            }
            if (readCabin || n % 5 == 0) for (door in Region.REAR_DOORS)
                v.getDoor().getCurrentDoorPosition(DoorArea(door), { d -> main.post { doorOpen[door] = d; if ((d ?: 0) > 0) rearDoorOpened = true } }, { })
            if (turnListener != null) v.getTurnSignal().getCurrentTurnSignalState({ ts -> main.post { if (ts != null) setTurn(ts.name) } }, { })
            if (readParkSignals && !readRoadSignals) v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> main.post { steerAngle = (a as? Number)?.toFloat() } }, { })
            if (readParkSignals && n % 2 == 0) v.getLight().getCurrentLowBeamLightState({ st -> main.post { lowBeamOn = st?.let { it == ai.pleos.playground.vehicle.constant.control.LightState.ON } } }, { main.post { lowBeamOn = null } })
            if (readRoadSignals) {
                v.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> main.post { steerAngle = (a as? Number)?.toFloat() } }, { })
                v.getBrake().getCurrentBrakePedalStatus({ b -> main.post { brakePedal = b } }, { })
                readRecirc(v)
                if (n % 5 == 0) readEv(v)
                if (n % 5 == 0) readOutsideTemp()
                if (n % 3 == 1) for (w in SIDE_WINDOWS)
                    v.getWindow().getCurrentWindowPosition(WindowArea(w), { p -> main.post { if (p != null) windowPos[w] = p else windowPos.remove(w) } }, { })
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

    /** 외기 온도(ENV_OUTSIDE_TEMPERATURE, 동기 읽기). 못 읽으면 값은 그대로 두고, 한 번도 못 읽었고 readN < 10 일 때만 실패 로그 */
    private fun readOutsideTemp() {
        val m = cpm ?: return
        runCatching { m.getProperty<Float>(VehiclePropertyIds.ENV_OUTSIDE_TEMPERATURE, 0)?.value }
            .onSuccess { t -> if (t != null) envTemp = t }
            .onFailure { if (envTemp == null && readN < 10) log.d("외기 온도 읽기 실패: ${it.message}") }
    }

    /** EV 충전 상태(충전구·충전 스위치·배터리 %) — 없는 차(내연·HEV)는 실패 콜백이 와서 값이 null 로 남는다 */
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
        main.postDelayed({ onDone(result.toMap()) }, SEAT_GATHER_MS)
    }

    fun rearDoorOpen(): Boolean = Region.REAR_DOORS.any { (doorOpen[it] ?: 0) > 0 }
    fun doorIsOpen(door: DoorValue): Boolean = (doorOpen[door] ?: 0) > 0

    fun release() {
        vehicleRetry?.cancel(); carRetry?.cancel()
        setFastSpeed(false); setTurnSignalWatch(false)   // 도로 링크·연구 기록 중에 닫혀도 구독이 남지 않게
        @Suppress("DEPRECATION")   // registerCallback 짝 (API 34)
        runCatching { cpm?.unregisterCallback(speedCallback) }
        runCatching { car?.disconnect() }
        runCatching { vehicle?.release() }
    }
}

/** 비·침수·연기 안내 때 보는 창문(1·2열 옆 창문. 앞뒤 유리·지붕은 열리지 않는다) */
private val SIDE_WINDOWS = listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT)
