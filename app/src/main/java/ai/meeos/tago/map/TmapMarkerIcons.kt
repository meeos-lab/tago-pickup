package ai.meeos.tago.map

import ai.meeos.tago.map.TmapCarMap.Companion.GRAY9E
import ai.meeos.tago.map.TmapCarMap.Companion.GREEN
import ai.meeos.tago.map.TmapCarMap.Companion.ORANGE
import ai.meeos.tago.map.TmapCarMap.Companion.PLACE_ICON
import ai.meeos.tago.map.TmapCarMap.Companion.PLACE_PIN
import ai.meeos.tago.map.TmapCarMap.Companion.WEB_SCALE
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.roundToInt

/**
 * 차 화면 TMAP 지도의 마커 그림 공장: SDK 핀·v9 핀·대안 후보 핀·순정 커서·원형 퍽·라벨을 만들고 캐시한다.
 * 그리기(TmapCarMap)와 떼어 둔 것 — 그림 규칙(docs/ui-design.md §9)을 고칠 때 이 파일만 본다.
 * @param dp 화면 밀도 × 웹 지도 배율(TmapCarMap.dp)
 * @param isDark 지금 밤 지도인지(퍽·라벨 색과 캐시 키에 쓴다)
 */
internal class TmapMarkerIcons(private val ctx: Context, private val dp: Float, private val isDark: () -> Boolean) {
    private val bitmaps = HashMap<String, Bitmap>()
    private val dark: Boolean get() = isDark()

    /** 밤/낮이 바뀌면 다시 그린다 */
    fun clear() = bitmaps.clear()

