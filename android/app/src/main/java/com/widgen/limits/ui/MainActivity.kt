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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
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
import com.widgen.limits.data.model.AccountDetail
import com.widgen.limits.data.model.CodexInfo
import com.widgen.limits.data.model.ModelQuota
import com.widgen.limits.data.model.PoolInfo
import com.widgen.limits.data.model.QuotaSnapshot
import com.widgen.limits.data.repository.QuotaRepository
import com.widgen.limits.data.util.BridgeUrlValidator
import com.widgen.limits.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = QuotaRepository.getInstance(applicationContext)

        setContent {
            AntigravityLimitsTheme {
                MainScreen(repository = repository)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: QuotaRepository
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
                            text = "AI Limits",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = SurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                        ) {
                            Text(
                                text = "MULTI-ACCOUNT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = GeminiCyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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
        val bridgeUrl = repository.getBridgeUrl()
        val isConfigured = bridgeUrl.isNotBlank()

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. Setup Banner if not configured
            if (!isConfigured) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(SurfaceDark)
                            .border(1.dp, GeminiCyan, RoundedCornerShape(16.dp))
                            .clickable { showSettingsDialog = true }
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "⚙️ Configure PC Bridge",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = GeminiCyan
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap here to enter your PC Bridge URL and API Token from ~/.widgen/config.json",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // 1. OpenAI Codex Section
            item {
                val codex = snapshot?.codex
                if (codex != null) {
                    CodexHeroCard(codex = codex)
                } else {
                    CodexOfflineCard()
                }
            }

            // 2. Antigravity Accounts Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Antigravity Accounts",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Tap to switch on PC",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            // Antigravity Accounts List
            val accounts = snapshot?.antigravity?.accounts.orEmpty()
            if (accounts.isNotEmpty()) {
                items(accounts) { acc ->
                    AccountSwitchCard(
                        account = acc,
                        onSwitch = {
                            scope.launch {
                                repository.switchAccount(acc.id)
                            }
                        }
                    )
                }
            } else {
                item {
                    // Fallback to single account card if multi-account list empty
                    AccountBannerCard(snapshot = snapshot, repository = repository)
                }
            }

            // 3. Active Account Live Quota Pools
            item {
                val geminiPool = snapshot?.pools?.gemini
                PoolHeroCard(
                    title = "Gemini Models",
                    subtitle = "Active account session",
                    pool = geminiPool,
                    accentColor = GeminiCyan,
                    badgeText = "Rolling Session"
                )
            }

            item {
                val claudePool = snapshot?.pools?.claudeGpt
                PoolHeroCard(
                    title = "Claude & GPT Models",
                    subtitle = "Active account weekly",
                    pool = claudePool,
                    accentColor = ClaudePurple,
                    badgeText = "Weekly Quota"
                )
            }

            // 4. Expandable Individual Models Breakdown
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
                                text = "Models Breakdown (${allModels.size})",
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

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentUrl = repository.getBridgeUrl(),
            currentToken = repository.getApiToken(),
            onDismiss = { showSettingsDialog = false },
            onSave = { newUrl, newToken ->
                repository.setBridgeUrl(newUrl)
                repository.setApiToken(newToken)
                showSettingsDialog = false
                scope.launch {
                    repository.refreshQuota()
                }
            }
        )
    }
}

