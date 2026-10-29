package com.pcl.app
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

/** A single Logs page for private diagnostics, crash history and live call activity. */
@Composable
internal fun PCLLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val callActivity by CallLogger.lines.collectAsState()
    N2K02UnifiedLogsPage(
        modifier = modifier, appName = "PCL",
        extraLabel = "Gateway / Remote activity",
        extraLines = callActivity,
        clearExtra = CallLogger::clear,
    )
}
