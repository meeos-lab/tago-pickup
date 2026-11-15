package ai.meeos.tago.ui.screens

import ai.meeos.tago.ui.LicensesUi
import ai.meeos.tago.ui.components.ButtonVariant
import ai.meeos.tago.ui.components.TagoButton
import ai.meeos.tago.ui.theme.Dim
import ai.meeos.tago.ui.theme.TagoType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign

/**
 * 고지(B-62 오픈소스 · 11-15 개인정보, 차대차 판): 순정 설정 화면 문법 — 흰 바탕, 직각, 1px 가는 줄로 나눈 목록(이름 왼쪽 · 라이선스 오른쪽), 아래에 안내 글, 맨 아래 [닫기].
 * 카드·색 강조 없음. 안내 글은 잘리면 안 되므로 줄 제한을 두지 않는다
 */
@Composable
internal fun ColumnScope.LicensesContent(u: LicensesUi, actions: TagoActions) {
    val c = ai.meeos.tago.ui.theme.Tago.colors
    Column(verticalArrangement = Arrangement.spacedBy(Dim.section)) {
        // 개인정보 고지(11-15, 차대차 판): 보내는 것 · 쓰는 곳 · 지도 서버(제3자) · 보관 · 보내지 않는 것 · 동의 거두기. 근거 docs/dev/83 1~3절
        if (u.privacy.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            if (u.privacyTitle != null) Text(u.privacyTitle, style = TagoType.bodyM, color = c.text)
            u.privacy.forEach { Text(it, style = TagoType.bodyS, color = c.text2) }
        }
        if (u.licTitle != null && u.privacy.isNotEmpty()) Text(u.licTitle, style = TagoType.bodyM, color = c.text)
        Column {
            Box(Modifier.fillMaxWidth().height(Dim.line).background(c.line))
            u.items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = Dim.gapS), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.name, style = TagoType.bodyM, color = c.text, modifier = Modifier.weight(1f))
                    Text(item.license, style = TagoType.bodyM, color = c.text2, textAlign = TextAlign.End)
                }
                Box(Modifier.fillMaxWidth().height(Dim.line).background(c.line))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dim.gapS)) {
            u.notes.forEach { Text(it, style = TagoType.bodyS, color = c.text2) }
        }
        TagoButton(u.closeLabel, actions.onLicensesClose, Modifier.fillMaxWidth(), ButtonVariant.PRIMARY)
    }
}
