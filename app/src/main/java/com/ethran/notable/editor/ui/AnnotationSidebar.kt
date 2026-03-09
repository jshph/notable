package com.ethran.notable.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ethran.notable.R
import com.ethran.notable.ui.noRippleClickable

@Composable
fun AnnotationSidebar(
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWikiLink: () -> Unit,
    onTag: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(44.dp)
            .padding(vertical = 80.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Undo
        SidebarButton(
            iconId = R.drawable.undo,
            contentDescription = "Undo",
            onClick = onUndo
        )

        // Redo
        SidebarButton(
            iconId = R.drawable.redo,
            contentDescription = "Redo",
            onClick = onRedo
        )

        // Spacer/divider
        Box(
            Modifier
                .size(width = 28.dp, height = 1.dp)
                .background(Color.LightGray)
        )

        // Wiki link annotation
        SidebarButton(
            text = "[[",
            contentDescription = "Wiki link",
            onClick = onWikiLink
        )

        // Tag annotation
        SidebarButton(
            text = "#",
            contentDescription = "Tag",
            onClick = onTag
        )
    }
}

@Composable
private fun SidebarButton(
    iconId: Int? = null,
    text: String? = null,
    contentDescription: String,
    isSelected: Boolean = false,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) Color.Black else Color.White
    val fgColor = if (isSelected) Color.White else Color.Black
    val borderColor = if (isSelected) Color.Black else Color.Gray

    Box(
        modifier = Modifier
            .size(36.dp)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .background(bgColor, RoundedCornerShape(6.dp))
            .noRippleClickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            iconId != null -> Icon(
                painterResource(iconId),
                contentDescription = contentDescription,
                tint = fgColor,
                modifier = Modifier.size(20.dp)
            )
            text != null -> Text(
                text,
                fontSize = if (text.length > 2) 10.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
        }
    }
}