    /** SDK 에 든 경로 핀(tmap-sdk res/drawable: start 출발 · end 도착 · pass 경유, 30×39 원본). 차 화면에서 보이게 순정 커서와 같은 비율로 키운다 */
    fun sdkPin(name: String): Bitmap = bitmaps.getOrPut("sdk:$name") {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        val src = (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) else null)
            ?: return@getOrPut puck("pin", 48, "best")
        val k = 1.6f * dp / WEB_SCALE   // 원본 30×39 → 약 48×62(밀도 1.0 기준)
        Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true).also { if (it !== src) src.recycle() }
    }

    /** SDK 에 없는 마커 = 사용자 v9 세트(res/drawable-nodpi/tago_place_*·tago_person_*·tago_pin_*.png, 기준 크기의 4배·투명, 단색 물방울 + 흰 실루엣).
     *  모든 핀은 끝점이 위치(앵커 0.5, PIN_TIP). base = 그림의 기준 칸(48, 단속·제외 24), scale = 차 화면 배율(SDK 도착 핀이 1.6) */
    fun pin(res: String, base: Int, scale: Float): Bitmap = bitmaps.getOrPut("v9:$res:$scale") {
        val id = ctx.resources.getIdentifier(res, "drawable", ctx.packageName)
        val src = (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false }) else null)
            ?: return@getOrPut puck("pin", base, "other")
        val px = (base * scale * dp / WEB_SCALE).roundToInt()
        Bitmap.createScaledBitmap(src, px, px, true).also { if (it !== src) src.recycle() }
    }

    /** 대안 후보 핀: 엔진 후보의 icon(종류) → v9 그림. 없는 종류는 원형 퍽(ORBIT 글리프) */
    fun place(icon: String): Bitmap {
        val name = PLACE_ICON[icon] ?: return puck(icon, 44, "alt")
        return pin("tago_place_$name", 48, PLACE_PIN)
    }

    /** 순정 커서 그림(VSM SDK 에 함께 든 location_marker 148×148 @xhdpi: 흰 원 + 파란 화살표 + 그림자) */
    fun cursor(): Bitmap = bitmaps.getOrPut("cursor") {
        val id = ctx.resources.getIdentifier("location_marker", "drawable", ctx.packageName)
        (if (id != 0) BitmapFactory.decodeResource(ctx.resources, id) else null) ?: puck("navigation", 44, "best")
    }

    /**
     * 내 차 표식(11-14, 상태마다 하나 — 둘을 겹치지 않는다): 가는 중 = 파란 화살촉 하나(뒤가 파인 삼각형 + 흰 테두리 3.5 + 옅은 그림자, 52 칸),
     * 길 안내가 없는 때 = 흰 테두리 파란 점 32 + 옅은 후광 56. 지도 페이지(link.html meCursor·mePuck)와 같은 수치. 회전은 마커 rotation 으로
     */
    fun arrowhead(): Bitmap = bitmaps.getOrPut("arrowhead") {
        val sz = (52 * dp).roundToInt(); val k = sz / 52f
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val path = android.graphics.Path().apply { moveTo(26 * k, 4 * k); lineTo(45 * k, 46 * k); lineTo(26 * k, 36 * k); lineTo(7 * k, 46 * k); close() }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.ROUND; p.strokeWidth = 3.5f * 2 * k; p.color = Color.WHITE
        p.setShadowLayer(3 * k, 0f, 2 * k, Color.argb(90, 0, 0, 0)); c.drawPath(path, p); p.clearShadowLayer()
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#178BF5"); c.drawPath(path, p)
        b
    }

    fun dot(): Bitmap = bitmaps.getOrPut("dot") {
        val sz = (56 * dp).roundToInt(); val k = sz / 56f; val r = sz / 2f
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(46, 23, 139, 245); c.drawCircle(r, r, r, p)               // 후광(.18)
        p.color = Color.WHITE; p.setShadowLayer(2 * k, 0f, 1 * k, Color.argb(90, 0, 0, 0)); c.drawCircle(r, r, 16 * k, p); p.clearShadowLayer()
        p.color = Color.parseColor("#178BF5"); c.drawCircle(r, r, 12 * k, p)
        b
    }

    /** 원형 퍽(docs/ui-design.md §9): kind = other(ink) · best(green 테) · sure(green 채움) · alt/via(orange 테) · no(제외) · cam(단속) */
    fun puck(icon: String, sizeDp: Int, kind: String): Bitmap = bitmaps.getOrPut("p:$icon:$sizeDp:$kind:$dark") {
        val face = if (dark) Color.parseColor("#1C1C1C") else Color.WHITE
        val (fill, ring, ringDp, glyph) = when (kind) {
            "best" -> Quad(face, GREEN, 4f, GREEN)
            "sure" -> Quad(GREEN, face, 3f, Color.WHITE)
            "alt", "via" -> Quad(face, ORANGE, 4f, ORANGE)
            "no" -> Quad(face, GRAY9E, 2f, GRAY9E)
            "cam" -> Quad(face, GRAY9E, 2f, Color.parseColor("#6E6E6E"))
            else -> Quad(if (dark) Color.parseColor("#E8E8E8") else Color.BLACK, face, 3f, if (dark) Color.parseColor("#111111") else Color.WHITE)
        }
        val sz = (sizeDp * dp).roundToInt()
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = sz / 2f
        p.color = ring; c.drawCircle(r, r, r, p)
        p.color = fill; c.drawCircle(r, r, r - ringDp * dp, p)
        val resId = ctx.resources.getIdentifier("ic_$icon", "drawable", ctx.packageName).takeIf { it != 0 }
            ?: ctx.resources.getIdentifier("ic_pin", "drawable", ctx.packageName)
        ctx.getDrawable(resId)?.mutate()?.let { d ->
            d.setTint(glyph)
            val g = (sz * 0.56f).roundToInt(); val o = (sz - g) / 2
            d.setBounds(o, o, o + g, o + g); d.draw(c)
        }
        b
    }

    /** 라벨(직각 태그): 흰 면 + 뜻 색 테두리(추천·확정 green, 대안 orange) + 진한 글. 퍽 쪽으로 gap 만큼 투명 여백을 둬 앵커(아래/위 가운데)가 점에 오게 한다 */
    fun tag(text: String, cls: String, gap: Int, above: Boolean): Bitmap = bitmaps.getOrPut("t:$text:$cls:$gap:$above:$dark") {
        val border = when (cls) { "best" -> if (dark) Color.parseColor("#00E074") else GREEN; "alt" -> ORANGE; else -> if (dark) Color.parseColor("#3A3A3A") else Color.parseColor("#D4D4D4") }
        val ink = when (cls) { "best" -> if (dark) Color.parseColor("#00E074") else Color.parseColor("#0E6B3F"); "alt" -> if (dark) ORANGE else Color.parseColor("#8A4B00"); else -> if (dark) Color.parseColor("#F2F2F2") else Color.BLACK }
        val bw = if (cls == "best" || cls == "alt") 2 * dp else 1 * dp
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18 * dp; typeface = Typeface.DEFAULT_BOLD; color = ink }
        val fm = tp.fontMetrics
        val w = (tp.measureText(text) + 2 * 10 * dp + 2 * bw).roundToInt()
        val h = ((fm.descent - fm.ascent) + 2 * 4 * dp + 2 * bw).roundToInt()
        val g = (gap * dp).roundToInt()
        val b = Bitmap.createBitmap(w, h + g, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val top = if (above) 0f else g.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = border; c.drawRect(RectF(0f, top, w.toFloat(), top + h), p)
        p.color = if (dark) Color.parseColor("#1C1C1C") else Color.WHITE; c.drawRect(RectF(bw, top + bw, w - bw, top + h - bw), p)
        c.drawText(text, bw + 10 * dp, top + bw + 4 * dp - fm.ascent, tp)
        b
    }

    private data class Quad(val a: Int, val b: Int, val c: Float, val d: Int)
}
