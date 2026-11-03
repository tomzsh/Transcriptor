package id.tomz.transcriptor

import android.app.Application
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import id.tomz.transcriptor.diagnostics.AppDiagnostics

@HiltAndroidApp
class TranscriptorApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageSettings.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
    }
}
