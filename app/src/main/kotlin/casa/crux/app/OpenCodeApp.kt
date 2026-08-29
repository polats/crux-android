package casa.crux.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import casa.crux.app.data.repository.DiagnosticLogRepository
import casa.crux.app.logging.AppLogger
import javax.inject.Inject

/**
 * Crux Application
 * Entry point for Hilt dependency injection
 */
@HiltAndroidApp
class OpenCodeApp : Application() {
    @Inject lateinit var diagnosticLogRepository: DiagnosticLogRepository

    override fun onCreate() {
        super.onCreate()
        AppLogger.initialize(diagnosticLogRepository)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppLogger.recordCrash(thread, error)
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
    }
}
