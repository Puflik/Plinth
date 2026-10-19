package io.github.puflik.plinth.spike

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Спайк Ю3а (одноразовый код, в `main` не идёт): отладочный экран. Запуск:
 * `adb shell am start -n <пакет>/io.github.puflik.plinth.spike.SpikeActivity --es scenario all`
 * (`cold` — только холодный путь). Журнал — logcat (тег `Yu3a`) и файл
 * `Android/data/<пакет>/files/yu3a-report.txt`.
 */
class SpikeActivity : ComponentActivity() {
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 11f }
        setContentView(ScrollView(this).apply { addView(view) })
        if (started || savedInstanceState != null) return
        started = true
        val scenario = intent.getStringExtra("scenario") ?: "all"
        if (scenario == "bg") {
            startForegroundService(
                android.content.Intent(this, SpikePlayService::class.java).putExtra("seconds", intent.getIntExtra("seconds", 75)),
            )
            view.text = "bg: служба запущена"
            return
        }
        val file = File(getExternalFilesDir(null), "yu3a-report.txt")
        file.delete()
        val report = Report(file)
        report.onChange = { text -> runOnUiThread { view.text = text } }
        lifecycleScope.launch { SpikeRunner(applicationContext, report).run(scenario) }
    }
}