@Composable
fun CodexHeroCard(codex: CodexInfo) {
    val session = codex.sessionWindow
    val weekly = codex.weeklyWindow
    val sFrac = session?.let { (it.remainingPercent / 100f).coerceIn(0f, 1f) } ?: 0f
    val wFrac = weekly?.let { (it.remainingPercent / 100f).coerceIn(0f, 1f) } ?: 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusGreen)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "OpenAI Codex",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Text(
                    text = "${codex.plan.uppercase()} · ${codex.email}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Session Window (5-hour)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "5-Hour Session Window", fontSize = 13.sp, color = TextSecondary)
            Text(
                text = if (session != null) "${session.remainingPercent}% left" else "—",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (session != null) StatusGreen else TextSecondary
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { sFrac },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (session != null) StatusGreen else SurfaceElevated,
            trackColor = SurfaceElevated
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (session != null) "Reset in: ${session.resetFormatted}" else "Status: Unavailable",
            fontSize = 11.sp,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Weekly Window
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Weekly Quota Window", fontSize = 13.sp, color = TextSecondary)
            val wCol = if (weekly != null) {
                if (weekly.remainingPercent < 20) StatusRose else StatusAmber
            } else TextSecondary
            Text(
                text = if (weekly != null) "${weekly.remainingPercent}% left" else "—",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = wCol
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { wFrac },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (weekly != null) (if (weekly.remainingPercent < 20) StatusRose else StatusAmber) else SurfaceElevated,
            trackColor = SurfaceElevated
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (weekly != null) "Reset in: ${weekly.resetFormatted}" else "Status: Unavailable",
                fontSize = 11.sp,
                color = TextSecondary
            )
            if (codex.resetCredits > 0) {
                Text(
                    text = "${codex.resetCredits} reset credit available",
                    fontSize = 11.sp,
                    color = GeminiCyan,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun CodexOfflineCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SurfaceDark)
            .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusRose)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "OpenAI Codex",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceElevated,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Text(
                    text = "OFFLINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = StatusRose,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Codex quota is currently offline or unreachable. Check bridge connection and token.",
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}

@Composable
fun AccountSwitchCard(
    account: AccountDetail,
    onSwitch: () -> Unit
) {
    val isCur = account.isCurrent
    val borderCol = if (isCur) GeminiCyan else BorderDark
    val bgCol = if (isCur) SurfaceElevated else SurfaceDark

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bgCol)
            .border(if (isCur) 1.5.dp else 1.dp, borderCol, RoundedCornerShape(16.dp))
            .padding(14.dp)
            .clickable(enabled = !isCur) { onSwitch() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = account.email,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Text(
                    text = account.name,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }

            if (isCur) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = GeminiCyan.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = GeminiCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ACTIVE ON PC",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = GeminiCyan
                        )
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = onSwitch,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SurfaceElevated,
                        contentColor = TextPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch",
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Switch", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Gemini: ${account.geminiPercent}% (${account.geminiReset})",
                fontSize = 12.sp,
                color = GeminiCyan,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Claude: ${account.claudePercent}% (${account.claudeReset})",
                fontSize = 12.sp,
                color = ClaudePurple,
                fontWeight = FontWeight.Medium
            )
        }
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
    val isKnown = pool != null
    val pct = pool?.remainingPercent ?: 0
    val frac = if (isKnown) (pool?.remainingFraction ?: 1.0f).coerceIn(0f, 1f) else 0f
    val resetStr = if (isKnown) pool?.resetFormatted ?: "Ready" else "Unavailable"
    val pctText = if (isKnown) "$pct%" else "—"

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

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = pctText,
                fontSize = 36.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
                color = if (isKnown) accentColor else TextSecondary
            )
            if (isKnown) {
                Text(
                    text = "remaining",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = if (isKnown) accentColor else SurfaceElevated,
            trackColor = SurfaceElevated
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                color = if (isKnown) TextPrimary else TextSecondary
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
    currentToken: String,
    onDismiss: () -> Unit,
    onSave: (url: String, token: String) -> Unit
) {
    var urlText by remember { mutableStateOf(currentUrl) }
    var tokenText by remember { mutableStateOf(currentToken) }
    var validationError by remember { mutableStateOf<String?>(null) }

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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PC LAN / Tailscale IP (e.g. http://192.168.1.50:59123):",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        validationError = null
                    },
                    singleLine = true,
                    isError = validationError != null,
                    placeholder = { Text("http://<PC-LAN-IP>:59123", color = TextSecondary.copy(alpha = 0.5f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = GeminiCyan,
                        unfocusedBorderColor = BorderDark,
                        errorBorderColor = Color(0xFFEF4444)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Text(
                        text = validationError ?: "",
                        color = Color(0xFFEF4444),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Text(
                    text = "API Bearer Token:",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    placeholder = { Text("From ~/.widgen/config.json", color = TextSecondary.copy(alpha = 0.5f)) },
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
                onClick = {
                    val normalized = BridgeUrlValidator.normalize(urlText)
                    normalized.fold(
                        onSuccess = { cleanUrl ->
                            validationError = null
                            onSave(cleanUrl, tokenText.trim())
                        },
                        onFailure = { error ->
                            validationError = error.message ?: "Invalid Bridge URL"
                        }
                    )
                },
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
