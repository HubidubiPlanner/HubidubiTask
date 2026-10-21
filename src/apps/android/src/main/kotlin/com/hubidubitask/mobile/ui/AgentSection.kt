package com.hubidubitask.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hubidubitask.core.agent.AgentField
import com.hubidubitask.core.agent.AgentTypes
import com.hubidubitask.core.i18n.t
import kotlinx.coroutines.delay

/**
 * Settings › AI Agent: which AI answers reviews, and only what that one
 * needs to be reached, its fields untitled ([AgentTypes]). No Save: a change
 * is kept on its own a moment after the last keystroke or choice, then
 * checked, and the line under says what was found. Nothing is kept before
 * anything was changed here. The same on the phone and the computer.
 */
@Composable
internal fun AgentSection(vm: HubidubiTaskViewModel, edge: Dp) {
    val c = HubidubiTaskTheme.colors
    var typeId by remember { mutableStateOf(vm.agentConfig().protocol) }
    // What was typed for each choice, so switching back and forth loses nothing.
    val forms = remember {
        mutableStateMapOf<String, Map<String, String>>().apply { AgentTypes.all.forEach { put(it.id, vm.agentForm(it)) } }
    }
    val type = AgentTypes.of(typeId)
    val values = forms[typeId].orEmpty()
    // Set by the first change made here; what was last kept, so it is not kept twice.
    var touched by remember { mutableStateOf(false) }
    var kept by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // Whether the last check found the AI answering; the message says it in words.
    var connected by remember { mutableStateOf(false) }

    @Composable
    fun Field(f: AgentField, modifier: Modifier = Modifier) =
        LabeledField("", values[f.key].orEmpty(), { typed ->
            forms[typeId] = values + (f.key to if (f.digits) typed.filter(Char::isDigit) else typed)
            message = null; touched = true
        }, helper = "", enabled = true, placeholder = f.placeholder, secret = f.secret, modifier = modifier)

    Column(Modifier.padding(horizontal = edge), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Dropdown(type.label, AgentTypes.all.map { choice ->
            DropdownItem(choice.label, choice.id == typeId) { typeId = choice.id; message = null; touched = true }
        })

        // Fields that share a row sit side by side, sized by their weight.
        type.fields.groupBy { it.row }.values.forEach { row ->
            if (row.size == 1) Field(row.single())
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { Field(it, Modifier.weight(it.weight)) }
            }
        }

        LaunchedEffect(typeId, values) {
            val inputs = typeId to values
            if (!touched || inputs == kept) return@LaunchedEffect
            delay(800)
            kept = inputs
            message = null
            if (vm.agentWillCheck(type, values)) message = t("Saved — checking…")
            val out = vm.setUpAgent(type, values)
            // What was found there (the port the agent listens on) shows in the fields.
            out.values?.let { found -> forms[typeId] = found; kept = typeId to found }
            message = out.message
            connected = out.connected
        }

        // What the last change found, and nothing before.
        message?.let {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(if (connected) c.ok else c.muted)
                Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}
