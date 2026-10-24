"""Shared, stdlib-only synthesis helpers for tools/generate_sounds.py and tools/generate_music.py.

Signals are plain Python lists of floats in [-1, 1]. The output stage writes 16-bit mono WAV
(no timestamps, fixed header) and encodes it to Ogg Vorbis with ffmpeg (libvorbis) with all
metadata stripped and bit-exact flags, so the same inputs always give the same bytes.
Nothing here uses the clock or unseeded randomness.
"""
import array
import math
import os
import shutil
import subprocess
import sys
import wave

TAU = 2.0 * math.pi
OGG_ENCODER_ARGS = ["-c:a", "libvorbis", "-q:a", "3"]


def to_samples(seconds, rate):
    return int(round(seconds * rate))


def wave_value(phase, shape):
    if shape == "sine":
        return math.sin(phase)
    if shape == "triangle":
        return 2.0 / math.pi * math.asin(math.sin(phase))
    if shape == "square":
        return 1.0 if math.sin(phase) >= 0.0 else -1.0
    if shape == "saw":
        return 2.0 * ((phase / TAU) % 1.0) - 1.0
    raise ValueError("unknown shape: %s" % shape)


def tone(freq, seconds, rate, shape="sine", glide_to=None):
    """One oscillator; with glide_to the pitch moves exponentially from freq to glide_to."""
    n = to_samples(seconds, rate)
    out = [0.0] * n
    phase = 0.0
    ratio = (glide_to / freq) if glide_to else 1.0
    denominator = max(1, n - 1)
    for i in range(n):
        frequency = freq * ratio ** (i / denominator)
        phase += TAU * frequency / rate
        out[i] = wave_value(phase, shape)
    return out


def noise(rng, seconds, rate):
    return [rng.uniform(-1.0, 1.0) for _ in range(to_samples(seconds, rate))]


def lowpass_sweep(x, cutoff_at, rate):
    """One-pole lowpass whose cutoff is cutoff_at(i) Hz at sample i."""
    out = [0.0] * len(x)
    y = 0.0
    for i, v in enumerate(x):
        a = 1.0 - math.exp(-TAU * cutoff_at(i) / rate)
        y += a * (v - y)
        out[i] = y
    return out


def lowpass(x, cutoff, rate):
    return lowpass_sweep(x, lambda _i: cutoff, rate)


def highpass(x, cutoff, rate):
    low = lowpass(x, cutoff, rate)
    return [v - l for v, l in zip(x, low)]


def bandpass(x, low_cut, high_cut, rate):
    return highpass(lowpass(x, high_cut, rate), low_cut, rate)


def exp_decay(n, rate, decay_s, attack_s=0.002):
    """Linear attack over attack_s, then exponential decay with time constant decay_s."""
    attack = max(1.0, attack_s * rate)
    return [min(1.0, i / attack) * math.exp(-i / (rate * decay_s)) for i in range(n)]


def mul(x, env):
    return [a * b for a, b in zip(x, env)]


def scaled(x, gain):
    return [v * gain for v in x]


def place(dst, src, start_s, rate, gain=1.0):
    """Add src into dst starting at start_s seconds; anything past the end of dst is dropped."""
    offset = to_samples(start_s, rate)
    if offset >= len(dst):
        return
    end = min(len(dst), offset + len(src))
    dst[offset:end] = [d + gain * s for d, s in zip(dst[offset:end], src)]


def ring(freq, seconds, rate, partials, attack_s=0.002):
    """Inharmonic exponentially decaying partials (bells, clanks, marimba bars).

    partials: (frequency ratio, amplitude, decay rate per second) tuples.
    """
    n = to_samples(seconds, rate)
    out = [0.0] * n
    for ratio, amplitude, decay in partials:
        step = TAU * freq * ratio / rate
        for i in range(n):
            out[i] += amplitude * math.exp(-decay * i / rate) * math.sin(step * i)
    attack = max(1.0, attack_s * rate)
    for i in range(min(n, int(attack))):
        out[i] *= i / attack
    return out


def fade_edges(x, rate, fade_in_s=0.003, fade_out_s=0.015):
    """Linear fades at both ends so that no sound starts or stops with a click."""
    n = len(x)
    fade_in = max(1, int(fade_in_s * rate))
    fade_out = max(1, int(fade_out_s * rate))
    for i in range(min(n, fade_in)):
        x[i] *= i / fade_in
    for i in range(min(n, fade_out)):
        x[n - 1 - i] *= i / fade_out
    return x


def normalise(x, peak_dbfs):
    peak = max((abs(v) for v in x), default=0.0)
    if peak == 0.0:
        return x
    gain = 10.0 ** (peak_dbfs / 20.0) / peak
    return [v * gain for v in x]


def write_wav(path, samples, rate):
    """16-bit mono PCM WAV. Samples are clamped to [-1, 1] before quantising."""
    data = array.array("h", (max(-32767, min(32767, int(round(v * 32767.0)))) for v in samples))
    if sys.byteorder == "big":
        data.byteswap()
    with wave.open(path, "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        handle.writeframes(data.tobytes())


def find_ffmpeg():
    found = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "/usr/bin/ffmpeg"
    if not os.path.exists(found) and not shutil.which(found):
        raise SystemExit("ffmpeg not found: install it or set FFMPEG=/path/to/ffmpeg")
    return found


def encode_ogg(ffmpeg, wav_path, ogg_path):
    """Encode to Ogg Vorbis. Metadata is dropped and bit-exact mode set so reruns match byte for byte."""
    partial = ogg_path + ".part"
    command = [
        ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
        "-i", wav_path,
        "-map_metadata", "-1", "-map_metadata:s:a", "-1", "-metadata:s:a", "encoder=",
        "-fflags", "+bitexact", "-flags:a", "+bitexact",
        *OGG_ENCODER_ARGS,
        "-f", "ogg", partial,
    ]
    subprocess.run(command, check=True)
    os.replace(partial, ogg_path)
