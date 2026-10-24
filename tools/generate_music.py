#!/usr/bin/env python3
"""Generate Night Fleet's ambient music: the home theme and one loop per sea (13 tracks).

Each track is synthesized from a fixed seed and a parameter table that follows docs/CONTENT.md
section 1 (sea 1 buoy bells, sea 4 icy pads, sea 10 percussion, sea 11 deep bass, sea 12 full
theme, ...). Synthesis is additive and FM-free: sine-based pads, inharmonic bells and bars,
plucked strings, a flute with vibrato, pulses and drums. Pure standard library, no samples.

The loop is seamless: the track is rendered LENGTH + CROSSFADE seconds long and the last
CROSSFADE seconds are blended into the first ones with an equal-power curve, so the end of the
file continues into its start. Mono, 32 kHz, Ogg Vorbis quality 3 (ffmpeg, libvorbis, no
metadata, bit-exact). Output goes to app/src/main/res/raw/music_<name>.ogg.

    python3 tools/generate_music.py                  # all 13 tracks
    python3 tools/generate_music.py --only music_sea11
    python3 tools/generate_music.py --wav-dir DIR    # keep the intermediate WAV files
"""
import argparse
import math
import os
import random
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from audio_dsp import (TAU, bandpass, encode_ogg, exp_decay, find_ffmpeg,  # noqa: E402
                       highpass, lowpass, mul, noise, normalise, place, ring, tone, to_samples,
                       write_wav)

RATE = 32000
CROSSFADE_S = 4.0
PEAK_DBFS = -6.0
ROOT_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT_DIR, "app", "src", "main", "res", "raw")

