package com.hangge.rootpilot.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val RootPilotTypography = Typography(
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    ),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = Typography().labelSmall.copy(
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.6.sp,
    ),
    bodySmall = Typography().bodySmall.copy(letterSpacing = 0.2.sp),
)
