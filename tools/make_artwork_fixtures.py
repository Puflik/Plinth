# -*- coding: utf-8 -*-
"""Тестовый файл со встроенной обложкой (E2): секунда тишины в MP3 и картинка в APIC.

Обложка — сплошной красный квадрат 1400×1400 в JPEG, больше миниатюры,
которую отдаёт система (около половины экрана). Инструментальный тест
(androidTest/.../artwork/embedded/EmbeddedArtworkSourceTest.kt) проверяет, что
картинка найдена, уменьшена и осталась красной, а большая картинка для плеера
прочитана из тегов, а не взята у системы. Файл без обложки тест берёт
из фикстур тегов (tags/plinth-untagged.mp3).

Запуск из корня репозитория, нужен ffmpeg с libmp3lame в PATH:

    python tools/make_artwork_fixtures.py
"""
import os
import subprocess

OUT = os.path.join('app', 'src', 'androidTest', 'assets', 'artwork')

COVER_SIZE = 1400


def main():
    os.makedirs(OUT, exist_ok=True)
    cover = os.path.join(OUT, 'cover.jpg')
    path = os.path.join(OUT, 'plinth-cover.mp3')
    quiet = ['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error']
    subprocess.run(quiet + ['-f', 'lavfi', '-i', f'color=c=red:s={COVER_SIZE}x{COVER_SIZE}',
                            '-frames:v', '1', '-fflags', '+bitexact', '-flags:v', '+bitexact', cover],
                   check=True)
    try:
        subprocess.run(quiet + ['-f', 'lavfi', '-t', '1', '-i', 'anullsrc=r=44100:cl=mono', '-i', cover,
                                '-map', '0:a', '-map', '1:v',
                                # Без подписи кодировщика: файл собирается одинаково на любой машине.
                                '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact',
                                '-c:a', 'libmp3lame', '-b:a', '32k',
                                '-c:v', 'copy', '-disposition:v', 'attached_pic',
                                '-metadata:s:v', 'comment=Cover (front)',
                                '-id3v2_version', '3',
                                '-metadata', 'title=Cover Silence', '-metadata', 'album=Covers',
                                path],
                       check=True)
    finally:
        os.remove(cover)
    print(f'{path}: {os.path.getsize(path)} байт')


if __name__ == '__main__':
    main()
