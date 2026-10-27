# Запись ответов YouTube для тестов (Ю3б, Ю4б)

Инструменты планировщика Ю3б: записать живые обмены NewPipeExtractor с YouTube и собрать из них урезанный набор для
`app/src/test/resources/youtube/` (как он устроен — `README.md` там). Нужны после смены версии экстрактора, если его запросы
изменились (тесты тогда падают на «no recorded answer for …»). В сборку приложения не входят.

- `Recorder.java` — запускает настоящий экстрактор на настольной JVM и пишет каждый обмен файлом `<сценарий>/NNN.json`:
  `search <запрос|@файл> songs|albums`, `stream <видео> [кругов]`, `playlist <OLAK5uy_…>`, `mix <видео> [страниц]` (Ю4б: Mix `RD<видео>` —
  первая страница и `getPage` дальше; записываются `sw.js` и по `next` на страницу). Запрос с кириллицей — в UTF-8
  файле (`@путь`): аргументы командной строки Windows её портят (`q-kino-gk.txt` — пример).
- `trim.py <записи> <набор> [mix]` — урезает записи и убирает личное (адрес клиента в ссылках потока, `visitorData`), собирает `routes.tsv`.
  С `mix` — только Mix (`mix/*.json`, `routes-mix.tsv`: его строки кладутся в начало `routes.tsv`, см. `README.md` набора); записи — каталоги
  `mix_<видео>_<страниц>`.
  Имена записей внутри (`stream_4D7u5KF7SP8_2` и т. д.) — каталоги, которые создал `Recorder`.
- `Replay.java <набор> <сценарий>` — проигрывает набор через настоящий экстрактор: проверка, что урезание ничего не сломало
  (`songs:запрос`, `albums:запрос`, `stream:видео`, `playlist:id`, `mix:видео[:страниц]`; `маршрут=файл` подменяет ответ).

Сборка — отдельным проектом вне репозитория (JDK 21, в корне `Plinth` лежит `gradlew`):

```kotlin
// settings.gradle.kts: rootProject.name = "yt-record"
plugins { java; application }
repositories { mavenCentral(); maven("https://jitpack.io") { content { includeGroup("com.github.TeamNewPipe") } } }
dependencies { implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5") }  // версия — как в gradle/libs.versions.toml
application { mainClass.set("Recorder") }
```

`<корень Plinth>\gradlew.bat -p <проект> installDist`, затем `java -cp "<проект>\build\install\yt-record\lib\*" -Dout=rec Recorder …`
(и `Replay`). Исходники — в `src/main/java/` проекта; запись трогает сеть YouTube с вашего адреса — смотрите, что попало в `rec/`, прежде чем что-то коммитить.
