"""
Generates the built-in alarm sounds (app/src/main/res/raw/alarm_*.wav).

Pure standard library, so the sounds are reproducible and free of any licence:
    python tools/gen_alarm_sounds.py

Each file is a short loop (16-bit mono, 22.05 kHz) that the app repeats while the alarm rings.
Design: urgent alarms are fast, bright beeps; normal ones are softer two-tone chimes;
the direction (down = low/falling, up = high/rising) is audible without looking.
"""
import math
import os
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')


def tone(freq, dur, bright=False, decay=0.0, vol=0.8):
    """Sine (+ odd harmonics when bright) with a short attack/release, optional exponential decay."""
    n = int(dur * RATE)
    attack, release = int(0.008 * RATE), int(0.03 * RATE)
    out = []
    for i in range(n):
        t = i / RATE
        s = math.sin(2 * math.pi * freq * t)
        if bright:
            s += 0.33 * math.sin(2 * math.pi * 3 * freq * t) + 0.15 * math.sin(2 * math.pi * 5 * freq * t)
            s /= 1.48
        else:
            s = 0.85 * s + 0.15 * math.sin(2 * math.pi * 2 * freq * t)
        env = min(1.0, i / attack, (n - i) / release)
        if decay:
            env *= math.exp(-decay * t)
        out.append(s * env * vol)
    return out


def sweep(f0, f1, dur, vol=0.8):
    n = int(dur * RATE)
    attack, release = int(0.01 * RATE), int(0.04 * RATE)
    out, phase = [], 0.0
    for i in range(n):
        f = f0 + (f1 - f0) * (i / n)
        phase += 2 * math.pi * f / RATE
        s = 0.8 * math.sin(phase) + 0.2 * math.sin(3 * phase)
        out.append(s * min(1.0, i / attack, (n - i) / release) * vol)
    return out


def silence(dur):
    return [0.0] * int(dur * RATE)


def save(name, samples):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, f'alarm_{name}.wav')
    with wave.open(path, 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s)) * 32000)) for s in samples))
    print(f'{path}  {len(samples) / RATE:.2f} s')


def triple(freqs, gap=0.06, beep=0.15):
    s = []
    for f in freqs:
        s += tone(f, beep, bright=True, vol=0.95) + silence(gap)
    return s


# urgent: two bursts of three fast bright beeps
save('urgent_low', triple([1568, 1319, 1047]) + silence(0.12) + triple([1568, 1319, 1047]) + silence(0.6))
save('urgent_high', triple([1047, 1319, 1568]) + silence(0.12) + triple([1047, 1319, 1568]) + silence(0.6))
# normal: soft two-tone chime, then a pause
save('low', tone(880, 0.32, decay=2.5) + silence(0.05) + tone(659, 0.5, decay=2.5) + silence(1.1))
save('high', tone(659, 0.32, decay=2.5) + silence(0.05) + tone(880, 0.5, decay=2.5) + silence(1.1))
# rate of change: glides
save('falling', sweep(1300, 520, 0.55) + silence(0.1) + sweep(1300, 520, 0.55) + silence(0.9))
save('rising', sweep(520, 1300, 0.55) + silence(0.1) + sweep(520, 1300, 0.55) + silence(0.9))
# signal loss: low bell
save('signal', tone(440, 0.7, decay=3.0) + tone(349, 0.9, decay=3.0) + silence(0.9))
