# Правила R8 для релизной сборки (H5). Библиотеки (Media3, Hilt,
# WorkManager, Compose) несут свои правила; здесь — только то, что нужно
# нашему коду.

# Строки стектрейсов в отчётах о сбоях: номера строк остаются, имена классов
# восстанавливаются по mapping.txt, который лежит в релизе рядом с APK.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# QueueCommandsPlayer пересылает вызовы Player.Listener через прокси и
# узнаёт onAvailableCommandsChanged по имени метода. Переименуй R8 методы
# слушателя — сессия перестала бы видеть «следующий/предыдущий».
-keepclassmembernames interface androidx.media3.common.Player$Listener {
    <methods>;
}

# Rust-ядро (A3): биндинги UniFFI работают через JNA — рефлексией. JNA
# находит поля структур и методы колбэков по именам, а нативные методы
# библиотеки — по именам символов в libplinth_ffi.so. Переименование любого
# из них ломает ядро в релизе, хотя отладочная сборка работает.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class io.github.puflik.plinth.ffi.generated.** { *; }
# JNA ссылается на AWT, которого на Android нет, — эти ветки не исполняются.
-dontwarn java.awt.**

# YouTube (Ю3г): NewPipeExtractor и Rhino. В отладочной сборке R8 выключен, поэтому всё ниже видно только в release.
# Правила держат два стража: R8RulesGuardTest (текст этого файла) и R8OutputGuardTest (вывод R8 настоящей сборки).

# Языки «сколько времени назад» (timeago.patterns.af, am, ar, …, en, ru). У экстрактора v0.26.5 PatternMap зовёт
# getInstance() языков прямыми вызовами, так что сейчас без правила ничего не ломается: R8 переименовал бы около
# 100 классов (в логе и mapping они стали бы af, am, …), но работали бы они. Правило — как у приложения NewPipe:
# имена языков не должны зависеть от того, как R8 перестроит экстрактор в следующей версии.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }

# Rhino подгружает свои части по строковым именам: VMBridge (VMBridge_jdk18), Interpreter, RegExpImpl, XMLLibImpl,
# JavaAdapter, встроенные объекты JS (ScriptRuntime), пакет сообщений resources.Messages. Переименованные R8 они не
# находятся, и первый же вызов JS из экстрактора (расшифровка адреса потока, JavaScript.evaluateString) падает.
-keep class org.mozilla.javascript.** { *; }

# ClassFileWriter — сборка байт-кода в Codegen. Экстрактор гоняет Rhino интерпретатором (setInterpretedMode), эта
# ветка не исполняется, и сбоя без правила мы не наблюдали; держим одним классом, как NewPipe, чтобы имя не уплыло.
-keep class org.mozilla.classfile.ClassFileWriter

# Сообщения protobuf (в экстракторе — токены продолжения плейлистов) читают и пишут поля через рефлексию по именам.
# Переименованное поле — первое же сообщение падает «Field … not found».
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Предупреждения R8. Без строк ниже R8 падает на «Missing classes» (проверено на Rhino 1.8.1 и экстракторе v0.26.5):
# в Rhino есть ветки, которых на Android нет и которые в режиме интерпретатора не исполняются. Все правила — на пакет
# или класс, общего -dontwarn нет (и R8RulesGuardTest его не пропустит).
# java.beans (5 классов) нужен одному классу — JSON.stringify для Java-объектов; в расшифровке адреса потока его нет.
# (Пять отдельных -dontwarn java.beans.* из Ю3в этой строкой покрыты — убраны.)
-dontwarn org.mozilla.javascript.JavaToJSONConverters
# Консольные инструменты Rhino: в этой сборке на пакет ничего не ссылается (без строки ошибок нет), оставлено как в
# NewPipe на случай смены версии Rhino.
-dontwarn org.mozilla.javascript.tools.**
# javax.script (10 классов, rhino-engine-1.8.1.jar): «движок для javax.script», экстрактор зовёт Rhino напрямую
# (Context), поэтому он не нужен. Та же строка снимает предупреждение «Unexpected reference to missing service class:
# META-INF/services/javax.script.ScriptEngineFactory» — этот файл jar несёт, а интерфейса на Android нет.
-dontwarn javax.script.**
# jdk.dynalink (18 классов): связывание вызовов скомпилированного в байт-код JS (Bootstrapper); режим интерпретатора
# его не грузит.
-dontwarn jdk.dynalink.**
