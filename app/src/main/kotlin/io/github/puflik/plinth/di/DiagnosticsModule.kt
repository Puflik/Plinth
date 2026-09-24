package io.github.puflik.plinth.di

import android.content.Context
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.puflik.plinth.BuildConfig
import io.github.puflik.plinth.diagnostics.AppInfo
import io.github.puflik.plinth.diagnostics.CrashStore
import io.github.puflik.plinth.diagnostics.LogExporter
import io.github.puflik.plinth.diagnostics.log.BackgroundSink
import io.github.puflik.plinth.diagnostics.log.LogBuffer
import io.github.puflik.plinth.diagnostics.log.LogFileWriter
import io.github.puflik.plinth.diagnostics.log.LogLevel
import io.github.puflik.plinth.diagnostics.log.LogRedactor
import io.github.puflik.plinth.diagnostics.log.LogcatSink
import io.github.puflik.plinth.diagnostics.log.Logger
import io.github.puflik.plinth.flavor.FlavorConfig
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Singleton
import kotlin.time.Clock

/**
 * Диагностика (G1): один логгер на процесс — буфер в памяти, файл в
 * `filesDir/logs` (5 МБ на два файла, пишется в своём потоке) и logcat в
 * отладочной сборке. Релиз пишет с уровня INFO. Отчёт о сбое — в
 * `filesDir/crash`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiagnosticsModule {
    @Provides
    @Singleton
    fun provideLogBuffer(): LogBuffer = LogBuffer(BUFFER_SIZE)

    @Provides
    @Singleton
    fun provideLogFileWriter(
        @ApplicationContext context: Context,
    ): LogFileWriter = LogFileWriter(File(context.filesDir, "logs"), LOG_LIMIT_BYTES)

    @Provides
    @Singleton
    fun provideLogger(
        buffer: LogBuffer,
        file: LogFileWriter,
        clock: Clock,
    ): Logger {
        val writer = Executors.newSingleThreadExecutor { Thread(it, "plinth-log").apply { isDaemon = true } }
        val sinks =
            buildList {
                add(buffer)
                add(BackgroundSink(file, writer))
                if (BuildConfig.DEBUG) add(LogcatSink())
            }
        return Logger(sinks, LogRedactor(), clock, if (BuildConfig.DEBUG) LogLevel.DEBUG else LogLevel.INFO)
    }

    @Provides
    fun provideAppInfo(): AppInfo =
        AppInfo(
            version = BuildConfig.VERSION_NAME,
            flavor = FlavorConfig.NAME,
            androidRelease = Build.VERSION.RELEASE,
            api = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
        )

    @Provides
    @Singleton
    fun provideCrashStore(
        @ApplicationContext context: Context,
    ): CrashStore = CrashStore(File(context.filesDir, "crash"))

    @Provides
    fun provideLogExporter(
        info: AppInfo,
        clock: Clock,
    ): LogExporter = LogExporter(info, clock)

    private const val BUFFER_SIZE = 500
    private const val LOG_LIMIT_BYTES = 5L * 1024 * 1024
}
