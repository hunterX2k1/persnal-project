package app.vault.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vault.data.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(initial: Filter, options: FilterOptions, onApply: (Filter) -> Unit, onDismiss: () -> Unit) {
    var f by remember { mutableStateOf(initial) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Surface1) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                TextButton(onClick = { f = Filter(text = f.text, sort = f.sort, favoritesOnly = f.favoritesOnly) }) { Text("Reset") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Range("Age", f.ageFrom, f.ageTo, MIN_AGE, MAX_AGE, suffixMax = "+") { a, b -> f = f.copy(ageFrom = a, ageTo = b) }
                SectionTitle("Career status")
                Segmented(Status.entries, f.status, { it.label }) { f = f.copy(status = it) }
                Range("Debut year", f.debutFrom, f.debutTo, MIN_DEBUT, CURRENT_YEAR) { a, b -> f = f.copy(debutFrom = a, debutTo = b) }
                Range("Height (cm)", f.heightFrom, f.heightTo, MIN_HEIGHT, MAX_HEIGHT) { a, b -> f = f.copy(heightFrom = a, heightTo = b) }
                SectionTitle("Tattoos")
                Segmented(TriState.entries, f.tattoos, { it.label }) { f = f.copy(tattoos = it) }
                SectionTitle("Piercings")
                Segmented(TriState.entries, f.piercings, { it.label }) { f = f.copy(piercings = it) }
                Chips("My tags", options.tags, f.tags) { f = f.copy(tags = f.tags.toggle(it)) }
                Chips("Gender", options.genders, f.genders) { f = f.copy(genders = f.genders.toggle(it)) }
                Chips("Ethnicity", options.ethnicities, f.ethnicities) { f = f.copy(ethnicities = f.ethnicities.toggle(it)) }
                Chips("Hair", options.hair, f.hair) { f = f.copy(hair = f.hair.toggle(it)) }
                Chips("Eyes", options.eyes, f.eyes) { f = f.copy(eyes = f.eyes.toggle(it)) }
                Chips("Cup size", options.cups, f.cups) { f = f.copy(cups = f.cups.toggle(it)) }
                Chips("Nationality", options.nationalities, f.nationalities) { f = f.copy(nationalities = f.nationalities.toggle(it)) }
                Spacer(Modifier.height(16.dp))
            }
            Button(onClick = { onApply(f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).height(50.dp)) {
                Text("Show results", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Range(title: String, from: Int, to: Int, min: Int, max: Int, suffixMax: String = "", onChange: (Int, Int) -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        SectionTitle(title, Modifier.weight(1f))
        Text("$from – $to${if (to == max) suffixMax else ""}", color = Accent, modifier = Modifier.padding(bottom = 8.dp))
    }
    RangeSlider(
        value = from.toFloat()..to.toFloat(),
        onValueChange = { onChange(it.start.roundToInt(), it.endInclusive.roundToInt()) },
        valueRange = min.toFloat()..max.toFloat(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        values.forEachIndexed { i, v ->
            SegmentedButton(
                selected = v == selected, onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, values.size),
            ) { Text(label(v)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: String, all: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    if (all.isEmpty()) return
    SectionTitle(title)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        all.forEach { v -> FilterChip(selected = v in selected, onClick = { onToggle(v) }, label = { Text(v) }) }
    }
}
