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
