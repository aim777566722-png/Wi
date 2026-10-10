package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entity.DataUsageSnapshotEntity
import com.example.data.model.FilterPeriod
import com.example.data.model.ReadingStatus
import com.example.ui.components.Formatters
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.MainUiState

@Composable
fun NetworkDetailScreen(
    uiState: MainUiState,
    onBack: () -> Unit,
    onPeriodSelected: (FilterPeriod) -> Unit
) {
    BackHandler { onBack() }

    val ssid = uiState.selectedNetworkSsid ?: "تفاصيل الشبكة"

    // فحص حالة اكتمال البيانات
    val hasRebootSnapshots = uiState.selectedNetworkSnapshots.any {
        it.readingStatus == ReadingStatus.REBOOT_RESET.name
    }
    val hasInitialSnapshots = uiState.selectedNetworkSnapshots.any {
        it.readingStatus == ReadingStatus.BASELINE_INITIAL.name
    }
    val hasRollbackSnapshots = uiState.selectedNetworkSnapshots.any {
        it.readingStatus == ReadingStatus.COUNTER_ROLLBACK.name
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("network_detail_screen_content")
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // شريط العنوان مع زر الرجوع
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "الرجوع"
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = ssid,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تفاصيل استهلاك البيانات وسجلات القراءات",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // منتقي الفترات الزمنية
        item {
            PeriodSelectorRow(
                selectedPeriod = uiState.selectedPeriod,
                onPeriodSelected = onPeriodSelected
            )
        }

        // بطاقة ملخص الاستهلاك للفترة المحددة
        item {
            PeriodSummaryCard(
                uiState = uiState
            )
        }

        // توضيح حالة اكتمال البيانات وموثوقية القراءات
        item {
            DataCompletenessCard(
                snapshotsCount = uiState.selectedNetworkSnapshots.size,
                hasReboots = hasRebootSnapshots,
                hasInitials = hasInitialSnapshots,
                hasRollbacks = hasRollbackSnapshots
            )
        }

        // عنوان قائمة القراءات
        item {
            Text(
                text = "قراءات هذه الشبكة للفترة المحددة (${uiState.selectedNetworkSnapshots.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.selectedNetworkSnapshots.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد قراءات مسجلة لهذه الشبكة في الفترة الزمنية المختارة",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(uiState.selectedNetworkSnapshots) { snapshot ->
                NetworkSnapshotItem(snapshot = snapshot)
            }
        }
    }
}

@Composable
fun PeriodSelectorRow(
    selectedPeriod: FilterPeriod,
    onPeriodSelected: (FilterPeriod) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPeriod.entries.forEach { period ->
            FilterChip(
                selected = selectedPeriod == period,
                onClick = { onPeriodSelected(period) },
                label = { Text(period.arabicTitle) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("filter_period_${period.name}"),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

@Composable
fun PeriodSummaryCard(uiState: MainUiState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("period_summary_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "استهلاك ${uiState.selectedPeriod.arabicTitle}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = Formatters.formatBytes(uiState.selectedPeriodTotalBytes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // التنزيل
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowDownward,
                            contentDescription = "تنزيل",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "التنزيل (Rx)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = Formatters.formatBytes(uiState.selectedPeriodRxBytes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // الرفع
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "رفع",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "الرفع (Tx)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = Formatters.formatBytes(uiState.selectedPeriodTxBytes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DataCompletenessCard(
    snapshotsCount: Int,
    hasReboots: Boolean,
    hasInitials: Boolean,
    hasRollbacks: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (hasReboots || hasRollbacks) {
                WarningAmber.copy(alpha = 0.1f)
            } else {
                SuccessGreen.copy(alpha = 0.08f)
            }
        ),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasReboots || hasRollbacks) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (hasReboots || hasRollbacks) WarningAmber else SuccessGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "حالة اكتمال وموثوقية البيانات",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                snapshotsCount == 0 -> {
                    Text(
                        text = "لا توجد قراءات حتى الآن في هذه الفترة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                hasReboots -> {
                    Text(
                        text = "تم رصد إعادة تشغيل للهاتف في هذه الفترة؛ تم تصفير عدادات النظام وإنشاء نقطة مرجعية سليمة بدون احتساب التراكم القديم كاستهلاك جديد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                hasRollbacks -> {
                    Text(
                        text = "تم رصد تراجع طفيف في عدادات النظام وتم استبعاد الزيادات السلبية لضمان النزاهة الإحصائية.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    Text(
                        text = "القراءات مكتملة وموثوقة، ومحسوبة مباشرة من فروق العدادات المتتالية لنظام Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkSnapshotItem(snapshot: DataUsageSnapshotEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Formatters.formatDateTime(snapshot.timestamp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                SnapshotStatusBadge(statusName = snapshot.readingStatus)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "الزيادة: +${Formatters.formatBytes(snapshot.deltaRxBytes + snapshot.deltaTxBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "تنزيل: ${Formatters.formatBytes(snapshot.deltaRxBytes)} | رفع: ${Formatters.formatBytes(snapshot.deltaTxBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (!snapshot.notes.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = snapshot.notes,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun SnapshotStatusBadge(statusName: String) {
    val (label, color) = when (statusName) {
        ReadingStatus.VALID.name -> Pair("موثوقة", SuccessGreen)
        ReadingStatus.BASELINE_INITIAL.name -> Pair("مرجعية أولى", InfoBlue)
        ReadingStatus.REBOOT_RESET.name -> Pair("إعادة تشغيل", WarningAmber)
        ReadingStatus.NETWORK_SWITCH.name -> Pair("تبديل شبكة", MaterialTheme.colorScheme.secondary)
        ReadingStatus.DISCONNECTED_FALLBACK.name -> Pair("انقطاع", MaterialTheme.colorScheme.outline)
        else -> Pair(statusName, MaterialTheme.colorScheme.outline)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}
