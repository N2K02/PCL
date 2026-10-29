package com.pcl.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A modern, compact Recents card. It does not mutate a caller or the Gateway:
 * every action remains explicitly user-initiated and governed by policy.
 */
@Composable
internal fun RecentCallCard(
    record: CallHistory.Record,
    lookedUpName: String?,
    ui: CallsUi,
    actions: CallsActions,
    onDelete: (CallHistory.Record) -> Unit,
) {
    val context = LocalContext.current
    val number = CallerId.asNumber(record.number)
    val contactName = CallerId.asName(record.name).ifEmpty {
        CallerId.asName(lookedUpName)
    }
    val title = RecentCallActionsPolicy.title(record, lookedUpName)
    val source = RecentCallPresentation.source(
        record.sourceApp, record.whatsapp, record.simIdentity)
    val flags = RecentCallActionsPolicy.availability(
        record, ui.connected, ui.callState == LinkService.CALL_IDLE,
        ui.availableSimSlots)
    var menuVisible by remember(record) { mutableStateOf(false) }
    var detailsVisible by remember(record) { mutableStateOf(false) }
    var confirmDelete by remember(record) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(23.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { detailsVisible = true },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarColor = when (record.type) {
                CallHistory.Type.MISSED, CallHistory.Type.DECLINED ->
                    MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.secondaryContainer
            }
            Surface(shape = CircleShape, color = avatarColor,
                modifier = Modifier.size(43.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    val initials = contactName.split(' ').filter { it.isNotBlank() }
                        .take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }
                        .joinToString("").ifEmpty { "#" }
                    Text(initials, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                RecentCallBadges(record)
                record.simIdentity?.let { sim ->
                    Text(sim.description().substringAfter(" · ", ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(RecentCallActionsPolicy.dateTime(record.at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = { menuVisible = true },
                    modifier = Modifier.size(35.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More call actions")
                }
                if (record.durationSec > 0L) {
                    Surface(shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        border = BorderStroke(0.75.dp,
                            MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AccessTime, contentDescription = null,
                                modifier = Modifier.size(13.dp))
                            Text(RecentCallActionsPolicy.duration(record.durationSec),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    if (menuVisible) {
        ModalBottomSheet(
            onDismissRequest = { menuVisible = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                SheetAction("Make a quick call", Icons.Default.Call, flags.quickCall) {
                    menuVisible = false
                    actions.simCall(number, contactName)
                }
                if (SimRecentsAnalytics.isSim(record)) {
                    SheetAction("Call with SIM 1", Icons.Default.SimCard, flags.simOne) {
                        menuVisible = false
                        actions.simCallFromSlot(number, contactName, 0)
                    }
                    SheetAction("Call with SIM 2", Icons.Default.SimCard, flags.simTwo) {
                        menuVisible = false
                        actions.simCallFromSlot(number, contactName, 1)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                SheetAction("Share contact", Icons.Default.Share, flags.share) {
                    menuVisible = false
                    val share = RecentCallActionsPolicy.share(record, lookedUpName)
                    if (share != null) runCatching {
                        context.startActivity(Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, share), "Share contact"))
                    }.onFailure { showRecentActionError(context) }
                }
                SheetAction("Copy number", Icons.Default.ContentCopy, flags.copy) {
                    menuVisible = false
                    runCatching {
                        val clipboard = context.getSystemService(
                            Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Phone number", number))
                    }.onFailure { showRecentActionError(context) }
                }
                SheetAction("View details", Icons.Default.Info, true) {
                    menuVisible = false
                    detailsVisible = true
                }
                SheetAction("Add / Edit contact", Icons.Default.PersonAdd, flags.contact) {
                    menuVisible = false
                    // This opens the contact editor on the Remote phone only.
                    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT)
                        .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                        .putExtra(ContactsContract.Intents.Insert.PHONE, number)
                    if (contactName.isNotEmpty()) intent.putExtra(
                        ContactsContract.Intents.Insert.NAME, contactName)
                    runCatching { context.startActivity(intent) }
                        .onFailure { showRecentActionError(context) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
                SheetAction("Delete from list", Icons.Default.DeleteOutline, true,
                    destructive = true) {
                    menuVisible = false
                    confirmDelete = true
                }
            }
        }
    }

    if (detailsVisible) {
        ModalBottomSheet(
            onDismissRequest = { detailsVisible = false },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            Column(Modifier.fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Call details", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.titleMedium)
                RecentCallBadges(record)
                DetailField("Date and time", RecentCallActionsPolicy.dateTime(record.at))
                DetailField("Call type", RecentCallPresentation.outcome(record.type))
                DetailField("Source", source)
                if (number.isNotEmpty()) DetailField("Phone number", number)
                record.simIdentity?.let { DetailField("SIM / carrier", it.description()) }
                DetailField("Talk time",
                    RecentCallActionsPolicy.duration(record.durationSec))
                Text("Saved in PCL on this Remote phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this call?") },
            text = {
                Text("Remove only this entry from PCL Recents on this phone. " +
                    "Android's call log and your contacts will not be changed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(record)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(label, color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                else if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface)
        },
        leadingContent = {
            Icon(icon, null, tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                destructive -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            })
        },
        modifier = Modifier.fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun DetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun showRecentActionError(context: Context) {
    Toast.makeText(context,
        "No compatible app is available for that action",
        Toast.LENGTH_LONG).show()
}
