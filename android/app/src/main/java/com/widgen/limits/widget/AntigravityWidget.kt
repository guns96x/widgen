package com.widgen.limits.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.widgen.limits.data.model.PoolInfo
import com.widgen.limits.data.model.QuotaSnapshot
import com.widgen.limits.data.repository.QuotaRepository
import com.widgen.limits.ui.MainActivity
import com.widgen.limits.worker.QuotaSyncWorker

class AntigravityWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = QuotaRepository.getInstance(context)
        val snapshot = repository.getCachedSnapshot()

        provideContent {
            GlanceTheme {
                WidgetContent(context = context, snapshot = snapshot)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: Context, snapshot: QuotaSnapshot?) {
        val activeAccount = snapshot?.account
        val email = activeAccount?.email?.ifBlank { "Antigravity" } ?: "Antigravity"
        val isOnline = snapshot?.isOnline == true

        val codex = snapshot?.codex
        val codexSessionPct = codex?.sessionWindow?.remainingPercent ?: 100
        val codexSessionFrac = codexSessionPct / 100f
        val codexSessionReset = codex?.sessionWindow?.resetFormatted?.let { "Reset: $it" } ?: "Ready"

        val codexWeeklyPct = codex?.weeklyWindow?.remainingPercent ?: 100
        val codexWeeklyFrac = codexWeeklyPct / 100f
        val codexWeeklyReset = codex?.weeklyWindow?.resetFormatted?.let { "Reset: $it" } ?: "Ready"

        val geminiPool = snapshot?.pools?.gemini
        val geminiPct = geminiPool?.remainingPercent ?: 100
        val geminiFrac = (geminiPool?.remainingFraction ?: 1.0f).coerceIn(0f, 1f)
        val geminiReset = geminiPool?.resetFormatted?.let { "Reset: $it" } ?: "Ready"

        val claudePool = snapshot?.pools?.claudeGpt
        val claudePct = claudePool?.remainingPercent ?: 100
        val claudeFrac = (claudePool?.remainingFraction ?: 1.0f).coerceIn(0f, 1f)
        val claudeReset = claudePool?.resetFormatted?.let { "Reset: $it" } ?: "Ready"

        // Colors
        val bgCol = Color(0xFF0E1117)
        val surfaceCol = Color(0xFF161B22)
        val textPrimary = Color(0xFFF0F6FC)
        val textSecondary = Color(0xFF8B949E)
        val codexGreen = Color(0xFF10B981)
        val codexSky = Color(0xFF38BDF8)
        val geminiCyan = Color(0xFF00E5FF)
        val claudePurple = Color(0xFFA855F7)
        val greenCol = Color(0xFF10B981)
        val amberCol = Color(0xFFF59E0B)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgCol)
                .cornerRadius(18.dp)
                .padding(10.dp)
                .clickable(actionStartActivity<MainActivity>())
        ) {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                // Top Header Row
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = GlanceModifier
                            .size(7.dp)
                            .cornerRadius(4.dp)
                            .background(if (isOnline) greenCol else amberCol)
                    ) {}
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = "AI Limits",
                        style = TextStyle(
                            color = ColorProvider(textPrimary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = email,
                        style = TextStyle(
                            color = ColorProvider(textSecondary),
                            fontSize = 10.sp
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "↻",
                        style = TextStyle(
                            color = ColorProvider(geminiCyan),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = GlanceModifier
                            .padding(horizontal = 4.dp)
                            .clickable(actionRunCallback<RefreshWidgetCallback>())
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Unified Quotas: 2-Column Side-by-Side (Codex & Antigravity)
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    // Left Column: OpenAI Codex
                    Column(
                        modifier = GlanceModifier.defaultWeight()
                    ) {
                        Text(
                            text = "CODEX",
                            style = TextStyle(
                                color = ColorProvider(codexGreen),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        MetricCard(
                            title = "5h Window",
                            percent = codexSessionPct,
                            fraction = codexSessionFrac,
                            resetText = codexSessionReset,
                            accentColor = codexGreen,
                            surfaceColor = surfaceCol,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        MetricCard(
                            title = "Weekly",
                            percent = codexWeeklyPct,
                            fraction = codexWeeklyFrac,
                            resetText = codexWeeklyReset,
                            accentColor = if (codexWeeklyPct < 20) amberCol else codexSky,
                            surfaceColor = surfaceCol,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Right Column: Antigravity
                    Column(
                        modifier = GlanceModifier.defaultWeight()
                    ) {
                        Text(
                            text = "ANTIGRAVITY",
                            style = TextStyle(
                                color = ColorProvider(geminiCyan),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        MetricCard(
                            title = "Gemini",
                            percent = geminiPct,
                            fraction = geminiFrac,
                            resetText = geminiReset,
                            accentColor = geminiCyan,
                            surfaceColor = surfaceCol,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary
                        )
                        Spacer(modifier = GlanceModifier.height(4.dp))
                        MetricCard(
                            title = "Claude & GPT",
                            percent = claudePct,
                            fraction = claudeFrac,
                            resetText = claudeReset,
                            accentColor = claudePurple,
                            surfaceColor = surfaceCol,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MetricCard(
        title: String,
        percent: Int,
        fraction: Float,
        resetText: String,
        accentColor: Color,
        surfaceColor: Color,
        textPrimary: Color,
        textSecondary: Color
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(surfaceColor)
                .cornerRadius(8.dp)
                .padding(horizontal = 7.dp, vertical = 5.dp)
        ) {
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = TextStyle(
                            color = ColorProvider(textSecondary),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = GlanceModifier.defaultWeight(),
                        maxLines = 1
                    )
                    Text(
                        text = "$percent%",
                        style = TextStyle(
                            color = ColorProvider(accentColor),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.height(3.dp))

                LinearProgressIndicator(
                    progress = fraction,
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp),
                    color = ColorProvider(accentColor),
                    backgroundColor = ColorProvider(Color(0xFF21262D))
                )

                Spacer(modifier = GlanceModifier.height(2.dp))

                Text(
                    text = resetText,
                    style = TextStyle(
                        color = ColorProvider(textSecondary),
                        fontSize = 8.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: androidx.glance.action.ActionParameters) {
        // Enqueue immediate one-time sync worker
        val request = OneTimeWorkRequestBuilder<QuotaSyncWorker>().build()
        WorkManager.getInstance(context).enqueue(request)
        // Refresh cached UI
        AntigravityWidget().update(context, glanceId)
    }
}
