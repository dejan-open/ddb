package io.substrate.regdemo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * T27: what a Compose screen draws, not only what it says. Every element
 * here carries one visual attribute the semantic walk must report:
 * a solid card with a radius and padding, a gradient band, a bordered
 * pill, an undescribed raster image, a tinted vector icon, styled text
 * and a text truncated to one line.
 */
@Composable
fun T27VisualsScreen() {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            "T27 Visuals",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            color = Color(0xFF1A237E),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(12.dp))
                .background(Color(0xFFFFF3E0), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("T27 card", color = Color(0xFFE65100))
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Brush.verticalGradient(listOf(Color(0xFF6A1B9A), Color(0xFF00897B)))),
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .border(2.dp, Color(0xFF2E7D32), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text("T27 pill", fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Image(
                painter = painterResource(android.R.drawable.star_big_on),
                contentDescription = null,
                modifier = Modifier.size(48.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.Star, contentDescription = "T27 star", tint = Color(0xFFD81B60))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "T27 a long line that cannot fit on one row of this screen and must be cut off at the end",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
