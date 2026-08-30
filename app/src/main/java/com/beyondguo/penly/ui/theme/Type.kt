package com.beyondguo.penly.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val PenlyTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PenText1),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PenText1),
    bodyLarge = TextStyle(fontSize = 16.sp, color = PenText2),
    bodyMedium = TextStyle(fontSize = 14.sp, color = PenText2),
    bodySmall = TextStyle(fontSize = 12.sp, color = PenText3),
    labelMedium = TextStyle(fontSize = 12.sp, color = PenText3),
)
