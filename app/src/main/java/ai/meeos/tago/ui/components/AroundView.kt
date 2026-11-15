package ai.meeos.tago.ui.components

import ai.meeos.tago.ui.AroundUi
import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 주행 중 왼쪽 판의 주변 차 화면(11-14). 그림은 앱 자산의 server/link/around.html(2D 캔버스, 외부 라이브러리·통신 없음)이 그리고,
 * 여기서는 화면 상태(AroundUi)가 바뀔 때마다 그 값을 페이지의 aroundSet(...) 에 넘기기만 한다. 페이지가 아직 안 열렸으면 열린 뒤 마지막 값을 넘긴다.
 * 누를 것이 없는 화면이라 손 입력은 받지 않는다(주행 중 조작 금지와 같은 쪽)
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun AroundView(ui: AroundUi, night: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val holder = remember { AroundHolder() }
    val web = remember {
        WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.setSupportZoom(false)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            setBackgroundColor(if (night) 0xFF101113.toInt() else 0xFFF4F4F5.toInt())   // 페이지가 뜨기 전 빈 바탕(페이지 바탕색과 같다)
            setOnTouchListener { _, _ -> true }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { holder.ready = true; holder.last?.let { view.evaluateJavascript("aroundSet($it)", null) } }
            }
            loadUrl("file:///android_asset/link/around.html" + if (night) "?night=1" else "")
        }
    }
    DisposableEffect(Unit) { onDispose { web.stopLoading(); web.destroy() } }
    val json = remember(ui, night) {
        JSONObject().put("kmh", ui.kmh).put("unit", ui.unit).put("night", night)
            .put("cars", JSONArray().also { a -> ui.cars.forEach { c -> a.put(JSONObject().put("id", c.id).put("kind", c.kind).put("d", c.d).put("name", c.name).put("dist", c.dist).put("tone", c.tone)) } })
            .toString()
    }
    AndroidView(factory = { web }, modifier = modifier, update = {
        if (holder.last != json) { holder.last = json; if (holder.ready) it.evaluateJavascript("aroundSet($json)", null) }
    })
}

private class AroundHolder { var ready = false; var last: String? = null }
