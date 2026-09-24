# Правила R8 для релизной сборки (H5). Библиотеки (Media3, Room, Hilt,
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
