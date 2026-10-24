#!/usr/bin/env python3
"""Synthesize Night Fleet's sound effects offline and encode them to Ogg Vorbis.

Every effect is generated from fixed seeds with the Python standard library (no samples, no
network). Each one is written as a 44.1 kHz mono 16-bit WAV in a temporary directory, peak
normalised to -3 dBFS, then encoded with ffmpeg (libvorbis, quality 3) into
app/src/main/res/raw/sfx_<name>.ogg. Rerunning the script gives identical bytes.

    python3 tools/generate_sounds.py                 # write res/raw/sfx_*.ogg
    python3 tools/generate_sounds.py --wav-dir DIR   # also keep the intermediate WAV files

Relative loudness per effect (ui_tap, clock_tick, ...) is applied in the app, not here, so
every file keeps the same -3 dBFS peak as the rest of the set.
"""
import argparse
import math
import os
import random
import sys
import tempfile
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from audio_dsp import (TAU, bandpass, encode_ogg, exp_decay, fade_edges, find_ffmpeg,  # noqa: E402
                       highpass, lowpass, lowpass_sweep, mul, noise, normalise, place, ring,
                       tone, to_samples, write_wav)

RATE = 44100
PEAK_DBFS = -3.0
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, "app", "src", "main", "res", "raw")

SOUNDS = {}  # name -> (builder, maximum length in seconds)


def sound(name, max_seconds=2.5):
    def register(builder):
        SOUNDS[name] = (builder, max_seconds)
        return builder
    return register


def m(seconds):
    return to_samples(seconds, RATE)


@sound("splash")
def splash(rng):
    """Miss: a short hiss of water with three small plops."""
    out = [0.0] * m(0.9)
    hiss = mul(bandpass(noise(rng, 0.9, RATE), 900, 5000, RATE), exp_decay(m(0.9), RATE, 0.12, 0.004))
    place(out, hiss, 0.0, RATE, 0.8)
    for start in (0.04, 0.17, 0.31):
        frequency = 700 + rng.uniform(-80, 80)
        drop = tone(frequency, 0.07, RATE, glide_to=frequency * 0.5)
        place(out, mul(drop, exp_decay(m(0.07), RATE, 0.02, 0.002)), start, RATE, 0.6)
    return out


@sound("hit")
def hit(rng):
    """Impact on a ship: a low thump, a crack of wood and a short metallic ring."""
    out = [0.0] * m(0.6)
    thump = tone(140, 0.3, RATE, glide_to=45)
    place(out, mul(thump, exp_decay(m(0.3), RATE, 0.10, 0.001)), 0.0, RATE, 0.9)
    crack = bandpass(noise(rng, 0.05, RATE), 1500, 7000, RATE)
    place(out, mul(crack, exp_decay(m(0.05), RATE, 0.02, 0.001)), 0.0, RATE, 0.7)
    clang = ring(310, 0.6, RATE, [(1, 0.5, 9), (2.76, 0.3, 12), (5.4, 0.15, 18)], 0.001)
    place(out, clang, 0.0, RATE, 0.25)
    return out


@sound("sink", 2.5)
def sink(rng):
    """A ship going down: a long deep rumble, a low wash and bubbles rising to the surface."""
    out = [0.0] * m(2.4)
    rumble = tone(60, 2.3, RATE, glide_to=30)
    place(out, mul(rumble, exp_decay(m(2.3), RATE, 1.1, 0.06)), 0.0, RATE, 0.9)
    wash = mul(lowpass(noise(rng, 2.0, RATE), 380, RATE), exp_decay(m(2.0), RATE, 0.9, 0.25))
    place(out, wash, 0.05, RATE, 0.7)
    for _ in range(16):
        start = rng.uniform(0.35, 2.2)
        frequency = rng.uniform(260, 720)
        bubble = tone(frequency, 0.09, RATE, glide_to=frequency * 1.7)
        place(out, mul(bubble, exp_decay(m(0.09), RATE, 0.03, 0.001)), start, RATE, 0.25)
    return out


@sound("sonar_ping")
def sonar_ping(rng):
    """Sonar pulse with two fading echoes."""
    out = [0.0] * m(1.8)
    for start, gain, frequency in ((0.0, 1.0, 1150.0), (0.55, 0.45, 1080.0), (1.05, 0.2, 1020.0)):
        place(out, ring(frequency, 1.2, RATE, [(1, 1.0, 3.0), (2, 0.2, 5.0)], 0.002), start, RATE, gain)
    return out


