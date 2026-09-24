# -*- coding: utf-8 -*-
"""Тестовые файлы семи форматов для прогона воспроизведения (B2.4).

По секунде тишины на формат: MP3, AAC (M4A), FLAC, ALAC (M4A), Ogg Vorbis,
Opus, WAV. FormatSupportTest (androidTest/.../audio/media3) проигрывает
каждый через Media3Engine до конца и сверяет длительность. Тишина — чтобы
прогон на эмуляторе со звуком не пищал в динамики.

Запуск из корня репозитория, нужен ffmpeg с libmp3lame, libvorbis и libopus в PATH:

    python tools/make_format_fixtures.py
"""
import os
import subprocess

OUT = os.path.join('app', 'src', 'androidTest', 'assets', 'formats')

# Имя файла, частота, кодек и его параметры. Opus работает на 48 кГц.
FIXTURES = [
    ('silence.mp3', 44100, ['-c:a', 'libmp3lame', '-b:a', '32k']),
    ('silence-aac.m4a', 44100, ['-c:a', 'aac', '-b:a', '32k']),
    ('silence.flac', 44100, ['-c:a', 'flac']),
    ('silence-alac.m4a', 44100, ['-c:a', 'alac']),
    ('silence.ogg', 44100, ['-c:a', 'libvorbis', '-q:a', '0']),
    ('silence.opus', 48000, ['-c:a', 'libopus', '-b:a', '16k']),
    ('silence.wav', 44100, ['-c:a', 'pcm_s16le']),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, rate, codec in FIXTURES:
        path = os.path.join(OUT, name)
        subprocess.run(
            ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
             '-f', 'lavfi', '-i', 'anullsrc=channel_layout=mono:sample_rate=%d' % rate,
             '-t', '1', '-map_metadata', '-1', '-fflags', '+bitexact', '-flags:a', '+bitexact',
             *codec, path],
            check=True)
        print('%-18s %6d байт' % (name, os.path.getsize(path)))


if __name__ == '__main__':
    main()
