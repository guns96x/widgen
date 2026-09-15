package com.widgen.limits.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.widgen.limits.data.repository.QuotaRepository
import com.widgen.limits.widget.AntigravityWidget

class QuotaSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = QuotaRepository.getInstance(applicationContext)
        val result = repository.refreshQuota()

        // Always notify the Glance widget to update with fresh or cached data
        try {
            AntigravityWidget().updateAll(applicationContext)
        } catch (_: Exception) {}

        return if (result.isSuccess) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}