@sound("torpedo_run")
def torpedo_run(rng):
    """A torpedo running under the water: hiss, pulsed propeller and a low gurgle."""
    seconds = 2.4
    n = m(seconds)
    hiss = bandpass(noise(rng, seconds, RATE), 350, 2600, RATE)
    gurgle = lowpass(noise(rng, seconds, RATE), 180, RATE)
    prop = tone(190, seconds, RATE, glide_to=230)
    out = [0.0] * n
    for i in range(n):
        t = i / RATE
        swell = (0.3 + 0.7 * t / seconds) * min(1.0, (seconds - t) / 0.4)
        pulse = 0.5 + 0.5 * math.sin(TAU * 9.0 * t)
        out[i] = swell * (0.7 * hiss[i] + 0.35 * gurgle[i] * pulse + 0.15 * prop[i] * (0.5 + 0.5 * math.sin(TAU * 18.0 * t)))
    return out


@sound("recon_plane")
def recon_plane(rng):
    """A reconnaissance plane passing overhead: propeller blade pass and a falling engine tone."""
    seconds = 2.3
    n = m(seconds)
    blades = bandpass(noise(rng, seconds, RATE), 500, 2500, RATE)
    engine = tone(160, seconds, RATE, glide_to=120)
    engine_high = tone(320, seconds, RATE, glide_to=240)
    out = [0.0] * n
    for i in range(n):
        t = i / RATE
        passing = 0.2 + 0.8 * math.exp(-((t - 1.15) / 0.6) ** 2)
        blade_rate = 0.55 + 0.45 * math.sin(TAU * 22.0 * t)
        out[i] = passing * (0.8 * blades[i] * blade_rate + 0.35 * engine[i] * blade_rate + 0.2 * engine_high[i])
    return out


@sound("cross_barrage")
def cross_barrage(rng):
    """Five quick impacts in a cross, each a little quieter than the last."""
    out = [0.0] * m(1.0)
    for k, start in enumerate((0.0, 0.14, 0.28, 0.41, 0.55)):
        gain = 1.0 - 0.1 * k
        pitch = 110 * rng.uniform(0.92, 1.08)
        thump = mul(tone(pitch, 0.2, RATE, glide_to=pitch * 0.5), exp_decay(m(0.2), RATE, 0.07, 0.001))
        crack = mul(bandpass(noise(rng, 0.06, RATE), 300, 2600, RATE), exp_decay(m(0.06), RATE, 0.02, 0.001))
        place(out, thump, start, RATE, 0.8 * gain)
        place(out, crack, start, RATE, 0.6 * gain)
    return out


@sound("mine")
def mine(rng):
    """A mine going off: a sharp metallic clang and a low boom."""
    out = [0.0] * m(1.4)
    clang = ring(520, 1.4, RATE, [(1, 0.6, 6), (2.76, 0.4, 10), (5.4, 0.25, 18)], 0.001)
    place(out, clang, 0.0, RATE, 0.5)
    boom = mul(tone(70, 0.6, RATE, glide_to=35), exp_decay(m(0.6), RATE, 0.25, 0.001))
    place(out, boom, 0.0, RATE, 0.9)
    rumble = mul(lowpass(noise(rng, 0.6, RATE), 260, RATE), exp_decay(m(0.6), RATE, 0.3, 0.002))
    place(out, rumble, 0.0, RATE, 0.5)
    return out


@sound("decoy_revealed", 2.0)
def decoy_revealed(rng):
    """A decoy is exposed: a rising chime arpeggio."""
    out = [0.0] * m(1.0)
    for k, (start, frequency) in enumerate(((0.0, 1046.5), (0.09, 1318.5), (0.18, 1568.0), (0.27, 2093.0))):
        place(out, ring(frequency, 0.7, RATE, [(1, 1.0, 4.0), (2, 0.4, 6.0), (3, 0.15, 8.0)], 0.002),
              start, RATE, 0.6 * 0.9 ** k)
    return out


@sound("repair")
def repair(rng):
    """Repair: two metallic clanks and a few sparks."""
    out = [0.0] * m(1.0)
    place(out, ring(380, 0.5, RATE, [(1, 1.0, 12), (2.32, 0.6, 16), (4.25, 0.3, 22)], 0.001), 0.0, RATE, 0.8)
    place(out, ring(520, 0.5, RATE, [(1, 1.0, 12), (2.32, 0.6, 16), (4.25, 0.3, 22)], 0.001), 0.2, RATE, 0.7)
    for start in (0.42, 0.5, 0.6, 0.7):
        spark = bandpass(noise(rng, 0.03, RATE), 4000, 9000, RATE)
        place(out, mul(spark, exp_decay(m(0.03), RATE, 0.008, 0.001)), start, RATE, 0.4)
    return out


@sound("ship_move")
def ship_move(rng):
    """A ship moving through water: a swoosh whose brightness rises and falls."""
    seconds = 1.4
    n = m(seconds)
    x = noise(rng, seconds, RATE)
    shaped = lowpass_sweep(x, lambda i: 300.0 + 1900.0 * math.sin(math.pi * i / n), RATE)
    shaped = highpass(shaped, 150, RATE)
    return [v * math.sin(math.pi * i / n) ** 2 for i, v in enumerate(shaped)]


