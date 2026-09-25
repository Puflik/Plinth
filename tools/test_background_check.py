"""Разбор `dumpsys` в background_check.py на разных версиях Android.

    python -m unittest tools/test_background_check.py
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import background_check  # noqa: E402


class AudioStartedTest(unittest.TestCase):
    def test_android_9_and_later(self):
        dump = ('  AudioPlaybackConfiguration piid:295 type:android.media.AudioTrack '
                'u/pid:10169/7086 state:started attr:AudioAttributes: usage=USAGE_MEDIA')
        self.assertTrue(background_check.audio_started(dump, '10169', '7086'))

    def test_android_8_0(self):
        # Н8 прогона на старых Android: на 8.0 строка без префикса AudioPlaybackConfiguration.
        dump = ('  ID:311 -- type:android.media.AudioTrack -- u/pid:10079/5903 -- state:started -- '
                'attr:AudioAttributes: usage=1 content=2 flags=0x200 tags= bundle=null')
        self.assertTrue(background_check.audio_started(dump, '10079', '5903'))

    def test_paused_track_is_not_playing(self):
        dump = '  ID:311 -- type:android.media.AudioTrack -- u/pid:10079/5903 -- state:paused -- attr:x'
        self.assertFalse(background_check.audio_started(dump, '10079', '5903'))

    def test_other_process_is_not_ours(self):
        dump = '  ID:311 -- type:android.media.AudioTrack -- u/pid:10079/1111 -- state:started -- attr:x'
        self.assertFalse(background_check.audio_started(dump, '10079', '5903'))


if __name__ == '__main__':
    unittest.main()
