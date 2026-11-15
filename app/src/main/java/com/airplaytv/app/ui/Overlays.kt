package com.airplaytv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airplaytv.core.UserPrompt
import kotlinx.coroutines.delay

@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) { content() }
}

/** PIN / one-time code the user types on the iPhone. */
@Composable
fun PinOverlay(prompt: UserPrompt.ShowPin) {
    Scrim {
        GlassPanel(Modifier.width(600.dp), padding = 36.dp, solid = true) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                GText("Введите код на iPhone", GlassType.Title, align = TextAlign.Center)
                Spacer(height = 8.dp)
                GText("Код подтверждает, что подключаетесь именно вы", GlassType.Body, align = TextAlign.Center)
                Spacer(height = 26.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    prompt.pin.forEach { ch ->
                        Box(
                            Modifier.width(78.dp).height(96.dp).glass(RoundedCornerShape(GlassTokens.RadiusM), focused = true),
                            contentAlignment = Alignment.Center,
                        ) { GText(ch.toString(), GlassType.Display.copy(fontSize = 52.sp)) }
                    }
                }
            }
        }
    }
}

/** Asks whether to admit an unknown device. Times out (denies) on its own. */
@Composable
fun ConfirmOverlay(prompt: UserPrompt.ConfirmClient, onAnswer: (allow: Boolean, remember: Boolean) -> Unit) {
    val first = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prompt.id) {
        runCatching { first.requestFocus() }
        while (true) { delay(250); now = System.currentTimeMillis() }
    }
    val left = ((prompt.expiresAt - now) / 1000).coerceAtLeast(0)
    BackHandler { onAnswer(false, false) }
    Scrim {
        GlassPanel(Modifier.width(640.dp), padding = 34.dp, solid = true) {
            GText("Новое подключение", GlassType.Caption, color = GlassTokens.Warning)
            Spacer(height = 8.dp)
            GText(prompt.client.displayName, GlassType.Title, maxLines = 1)
            prompt.client.model?.let { GText(it, GlassType.Body) }
            Spacer(height = 6.dp)
            GText("Разрешить этому устройству показывать изображение и звук на телевизоре? Автоматический отказ через $left с",
                GlassType.Body)
            Spacer(height = 22.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassButton({ onAnswer(true, false) }, Modifier.focusRequester(first), tint = GlassTokens.Positive.copy(alpha = 0.16f)) {
                    GText("Разрешить", GlassType.Label)
                }
                GlassButton({ onAnswer(true, true) }) { GText("Разрешить и запомнить", GlassType.Label) }
                GlassButton({ onAnswer(false, false) }, tint = GlassTokens.Negative.copy(alpha = 0.14f)) {
                    GText("Отклонить", GlassType.Label)
                }
            }
        }
    }
}

/** Text entry with the TV on-screen keyboard. */
@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    secret: Boolean,
    validate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    BackHandler(onBack = onDismiss)
    Scrim {
        GlassPanel(Modifier.width(560.dp), padding = 30.dp, solid = true) {
            GText(title, GlassType.Title)
            Spacer(height = 18.dp)
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 64) value = it },
                singleLine = true,
                textStyle = TextStyle(color = GlassTokens.TextPrimary, fontSize = 22.sp),
                cursorBrush = SolidColor(GlassTokens.Accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (validate(value)) onSave(value) }),
                modifier = Modifier.fillMaxWidth().focusRequester(field)
                    .glass(RoundedCornerShape(GlassTokens.RadiusS), focused = true).padding(16.dp),
            )
            Spacer(height = 20.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val ok = validate(value)
                GlassButton({ if (ok) onSave(value) }, tint = if (ok) GlassTokens.Accent.copy(alpha = 0.18f) else Color.Transparent) {
                    GText("Сохранить", GlassType.Label, color = if (ok) Color.Unspecified else GlassTokens.TextTertiary)
                }
                GlassButton(onDismiss) { GText("Отмена", GlassType.Label) }
            }
        }
    }
}

@Composable
fun FadingChip(text: String, modifier: Modifier = Modifier) {
    var visible by remember(text) { mutableStateOf(true) }
    LaunchedEffect(text) { delay(4_000); visible = false }
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(600), label = "chip")
    if (a > 0f) {
        Box(modifier.alpha(a).clip(RoundedCornerShape(50)).background(Color(0x66000000))) {
            StatusPill(text, GlassTokens.Positive)
        }
    }
}

