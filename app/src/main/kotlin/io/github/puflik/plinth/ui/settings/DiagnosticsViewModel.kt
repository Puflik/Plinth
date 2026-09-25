package io.github.puflik.plinth.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.puflik.plinth.di.IoDispatcher
import io.github.puflik.plinth.diagnostics.AppInfo
import io.github.puflik.plinth.diagnostics.CrashStore
import io.github.puflik.plinth.diagnostics.IssueTemplate
import io.github.puflik.plinth.diagnostics.LogExporter
import io.github.puflik.plinth.diagnostics.log.LogFileWriter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import javax.inject.Inject

/**
 * Диагностика (G1.3): после сбоя — предложение сохранить отчёт, в
 * настройках — «сохранить лог» и «сообщить о проблеме». Отчёт и лог
 * сохраняются одним файлом; сохранили или отказались — предложение
 * больше не появляется.
 */
@HiltViewModel
class DiagnosticsViewModel
    @Inject
    constructor(
        private val crashes: CrashStore,
        private val logs: LogFileWriter,
        private val exporter: LogExporter,
        private val info: AppInfo,
        @IoDispatcher private val io: CoroutineDispatcher,
    ) : ViewModel() {
        private val crash = MutableStateFlow<String?>(null)
        private val mutableHasCrash = MutableStateFlow(false)

        /** Прошлый запуск закончился сбоем, и про отчёт ещё не ответили. */
        val hasCrash: StateFlow<Boolean> = mutableHasCrash.asStateFlow()

        /** Шаблон нового сообщения об ошибке — открывается только по просьбе человека. */
        val issueUrl: String get() = IssueTemplate.url(info)

        init {
            viewModelScope.launch {
                val pending = withContext(io) { crashes.pending() }
                crash.value = pending
                mutableHasCrash.value = pending != null
            }
        }

        /** Отчёт о сбое, если есть, и лог — в [out]; предложение после этого снимается. */
        suspend fun writeReport(out: OutputStream) {
            withContext(io) {
                exporter.export(crash.value, logs.files(), out)
                crashes.clear()
            }
            crash.value = null
            mutableHasCrash.value = false
        }

        fun dismissCrash() {
            crash.value = null
            mutableHasCrash.value = false
            viewModelScope.launch { withContext(io) { crashes.clear() } }
        }

        /**
         * Диалог закрыт касанием мимо или «Назад» (ревью №14) — это не отказ:
         * отчёт остаётся на диске, «Сохранить лог» его выгрузит, а при
         * следующем запуске предложение появится снова.
         */
        fun closeCrashOffer() {
            mutableHasCrash.value = false
        }
    }
