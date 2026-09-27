package com.hubidubios.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// The few pieces every screen is built from. Keeping them here is what makes
// five screens look like one app: the same title, the same grouped card, the
// same 48dp row, the same two buttons.

/** A screen's title, serif, with an optional line under it and an action to its right. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val c = HubidubiOsTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, color = c.ink)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke(this)
    }
}

/** "Synced", "Online · fast": a dot and a word, in a pill so it reads as status, not a control. */
@Composable
fun StatusChip(text: String, good: Boolean) {
    val c = HubidubiOsTheme.colors
    Row(
        Modifier
            .background(c.raised, RoundedCornerShape(50))
            .border(1.dp, c.line, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(if (good) c.ok else c.muted)
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.muted)
    }
}

@Composable
fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

/** OVERDUE, NOT IN A PROJECT: the small tracked label above a group, with its count. */
@Composable
fun SectionLabel(
    text: String,
    color: Color = HubidubiOsTheme.colors.muted,
    count: Int? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.weight(1f))
        count?.let {
            Text(it.toString(), fontFamily = Mono,
                style = MaterialTheme.typography.labelMedium, color = HubidubiOsTheme.colors.muted)
        }
    }
}

/** A group of rows on a raised card. Borders, not shadows: flat paper stays flat. */
@Composable
fun CardGroup(
    modifier: Modifier = Modifier,
    border: Color = HubidubiOsTheme.colors.line,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = HubidubiOsTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .background(c.raised).border(1.dp, border),
        content = content,
    )
}

/**
 * One tappable row inside a [CardGroup]: at least 48dp tall, an optional glyph
 * on the left, a hairline under it unless it is the last.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    last: Boolean = false,
    startPadding: Int = 16,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = HubidubiOsTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = startPadding.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                    .padding(end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
            if (!last) HorizontalDivider(color = c.line, thickness = 1.dp)
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = HubidubiOsTheme.colors
    Button(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = c.button, contentColor = c.onButton,
            disabledContainerColor = c.line, disabledContentColor = c.muted,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = HubidubiOsTheme.colors
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RectangleShape,
        modifier = modifier.height(48.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (enabled) c.frame else c.line),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = c.ink, disabledContentColor = c.muted,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    icon?.let {
        Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}

/** Field colours shared by every text input: raised fill, clay-sand edge. */
@Composable
fun appFieldColors(): TextFieldColors {
    val c = HubidubiOsTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.raised, unfocusedContainerColor = c.raised,
        disabledContainerColor = c.paper,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.glyph,
        disabledBorderColor = c.line,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
    )
}

/** The title a bottom sheet opens with, plus the muted line above it. */
@Composable
fun SheetTitle(title: String, overline: String? = null) {
    val c = HubidubiOsTheme.colors
    overline?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted)
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, color = c.ink,
        modifier = Modifier.padding(top = if (overline != null) 4.dp else 0.dp, bottom = 12.dp))
}

/**
 * A sheet from the bottom edge: the one surface every item action, detail and
 * question opens in, so they all appear in the same place under the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = HubidubiOsTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.paper,
        contentColor = c.ink,
        shape = RectangleShape,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 18.dp)
                .size(width = 36.dp, height = 4.dp)
                .background(c.glyph, RoundedCornerShape(2.dp)))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/** One choice in a sheet: icon, label, optional value on the right. */
@Composable
fun ActionRow(
    icon: ImageVector,
    label: String,
    meta: String? = null,
    color: Color = HubidubiOsTheme.colors.ink,
    enabled: Boolean = true,
    last: Boolean = false,
    onClick: () -> Unit,
) {
    val c = HubidubiOsTheme.colors
    val tint = if (enabled) color else c.muted
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = tint,
                modifier = Modifier.weight(1f))
            meta?.let {
                Text(it, fontFamily = Mono, style = MaterialTheme.typography.bodySmall,
                    color = c.muted)
            }
        }
        if (!last) HorizontalDivider(color = c.line, thickness = 1.dp)
    }
}

// Dates are stored as ISO days in UTC (the picker hands back UTC midnight), so
// they are read back in UTC too, or a date would slide by one near midnight.
private fun utc(pattern: String) = java.text.SimpleDateFormat(pattern, java.util.Locale.ENGLISH)
    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

fun todayIso(): String = utc("yyyy-MM-dd").format(java.util.Date())

/** "Friday, 25 September": the line under the Next title. */
fun todayLong(): String = utc("EEEE, d MMMM").format(java.util.Date())

/** "2026-09-22" → "Tue 22 Sep". Anything unparseable is shown as stored. */
fun shortDate(iso: String): String = runCatching {
    utc("EEE d MMM").format(utc("yyyy-MM-dd").parse(iso)!!)
}.getOrDefault(iso)

/** Two or three mutually exclusive choices side by side: protocol, range. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = HubidubiOsTheme.colors
    Row(modifier.fillMaxWidth().border(1.dp, c.glyph)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(44.dp)
                    .background(if (on) c.button else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) c.onButton else c.ink)
            }
        }
    }
}
