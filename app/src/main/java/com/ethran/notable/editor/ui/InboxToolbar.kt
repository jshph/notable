package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val INBOX_TOOLBAR_COLLAPSED_HEIGHT: Dp = 55.dp
val INBOX_TOOLBAR_EXPANDED_HEIGHT: Dp = 170.dp

@Composable
fun InboxToolbar(
    selectedTags: List<String>,
    suggestedTags: List<String>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onTagAdd: (String) -> Unit,
    onTagRemove: (String) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    var tagInput by remember { mutableStateOf("") }

    fun submitTag() {
        val tags = tagInput
            .replace("#", "")
            .replace(",", " ")
            .split("\\s+".toRegex())
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        tags.forEach { onTagAdd(it) }
        tagInput = ""
    }

    val unselected = suggestedTags.filter { it !in selectedTags }
    val filtered = if (tagInput.isNotEmpty()) {
        val query = tagInput.lowercase().replace("#", "").trim()
        unselected.filter { it.contains(query) }
    } else {
        unselected
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
    ) {
        // Action bar: Back + toggle/tag count + Save & Exit
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .border(1.5.dp, Color.DarkGray, RoundedCornerShape(8.dp))
                    .clickable { onDiscard() }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    "← Back",
                    fontSize = 18.sp,
                    color = Color.DarkGray
                )
            }

            // Title + tag toggle
            Row(
                modifier = Modifier
                    .clickable { onToggleExpanded() }
                    .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val label = if (selectedTags.isEmpty()) "Capture"
                else "Capture · ${selectedTags.size} tag${if (selectedTags.size != 1) "s" else ""}"
                Text(
                    label,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse tags" else "Expand tags",
                    tint = Color.DarkGray,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .background(Color.Black, RoundedCornerShape(8.dp))
                    .clickable { onSave() }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    "Save & Exit",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        if (isExpanded) {
            // Search / add tag input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .border(1.5.dp, Color.Gray, RoundedCornerShape(8.dp))
                        .background(Color(0xFFFAFAFA), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (tagInput.isEmpty()) {
                        Text(
                            "search or add tags...",
                            fontSize = 17.sp,
                            color = Color(0xFF999999)
                        )
                    }
                    BasicTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        textStyle = TextStyle(
                            fontSize = 17.sp,
                            color = Color.Black
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(Color.Black),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submitTag() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color.Black, RoundedCornerShape(8.dp))
                        .clickable { submitTag() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add tag",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Tag pills row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                selectedTags.forEach { tag ->
                    TagPill(
                        tag = tag,
                        selected = true,
                        onTap = { onTagRemove(tag) }
                    )
                }

                if (filtered.isNotEmpty() && selectedTags.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(1.5.dp)
                            .height(28.dp)
                            .background(Color.LightGray)
                    )
                }

                filtered.forEach { tag ->
                    TagPill(
                        tag = tag,
                        selected = false,
                        onTap = {
                            onTagAdd(tag)
                            tagInput = ""
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
        }

        // Divider
        Divider(color = Color.DarkGray, thickness = 2.dp)
    }
}

@Composable
private fun TagPill(
    tag: String,
    selected: Boolean,
    onTap: () -> Unit
) {
    val bgColor = if (selected) Color.Black else Color.White
    val textColor = if (selected) Color.White else Color.Black
    val borderColor = if (selected) Color.Black else Color.Gray

    Box(
        modifier = Modifier
            .border(1.5.dp, borderColor, RoundedCornerShape(20.dp))
            .background(bgColor, RoundedCornerShape(20.dp))
            .clickable { onTap() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "#$tag",
                fontSize = 16.sp,
                color = textColor
            )
            if (selected) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove tag",
                    tint = textColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
