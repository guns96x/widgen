package com.widgen.limits.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.widgen.limits.data.model.ModelQuota
import com.widgen.limits.data.model.PoolInfo
import com.widgen.limits.data.model.QuotaSnapshot
import com.widgen.limits.data.repository.QuotaRepository
import com.widgen.limits.ui.theme.*
import com.widgen.limits.worker.QuotaSyncWorker
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = QuotaRepository.getInstance(applicationContext)

        setContent {
            AntigravityLimitsTheme {
                MainScreen(
                    repository = repository,
                    onRefreshRequested = {
                        val request = OneTimeWorkRequestBuilder<QuotaSyncWorker>().build()
                        WorkManager.getInstance(applicationContext).enqueue(request)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: QuotaRepository,
    onRefreshRequested: () -> Unit
) {
    val snapshot by repository.snapshotFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var isRefreshing by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var expandedModels by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .navigationBarsPadding(),
        containerColor = BgDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Antigravity Limits",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val plan = snapshot?.account?.plan ?: "PRO"
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                        ) {
                            Text(
                                text = plan.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = GeminiCyan,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!isRefreshing) {
                        isRefreshing = true
                        scope.launch {
                            repository.refreshQuota()
                            onRefreshRequested()
                            isRefreshing = false
                        }
                    }
                },
                containerColor = GeminiCyan,
                contentColor = Color.Black,
                shape = CircleShape
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh"
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account & Status Banner
            item {
                AccountBannerCard(snapshot = snapshot, repository = repository)
            }

            // Pool Card: Gemini Flash & Pro
            item {
                val geminiPool = snapshot?.pools?.gemini
                PoolHeroCard(
                    title = "Gemini Models",
                    subtitle = "3.8 Flash · 3.7 Flash · 3.1 Pro",
                    pool = geminiPool,
                    accentColor = GeminiCyan,
                    badgeText = "Rolling Session"
                )
            }

            // Pool Card: Claude & GPT
            item {
                val claudePool = snapshot?.pools?.claudeGpt
                PoolHeroCard(
                    title = "Claude & GPT Models",
                    subtitle = "Sonnet 4.6 · Opus 4.6 · GPT-OSS",
                    pool = claudePool,
                    accentColor = ClaudePurple,
                    badgeText = "Weekly Quota"
                )
            }

            // Expandable List of Individual Models
            item {
                val allModels = (snapshot?.pools?.gemini?.models.orEmpty()) +
                                (snapshot?.pools?.claudeGpt?.models.orEmpty())

                if (allModels.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedModels = !expandedModels },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Individual Models Breakdown (${allModels.size})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimary
                            )
                            Icon(
                                imageVector = if (expandedModels) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Expand",
                                tint = TextSecondary
                            )
                        }

                        AnimatedVisibility(visible = expandedModels) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                allModels.forEach { model ->
                                    ModelItemRow(model = model)
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Spacing for FAB
            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentUrl = repository.getBridgeUrl(),
            onDismiss = { showSettingsDialog = false },
            onSave = { newUrl ->
                repository.setBridgeUrl(newUrl)
                showSettingsDialog = false
                scope.launch {
                    repository.refreshQuota()
                    onRefreshRequested()
                }
            }
        )
    }
}

@Composable
fun AccountBannerCard(snapshot: QuotaSnapshot?, repository: QuotaRepository) {
    val account = snapshot?.account
    val isOnline = snapshot?.isOnline == true
    val email = account?.email?.ifBlank { "No connection" } ?: "Connecting..."

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isOnline) StatusGreen else StatusRose)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = email,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (account != null) {
                    "Credits: ${account.promptCredits} prompt · ${account.flowCredits} flow"
                } else {
                    "Bridge: ${repository.getBridgeUrl()}"
                },
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 1
            )
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (isOnline) StatusGreen.copy(alpha = 0.15f) else StatusRose.copy(alpha = 0.15f)
        ) {
            Text(
                text = if (isOnline) "SYNCED" else "OFFLINE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isOnline) StatusGreen else StatusRose,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
fun PoolHeroCard(
    title: String,
    subtitle: String,
    pool: PoolInfo?,
    accentColor: Color,
    badgeText: String
) {
    val pct = pool?.remainingPercent ?: 100
    val frac = (pool?.remainingFraction ?: 1.0f).coerceIn(0f, 1f)
    val resetStr = pool?.resetFormatted ?: "Ready"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Text(
                    text = badgeText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Large balance number
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "$pct%",
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
                color = accentColor
            )
            Text(
                text = "remaining",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = accentColor,
            trackColor = SurfaceElevated
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Reset in:",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Text(
                text = resetStr,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }
    }
}

@Composable
fun ModelItemRow(model: ModelQuota) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1
            )
            Text(
                text = "Reset: ${model.resetFormatted}",
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
        Text(
            text = "${model.remainingPercent}%",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = if (model.remainingPercent > 50) StatusGreen else if (model.remainingPercent > 20) StatusAmber else StatusRose
        )
    }
}

@Composable
fun SettingsDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        title = {
            Text(
                text = "PC Bridge Connection",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the IP address of your PC running the Widgen Bridge (e.g. 192.168.1.100:59123 or Tailscale IP):",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = GeminiCyan,
                        unfocusedBorderColor = BorderDark
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(urlText) },
                colors = ButtonDefaults.buttonColors(containerColor = GeminiCyan, contentColor = Color.Black)
            ) {
                Text("Save & Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
