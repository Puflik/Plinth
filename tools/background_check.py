# -*- coding: utf-8 -*-
"""Проверка фоновой игры (H2): звук не останавливается с выключенным экраном.

Проверяется установленное приложение, а не тест под инструментацией: процесс
под тестовым раннером система не трогает, а обычный — может заморозить или
убить. Работает с эмулятором и с телефоном по adb.

Перед запуском в приложении должна быть очередь с повтором (всей очереди или
одного трека), чтобы игра не кончилась сама. Скрипт:

1. включает звук медиакнопкой (KEYCODE_MEDIA_PLAY) и ждёт PLAYING;
2. уводит приложение в фон и гасит экран;
3. без --no-doze изображает телефон в кармане: «отключает зарядку»
   (dumpsys battery unplug), вводит устройство в глубокий Doze
   (dumpsys deviceidle force-idle) и приложение — в неактивные
   (am set-inactive);
4. раз в --interval секунд проверяет четыре признака: процесс тот же и жив,
   медиасессия в PLAYING, у приложения есть AudioTrack в состоянии started,
   служба воспроизведения — foreground; неудачную проверку повторяет — между
   треками звук на миг прерывается;
5. возвращает устройство как было, ставит звук на паузу и печатает PASS или
   FAIL с первой проблемой.

Два часа на живом телефоне (план 25, H2) честнее без зарядки: подключите его
по беспроводной отладке (Android 11+) и запускайте с --no-doze — пусть
засыпает сам.

Запуск из корня репозитория:

    python tools/background_check.py --minutes 10
    python tools/background_check.py --minutes 120 --no-doze --serial 192.168.1.5:5555
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time

PACKAGES = ('io.github.puflik.plinth', 'io.github.puflik.plinth.debug')
# Дамп пишет службу полным именем (отладка: пакет .debug) или от пакета
# приложения (релиз: «/.audio.media3.PlaybackService») — хвост общий.
SERVICE = 'audio.media3.PlaybackService'
PLAYING = 3
START_TIMEOUT_S = 10
RECHECKS = 3
RECHECK_PAUSE_S = 2


def find_adb():
    found = shutil.which('adb')
    if found:
        return found
    for root in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
                 os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')):
        if root:
            for name in ('adb.exe', 'adb'):
                path = os.path.join(root, 'platform-tools', name)
                if os.path.isfile(path):
                    return path
    sys.exit('adb не найден: добавьте platform-tools в PATH или задайте ANDROID_HOME')


class Device:
    def __init__(self, serial):
        self.base = [find_adb()] + (['-s', serial] if serial else [])

    def shell(self, *args):
        result = subprocess.run(self.base + ['shell', *args], capture_output=True,
                                text=True, encoding='utf-8', errors='replace')
        return result.stdout

    def key(self, name):
        self.shell('input', 'keyevent', name)


def session_state(dump, package):
    """Состояние медиасессии приложения из `dumpsys media_session`; None — сессии нет."""
    lines = dump.splitlines()
    for index, line in enumerate(lines):
        if line.strip() == 'package=' + package:
            for following in lines[index + 1:]:
                if following.strip().startswith('package='):
                    break
                match = re.search(r'state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)', following)
                if match:
                    return int(match.group(1))
    return None


def audio_started(dump, uid, pid):
    """Есть ли у процесса AudioTrack в состоянии started (`dumpsys audio`, раздел players)."""
    marker = 'u/pid:%s/%s ' % (uid, pid)
    return any(line.lstrip().startswith('AudioPlaybackConfiguration') and marker in line
               and 'state:started' in line for line in dump.splitlines())


def service_foreground(dump):
    """Служба воспроизведения в foreground (`dumpsys activity services <package>`)."""
    return SERVICE in dump and 'isForeground=true' in dump


def installed_uids(device):
    """Установленные пакеты Plinth и их uid; фильтр `pm list` — по подстроке, поэтому сверка по имени."""
    uids = {}
    for line in device.shell('pm', 'list', 'packages', '-U').splitlines():
        match = re.match(r'package:(\S+) uid:(\d+)', line.strip())
        if match and match.group(1) in PACKAGES:
            uids[match.group(1)] = match.group(2)
    return uids


def pick_package(present, wanted):
    if wanted:
        if wanted not in present:
            sys.exit('Пакет %s не установлен' % wanted)
        return wanted
    if len(present) != 1:
        sys.exit('Установлено %s — укажите --package' % (', '.join(present) or 'ни одного Plinth'))
    return present[0]


def lasting_problems(device, package, uid, pid):
    """
    Что не так и не проходит само. Между треками сессия на миг в STOPPED, а
    AudioTrack создаётся заново (плеер держит один трек) — одна неудачная
    проверка ещё не остановка, проверяем ещё несколько раз.
    """
    found = problems(device, package, uid, pid)
    for _ in range(RECHECKS):
        if not found:
            break
        time.sleep(RECHECK_PAUSE_S)
        found = problems(device, package, uid, pid)
    return found


def problems(device, package, uid, pid):
    """Что не так прямо сейчас; пустой список — звук идёт."""
    found = []
    now_pid = device.shell('pidof', package).strip()
    if now_pid != pid:
        found.append('процесс %s' % ('умер' if not now_pid else 'перезапустился (pid %s → %s)' % (pid, now_pid)))
        return found
    state = session_state(device.shell('dumpsys', 'media_session'), package)
    if state != PLAYING:
        found.append('медиасессия не играет (state=%s)' % state)
    if not audio_started(device.shell('dumpsys', 'audio'), uid, pid):
        found.append('нет AudioTrack в состоянии started')
    if not service_foreground(device.shell('dumpsys', 'activity', 'services', package)):
        found.append('служба воспроизведения не foreground')
    return found


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--minutes', type=float, default=10, help='сколько играть в фоне (по умолчанию 10)')
    parser.add_argument('--interval', type=int, default=60, help='проверка раз в N секунд (по умолчанию 60)')
    parser.add_argument('--package', help='пакет, если установлены и релиз, и отладка')
    parser.add_argument('--serial', help='устройство, если их несколько (adb -s)')
    parser.add_argument('--no-doze', action='store_true', help='не вводить Doze принудительно')
    args = parser.parse_args()

    device = Device(args.serial)
    uids = installed_uids(device)
    package = pick_package(sorted(uids), args.package)
    uid = uids[package]
    if not device.shell('pidof', package).strip():
        device.shell('monkey', '-p', package, '-c', 'android.intent.category.LAUNCHER', '1')
        time.sleep(START_TIMEOUT_S / 2)

    device.key('KEYCODE_MEDIA_PLAY')
    deadline = time.time() + START_TIMEOUT_S
    while session_state(device.shell('dumpsys', 'media_session'), package) != PLAYING:
        if time.time() > deadline:
            sys.exit('FAIL: звук не пошёл — в очереди есть что играть?')
        time.sleep(1)
    pid = device.shell('pidof', package).strip()
    print('%s (uid %s, pid %s) играет; в фоне на %g мин.' % (package, uid, pid, args.minutes))

    device.key('KEYCODE_HOME')
    device.key('KEYCODE_SLEEP')
    if not args.no_doze:
        device.shell('dumpsys', 'battery', 'unplug')
        print('Doze:', device.shell('dumpsys', 'deviceidle', 'force-idle').strip())
        device.shell('am', 'set-inactive', package, 'true')

    failure = None
    started = time.time()
    try:
        while time.time() - started < args.minutes * 60:
            time.sleep(min(args.interval, max(1, args.minutes * 60 - (time.time() - started))))
            found = lasting_problems(device, package, uid, pid)
            elapsed = (time.time() - started) / 60
            print('%6.1f мин: %s' % (elapsed, '; '.join(found) or 'играет'))
            if found:
                failure = '%.1f мин: %s' % (elapsed, '; '.join(found))
                break
    finally:
        if not args.no_doze:
            device.shell('dumpsys', 'deviceidle', 'unforce')
            device.shell('dumpsys', 'battery', 'reset')
            device.shell('am', 'set-inactive', package, 'false')
        device.key('KEYCODE_WAKEUP')
        device.key('KEYCODE_MEDIA_PAUSE')

    if failure:
        sys.exit('FAIL на ' + failure)
    print('PASS: %g мин в фоне с выключенным экраном%s' % (args.minutes, '' if args.no_doze else ' и в Doze'))


if __name__ == '__main__':
    main()
