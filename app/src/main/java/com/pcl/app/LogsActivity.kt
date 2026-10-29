package com.pcl.app
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Uses the main app's n2k02theme and single Settings header. */
@Composable
internal fun PCLLogsPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
    N2K02UnifiedLogsPage(modifier = modifier, appName = "PCL", onBack = onBack)
}