SCALES = {
    "major": [0, 2, 4, 5, 7, 9, 11],
    "major_pent": [0, 2, 4, 7, 9],
    "minor_pent": [0, 3, 5, 7, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    "phrygian": [0, 1, 3, 5, 7, 8, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11],
    "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "aeolian": [0, 2, 3, 5, 7, 8, 10],
    "locrian": [0, 1, 3, 5, 6, 8, 10],
}

BELL_PARTIALS = [(1, 1.0, 0.45), (2.0, 0.35, 0.8), (2.4, 0.22, 1.0), (3.0, 0.12, 1.6)]

# Each track: seed, length (s), root (MIDI; 50 = D3), scale, chord roots (scale degrees),
# seconds per chord, and one entry per layer. Layers that are absent are simply not rendered.
TRACKS = [
    dict(name="music_home", seed=101, seconds=66, root=50, scale="major_pent",
         chords=[0, 3, 4, 0], chord_s=11, pad=0.22, drone=0.18,
         bell=dict(per_minute=3, gain=0.5, octave=1)),
    dict(name="music_sea01", seed=201, seconds=72, root=50, scale="major_pent",
         chords=[0, 0, 3, 4], chord_s=14, pad=0.14, drone=0.2,
         bell=dict(per_minute=5, gain=0.6, octave=1)),
    dict(name="music_sea02", seed=302, seconds=70, root=48, scale="dorian",
         chords=[0, 3, 0, 4], chord_s=16, pad=0.18, drone=0.25,
         bell=dict(per_minute=1.5, gain=0.35, octave=1),
         melody=dict(kind="flute", chance=0.25, phrase_s=16, gain=0.12)),
    dict(name="music_sea03", seed=403, seconds=64, root=50, scale="major",
         chords=[0, 4, 5, 3], chord_s=8, pad=0.07, drone=0.12,
         melody=dict(kind="pluck", chance=0.6, phrase_s=8, gain=0.45)),
    dict(name="music_sea04", seed=504, seconds=78, root=50, scale="lydian",
         chords=[0, 1, 3, 0], chord_s=13, pad=0.3, drone=0.1, detune=0.006,
         bell=dict(per_minute=1.5, gain=0.3, octave=2)),
    dict(name="music_sea05", seed=605, seconds=66, root=45, scale="phrygian",
         chords=[0, 0, 1, 0], chord_s=20, pad=0.1, drone=0.3,
         pulse=dict(bpm=56, gain=0.35),
         bell=dict(per_minute=0.5, gain=0.2, octave=1)),
    dict(name="music_sea06", seed=706, seconds=68, root=50, scale="minor_pent",
         chords=[0, 3, 2, 0], chord_s=10, pad=0.08, drone=0.1,
         melody=dict(kind="marimba", chance=0.7, phrase_s=6, gain=0.5)),
    dict(name="music_sea07", seed=807, seconds=74, root=45, scale="aeolian",
         chords=[0, 5, 3, 4], chord_s=12, pad=0.1, drone=0.2,
         distant_drums=dict(per_minute=16, gain=0.22),
         bell=dict(per_minute=0.8, gain=0.2, octave=1)),
    dict(name="music_sea08", seed=908, seconds=70, root=50, scale="dorian",
         chords=[0, 3, 0, 6], chord_s=12, pad=0.12, drone=0.12,
         melody=dict(kind="flute", chance=0.7, phrase_s=9, gain=0.25)),
    dict(name="music_sea09", seed=1009, seconds=74, root=38, scale="phrygian",
         chords=[0, 1, 0, 0], chord_s=18, pad=0.16, drone=0.45, detune=0.004,
         bell=dict(per_minute=0.5, gain=0.3, octave=0)),
    dict(name="music_sea10", seed=1110, seconds=64, root=50, scale="aeolian",
         chords=[0, 5, 6, 4], chord_s=8, pad=0.08, drone=0.1,
         drums=dict(bpm=96, kick=0.5, snare=0.2, shaker=0.12),
         thunder=dict(per_minute=2, gain=0.2)),
    dict(name="music_sea11", seed=1211, seconds=80, root=38, scale="locrian",
         chords=[0, 0, 5, 4], chord_s=20, pad=0.07, drone=0.1, detune=0.01,
         bass=dict(gain=0.7),
         particles=dict(per_minute=18, gain=0.15)),
    dict(name="music_sea12", seed=1312, seconds=86, root=50, scale="mixolydian",
         chords=[0, 4, 3, 5], chord_s=11, pad=0.14, drone=0.12,
         melody=dict(kind="pluck", chance=0.7, phrase_s=8, gain=0.3),
         bell=dict(per_minute=2, gain=0.4, octave=1),
         drums=dict(bpm=84, kick=0.3, snare=0.0, shaker=0.08),
         sparkle=dict(per_minute=4, gain=0.2)),
]


def midi_hz(midi):
    return 440.0 * 2.0 ** ((midi - 69) / 12.0)


def window(samples):
    """Sine-squared window over a whole buffer: neighbours at half-length hops sum to 1."""
    n = len(samples)
    return [v * math.sin(math.pi * i / max(1, n - 1)) ** 2 for i, v in enumerate(samples)]


class Voices:
    """Renders reusable note buffers. Every buffer is cached by its parameters, so repeated
    notes cost one synthesis and a slice-add each time they are placed."""

    def __init__(self, rng):
        self.rng = rng
        self.cache = {}

    def cached(self, key, make):
        if key not in self.cache:
            self.cache[key] = make()
        return self.cache[key]

    def bell(self, midi):
        return self.cached(("bell", midi), lambda: ring(midi_hz(midi), 6.0, RATE, BELL_PARTIALS, 0.004))

    def pluck(self, midi):
        return self.cached(("pluck", midi), lambda: ring(
            midi_hz(midi), 2.5, RATE, [(1, 1.0, 2.6), (2, 0.4, 4.5), (3, 0.2, 7.5)], 0.002))

    def marimba(self, midi):
        return self.cached(("marimba", midi), lambda: ring(
            midi_hz(midi), 1.6, RATE, [(1, 1.0, 7.0), (4, 0.25, 35.0), (10, 0.06, 80.0)], 0.001))

    def flute(self, midi, seconds):
        def make():
            n = to_samples(seconds, RATE)
            base = midi_hz(midi)
            breath = lowpass(noise(self.rng, seconds, RATE), 2200, RATE)
            out = [0.0] * n
            phase = 0.0
            for i in range(n):
                t = i / RATE
                vibrato = 1.0 + 0.005 * math.sin(TAU * 5.2 * t) * min(1.0, t / 0.6)
                phase += TAU * base * vibrato / RATE
                out[i] = math.sin(phase) + 0.25 * math.sin(2 * phase) + 0.04 * breath[i]
            attack = to_samples(0.12, RATE)
            release = to_samples(0.35, RATE)
            for i in range(n):
                level = min(1.0, i / attack) * min(1.0, (n - i) / release)
                out[i] *= 0.35 * level
            return out
        return self.cached(("flute", midi, seconds), make)

    def chord(self, midis, seconds, detune):
        """Pad: detuned sine pairs with a soft octave, shaped by a sine-squared window."""
        def make():
            n = to_samples(seconds, RATE)
            out = [0.0] * n
            for midi in midis:
                f = midi_hz(midi)
                a = TAU * f / RATE
                b = TAU * f * (1.0 + detune) / RATE
                c = TAU * 2.0 * f / RATE
                for i in range(n):
                    out[i] += 0.5 * math.sin(a * i) + 0.35 * math.sin(b * i) + 0.12 * math.sin(c * i)
            return window(out)
        return self.cached(("chord", tuple(midis), seconds, detune), make)

    def kick(self, low=95.0):
        return self.cached(("kick", low), lambda: mul(
            tone(low, 0.5, RATE, glide_to=low * 0.4), exp_decay(to_samples(0.5, RATE), RATE, 0.12, 0.001)))

    def snare(self):
        def make():
            n = to_samples(0.3, RATE)
            body = mul(bandpass(noise(self.rng, 0.3, RATE), 1500, 4500, RATE), exp_decay(n, RATE, 0.06, 0.001))
            return body
        return self.cached(("snare",), make)

    def shaker(self):
        def make():
            n = to_samples(0.1, RATE)
            return mul(highpass(noise(self.rng, 0.1, RATE), 6000, RATE), exp_decay(n, RATE, 0.02, 0.001))
        return self.cached(("shaker",), make)

    def heartbeat(self):
        def make():
            out = [0.0] * to_samples(0.7, RATE)
            thump = mul(tone(52, 0.35, RATE, glide_to=40), exp_decay(to_samples(0.35, RATE), RATE, 0.1, 0.001))
            place(out, thump, 0.0, RATE, 1.0)
            place(out, thump, 0.22, RATE, 0.6)
            return out
        return self.cached(("heartbeat",), make)

    def thunder(self, seconds=6.0):
        n = to_samples(seconds, RATE)
        return window(lowpass(noise(self.rng, seconds, RATE), 180, RATE))[:n]

    def particle(self):
        n = to_samples(0.4, RATE)
        return mul(bandpass(noise(self.rng, 0.4, RATE), 1200, 3000, RATE), exp_decay(n, RATE, 0.1, 0.01))

    def sparkle(self, midi):
        return self.cached(("sparkle", midi), lambda: ring(
            midi_hz(midi), 2.0, RATE, [(1, 1.0, 4.0), (2.0, 0.3, 6.0)], 0.002))

    def bass(self, midi, seconds):
        def make():
            n = to_samples(seconds, RATE)
            f = midi_hz(midi)
            body = [0.0] * n
            a = TAU * f / RATE
            for i in range(n):
                body[i] = math.sin(a * i) + 0.2 * math.sin(2.0 * a * i)
            return window(body)
        return self.cached(("bass", midi, seconds), make)


def poisson_times(rng, per_minute, seconds):
    times = []
    t = rng.expovariate(per_minute / 60.0)
    while t < seconds:
        times.append(t)
        t += rng.expovariate(per_minute / 60.0)
    return times


def degree_midi(track, degree, octave=0):
    scale = SCALES[track["scale"]]
    up, step = divmod(degree, len(scale))
    return track["root"] + 12 * (up + octave) + scale[step]


def make_motif(rng, length=4):
    degree = rng.choice([0, 2, 4])
    notes = []
    for _ in range(length):
        degree += rng.choice([-2, -1, 1, 2, 3])
        notes.append((degree, rng.choice([0.5, 1.0, 1.5, 2.0])))
    return notes


def render_track(track):
    rng = random.Random(track["seed"])
    seconds = track["seconds"]
    total = seconds + CROSSFADE_S
    n_total = to_samples(total, RATE)
    buf = [0.0] * n_total
    voices = Voices(rng)

    # Harmony: overlapping windows of two chord lengths, cycling through the progression.
    chord_s = track["chord_s"]
    index = 0
    start = 0.0
    while start < total:
        degrees = track["chords"][index % len(track["chords"])]
        midis = [degree_midi(track, degrees + k, octave=1) for k in (0, 2, 4)]
        place(buf, voices.chord(midis, 2 * chord_s, track.get("detune", 0.004)), start, RATE, track["pad"])
        index += 1
        start += chord_s

    if track.get("drone"):
        drone = [degree_midi(track, 0, octave=-1), degree_midi(track, 0, octave=-1) + 7]
        window_s = 2 * max(12.0, chord_s)
        start = 0.0
        while start < total:
            place(buf, voices.chord(drone, window_s, track.get("detune", 0.002)), start, RATE, track["drone"])
            start += window_s / 2

    if track.get("bass"):
        bass_window = 14.0
        start = 0.0
        while start < total:
            place(buf, voices.bass(degree_midi(track, 0, octave=-1), bass_window), start, RATE, track["bass"]["gain"])
            start += bass_window / 2

    if track.get("bell"):
        bell = track["bell"]
        for t in poisson_times(rng, bell["per_minute"], total):
            degree = rng.randrange(0, 2 * len(SCALES[track["scale"]]))
            midi = degree_midi(track, degree, octave=bell["octave"])
            place(buf, voices.bell(midi), t, RATE, bell["gain"])

    if track.get("sparkle"):
        for t in poisson_times(rng, track["sparkle"]["per_minute"], total):
            midi = degree_midi(track, rng.randrange(4, 9), octave=2)
            place(buf, voices.sparkle(midi), t, RATE, track["sparkle"]["gain"])

    if track.get("pulse"):
        beat = 60.0 / track["pulse"]["bpm"]
        t = 0.0
        while t < total:
            place(buf, voices.heartbeat(), t, RATE, track["pulse"]["gain"])
            t += beat

    if track.get("drums"):
        drums = track["drums"]
        step_s = 60.0 / drums["bpm"] / 4.0
        kick_steps = {0, 6, 10}
        snare_steps = {4, 12}
        k = 0
        while k * step_s < total:
            t = k * step_s
            bar_step = k % 16
            if bar_step in kick_steps and rng.random() < 0.85:
                place(buf, voices.kick(), t, RATE, drums["kick"])
            if bar_step in snare_steps and drums["snare"]:
                place(buf, voices.snare(), t, RATE, drums["snare"])
            if k % 2 == 0 and drums["shaker"]:
                place(buf, voices.shaker(), t, RATE, drums["shaker"])
            k += 1

    if track.get("distant_drums"):
        for t in poisson_times(rng, track["distant_drums"]["per_minute"], total):
            place(buf, voices.kick(low=70.0), t, RATE, track["distant_drums"]["gain"])

    if track.get("thunder"):
        for t in poisson_times(rng, track["thunder"]["per_minute"], total):
            place(buf, voices.thunder(), t, RATE, track["thunder"]["gain"])

    if track.get("particles"):
        for t in poisson_times(rng, track["particles"]["per_minute"], total):
            place(buf, voices.particle(), t, RATE, track["particles"]["gain"])

    melody = track.get("melody")
    if melody:
        motif = make_motif(rng)
        beat = 60.0 / 72.0
        phrase = melody["phrase_s"]
        t = 0.0
        while t < total:
            if rng.random() < melody["chance"]:
                transpose = rng.choice([0, 0, -2, 1])
                cursor = t + rng.uniform(0.0, phrase * 0.3)
                for degree, beats in motif:
                    midi = degree_midi(track, degree + transpose, octave=1)
                    length = beats * beat
                    if melody["kind"] == "pluck":
                        note = voices.pluck(midi)
                    elif melody["kind"] == "marimba":
                        note = voices.marimba(midi)
                    else:
                        note = voices.flute(midi, length + 0.5)
                    place(buf, note, cursor, RATE, melody["gain"])
                    cursor += length
                motif = make_motif(rng) if rng.random() < 0.25 else motif
            t += phrase

    return finish_loop(buf, seconds)


def finish_loop(buf, seconds):
    """Keep the first `seconds` and blend the extra tail into the head for a seamless loop."""
    length = to_samples(seconds, RATE)
    fade = to_samples(CROSSFADE_S, RATE)
    out = buf[:length]
    for t in range(fade):
        head = math.sin(0.5 * math.pi * t / fade)
        tail = math.cos(0.5 * math.pi * t / fade)
        out[t] = buf[t] * head + buf[length + t] * tail
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out-dir", default=RAW_DIR, help="where the .ogg files go")
    parser.add_argument("--wav-dir", help="keep the intermediate WAV files here")
    parser.add_argument("--only", help="render just this track name, e.g. music_sea11")
    args = parser.parse_args()

    ffmpeg = find_ffmpeg()
    os.makedirs(args.out_dir, exist_ok=True)
    tracks = [t for t in TRACKS if args.only in (None, t["name"])]
    if not tracks:
        raise SystemExit("unknown track: %s" % args.only)
    with tempfile.TemporaryDirectory() as temp_dir:
        wav_dir = args.wav_dir or temp_dir
        os.makedirs(wav_dir, exist_ok=True)
        for track in tracks:
            samples = render_track(track)
            samples = normalise(samples, PEAK_DBFS)
            wav_path = os.path.join(wav_dir, track["name"] + ".wav")
            write_wav(wav_path, samples, RATE)
            encode_ogg(ffmpeg, wav_path, os.path.join(args.out_dir, track["name"] + ".ogg"))
            print("%s.ogg  %.1f s" % (track["name"], len(samples) / RATE))


if __name__ == "__main__":
    main()
