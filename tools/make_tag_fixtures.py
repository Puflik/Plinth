# -*- coding: utf-8 -*-
"""Тестовые файлы с тегами для сканера библиотеки (C2): по секунде тишины на формат.

Системный сканер Android читает теги каждого контейнера своим путём: ID3v2 в
MP3, Vorbis comment во FLAC, атомы iTunes в M4A. Инструментальные тесты
кладут эти файлы в Music/PlinthTest/ через MediaStore и проверяют, что
сканер их нашёл и прочитал. Последний файл — без тегов: название должно
прийти из имени файла.

Ожидаемые значения записаны и в тестах (androidTest/.../library/scan/TagFixtures.kt);
менять их нужно вместе.

Запуск из корня репозитория, нужен ffmpeg с libmp3lame в PATH:

    python tools/make_tag_fixtures.py
"""
import os
import subprocess

OUT = os.path.join('app', 'src', 'androidTest', 'assets', 'tags')

ALBUM = {'album': 'Fixtures', 'album_artist': 'Plinth Various'}

# Имя файла, кодек, теги. track и disc ffmpeg переводит в поля контейнера:
# TRCK/TPOS, TRACKNUMBER/DISCNUMBER, trkn/disk.
FIXTURES = [
    ('plinth-mp3.mp3', ['-c:a', 'libmp3lame', '-b:a', '32k', '-id3v2_version', '3'],
     {'title': 'Тишина', 'artist': 'Plinth', 'track': '3', 'disc': '2', **ALBUM}),
    ('plinth-flac.flac', ['-c:a', 'flac'],
     {'title': 'FLAC Silence', 'artist': 'Plinth', 'track': '1', 'disc': '1', **ALBUM}),
    ('plinth-m4a.m4a', ['-c:a', 'aac', '-b:a', '32k'],
     {'title': 'M4A Silence', 'artist': 'The Plinth', 'track': '2', 'disc': '1', **ALBUM}),
    ('plinth-untagged.mp3', ['-c:a', 'libmp3lame', '-b:a', '32k'], {}),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, codec, tags in FIXTURES:
        command = ['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error',
                   '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=mono', '-t', '1',
                   # Без тегов источника и без подписи кодировщика: файл
                   # собирается одинаково на любой машине.
                   '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact',
                   *codec]
        for key, value in tags.items():
            command += ['-metadata', f'{key}={value}']
        path = os.path.join(OUT, name)
        subprocess.run(command + [path], check=True)
        print(f'{path}: {os.path.getsize(path)} байт')


if __name__ == '__main__':
    main()