@sound("clock_tick", 0.5)
def clock_tick(rng):
    """Wood-block tick for the last seconds of a move clock."""
    out = [0.0] * m(0.12)
    place(out, ring(1750, 0.12, RATE, [(1, 1.0, 45), (2.0, 0.35, 70)], 0.0005), 0.0, RATE, 0.8)
    click = bandpass(noise(rng, 0.01, RATE), 2000, 6000, RATE)
    place(out, click, 0.0, RATE, 0.3)
    return out


@sound("star", 1.5)
def star(rng):
    """A bright bell for a star earned: two notes of a major chord."""
    out = [0.0] * m(0.9)
    place(out, ring(1320, 0.9, RATE, [(1, 1.0, 3.5), (2, 0.5, 6.0), (2.76, 0.35, 9.0), (5.4, 0.2, 12.0)], 0.002), 0.0, RATE, 0.8)
    place(out, ring(1976, 0.8, RATE, [(1, 1.0, 3.5), (2, 0.5, 6.0), (2.76, 0.35, 9.0)], 0.002), 0.08, RATE, 0.5)
    return out


def brass(frequency, seconds):
    """Soft brass-like note: six harmonics with a slow vibrato and a short release."""
    n = m(seconds)
    out = [0.0] * n
    phase = [0.0] * 7
    release = m(0.08)
    for i in range(n):
        t = i / RATE
        attack = min(1.0, i / m(0.025))
        tail = min(1.0, (n - i) / release)
        vibrato = 1.0 + (0.004 * math.sin(TAU * 5.5 * t) if t > 0.3 else 0.0)
        level = attack * tail
        value = 0.0
        for k in range(1, 7):
            phase[k] += TAU * frequency * vibrato * k / RATE
            value += math.sin(phase[k]) / (k ** 1.1)
        out[i] = level * value * 0.5
    return out


@sound("victory", 3.5)
def victory(rng):
    """Short fanfare in D mixolydian: a rising figure that closes on a held chord."""
    out = [0.0] * m(3.2)
    phrase = (
        (293.66, 0.0, 0.30), (440.0, 0.30, 0.30), (587.33, 0.60, 0.45),
        (739.99, 1.10, 0.30), (880.0, 1.45, 0.30), (587.33, 1.80, 0.30),
    )
    for frequency, start, length in phrase:
        place(out, brass(frequency, length), start, RATE, 0.6)
    for frequency in (587.33, 880.0, 1174.66):
        place(out, brass(frequency, 1.0), 2.2, RATE, 0.4)
    return out


@sound("defeat", 2.5)
def defeat(rng):
    """Soft, low loss: two falling notes on a warm tone, filtered."""
    out = [0.0] * m(2.2)
    for frequency, start, length in ((220.0, 0.0, 0.8), (164.81, 0.8, 1.2)):
        body = [a + 0.2 * b for a, b in zip(tone(frequency, length, RATE), tone(frequency * 2, length, RATE, shape="triangle"))]
        body = mul(body, exp_decay(len(body), RATE, 0.9, 0.08))
        place(out, lowpass(body, 1100, RATE), start, RATE, 0.9)
    return out


@sound("ui_tap", 0.2)
def ui_tap(rng):
    """Very soft button tap."""
    return ring(1800, 0.06, RATE, [(1, 1.0, 70)], 0.0005)


def build(name, rng):
    builder, max_seconds = SOUNDS[name]
    samples = fade_edges(builder(rng), RATE)
    if len(samples) > m(max_seconds):
        raise SystemExit("%s is %.2f s, over the %.1f s limit" % (name, len(samples) / RATE, max_seconds))
    return normalise(samples, PEAK_DBFS)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out-dir", default=RAW_DIR, help="where the .ogg files go")
    parser.add_argument("--wav-dir", help="keep the intermediate WAV files here")
    args = parser.parse_args()

    ffmpeg = find_ffmpeg()
    os.makedirs(args.out_dir, exist_ok=True)
    with tempfile.TemporaryDirectory() as temp_dir:
        wav_dir = args.wav_dir or temp_dir
        os.makedirs(wav_dir, exist_ok=True)
        for name in SOUNDS:
            # A fixed per-effect seed (CRC32 of the name, not Python's randomised hash()).
            rng = random.Random(zlib.crc32(name.encode("ascii")))
            samples = build(name, rng)
            wav_path = os.path.join(wav_dir, "sfx_%s.wav" % name)
            write_wav(wav_path, samples, RATE)
            encode_ogg(ffmpeg, wav_path, os.path.join(args.out_dir, "sfx_%s.ogg" % name))
            print("sfx_%s.ogg  %.2f s" % (name, len(samples) / RATE))


if __name__ == "__main__":
    main()
