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
        val account = snapshot?.account
        val email = account?.email?.ifBlank { "Antigravity" } ?: "Antigravity"
        val isOnline = snapshot?.isOnline == true

        val geminiPool = snapshot?.pools?.gemini
        val claudePool = snapshot?.pools?.claudeGpt

        // Colors
        val bgCol = Color(0xFF0E1117)
        val surfaceCol = Color(0xFF161B22)
        val textPrimary = Color(0xFFF0F6FC)
        val textSecondary = Color(0xFF8B949E)
        val cyanCol = Color(0xFF00E5FF)
        val purpleCol = Color(0xFFA855F7)
        val greenCol = Color(0xFF10B981)
        val amberCol = Color(0xFFF59E0B)

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgCol)
                .cornerRadius(18.dp)
                .padding(12.dp)
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
                            .size(8.dp)
                            .cornerRadius(4.dp)
                            .background(if (isOnline) greenCol else amberCol)
                    ) {}
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    Text(
                        text = email,
                        style = TextStyle(
                            color = ColorProvider(textPrimary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "↻",
                        style = TextStyle(
                            color = ColorProvider(cyanCol),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = GlanceModifier
                            .padding(horizontal = 4.dp)
                            .clickable(actionRunCallback<RefreshWidgetCallback>())
                    )
                }

                Spacer(modifier = GlanceModifier.height(8.dp))

                // Gemini Pool Card
                PoolRow(
                    title = "Gemini (Flash/Pro)",
                    pool = geminiPool,
                    accentColor = cyanCol,
                    surfaceColor = surfaceCol,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = GlanceModifier.height(6.dp))

                // Claude / GPT Pool Card
                PoolRow(
                    title = "Claude & GPT",
                    pool = claudePool,
                    accentColor = purpleCol,
                    surfaceColor = surfaceCol,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary
                )

                Spacer(modifier = GlanceModifier.defaultWeight())

                // Footer with credits info
                if (account != null) {
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Credits: ${account.promptCredits} prompt · ${account.flowCredits} flow",
                            style = TextStyle(
                                color = ColorProvider(textSecondary),
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PoolRow(
        title: String,
        pool: PoolInfo?,
        accentColor: Color,
        surfaceColor: Color,
        textPrimary: Color,
        textSecondary: Color
    ) {
        val pct = pool?.remainingPercent ?: 100
        val frac = (pool?.remainingFraction ?: 1.0f).coerceIn(0f, 1f)
        val resetStr = pool?.resetFormatted ?: "Ready"

        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(surfaceColor)
                .cornerRadius(10.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp)
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
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = GlanceModifier.defaultWeight()
                    )
                    Text(
                        text = "$pct%",
                        style = TextStyle(
                            color = ColorProvider(accentColor),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                Spacer(modifier = GlanceModifier.height(4.dp))

                LinearProgressIndicator(
                    progress = frac,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp),
                    color = ColorProvider(accentColor),
                    backgroundColor = ColorProvider(Color(0xFF282F3D))
                )

                Spacer(modifier = GlanceModifier.height(3.dp))

                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = "Reset in: $resetStr",
                        style = TextStyle(
                            color = ColorProvider(textSecondary),
                            fontSize = 9.sp
                        )
                    )
                }
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
