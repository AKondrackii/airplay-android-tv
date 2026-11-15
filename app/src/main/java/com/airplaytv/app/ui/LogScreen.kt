package com.airplaytv.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.airplaytv.core.AppLog

/** Warnings and errors from this run (memory only, nothing stored or sent). */
@Composable
fun LogScreen() {
    val entries by AppLog.entries.collectAsState()
    AuroraBackground(calm = true) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    GText("Журнал ошибок", GlassType.Title)
                    GText("Только предупреждения и ошибки текущего запуска. Хранится в памяти, никуда не отправляется.", GlassType.Caption)
                }
                GlassLabelButton("Очистить", { AppLog.clear() })
            }
            Spacer(height = 18.dp)
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GText("Ошибок нет", GlassType.Headline) }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries.asReversed()) { e ->
                        val color = if (e.level == AppLog.Level.ERROR) GlassTokens.Negative else GlassTokens.Warning
                        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(GlassTokens.RadiusS)).focusable().padding(12.dp)) {
                            GText(e.formatted(), GlassType.Mono, color = color, maxLines = 4)
                        }
                    }
                }
            }
        }
    }
}
