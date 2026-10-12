// The trailer's music: a funk groove, synthesized like the game's sounds (../sound/engine.mjs). Tight drums with ghost
// notes on the snare, a syncopated bass with octave pops, a clavinet chopping the offbeats through an envelope filter,
// synth brass stabs and a short hook over the time-lapses; the tutorial half pulls back to the drums, the bass and a
// soft pad, and it ends on one hit left ringing.
//
//   node tools/trailer/funk.mjs build/trailer/funk.wav [--demo]
//
// 112 BPM, a bar is 60/112*4 = 2.142857 s, sixteen sixteenths a bar, lightly swung. SECTIONS lays it out by bar to
// match trailer.shot.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { SR, biquad, mulberry32 } from "../sound/engine.mjs";
import { layout } from "./kit.mjs";

// --relaxed: the same groove laid back, the drums in half time (the snare on three), no brass, the clav and the bass
// softer, a pad under everything, more room. --bpm=N plays it at another tempo (the edit is cut to 112).
const ARGS = process.argv.slice(2), CALM = ARGS.includes("--relaxed");
export const BPM = Number((ARGS.find((a) => a.startsWith("--bpm=")) ?? "--bpm=112").slice(6));
const BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4, SWING = 0.12; // the off sixteenths land 12% of one late

// The intro under the showcase pan, the band under the time-lapses and their pans, the easy half under the tutorial,
// the end under the end card (kit.mjs's layout; --demo plays a short piece instead).
const { sections: SECTIONS, bars: BARS, file: FILE } = layout(process.argv.slice(2));
const LENGTH = BARS * BAR + 3;
const N = Math.ceil(LENGTH * SR);
const sectionAt = (bar) => SECTIONS.find((s) => bar >= s.from && bar < s.to);
const sectionOf = (bar) => sectionAt(bar)?.kind ?? "end";

const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const at = (t) => Math.round(t * SR);
const rnd = mulberry32(1977);

const bus = () => ({ L: new Float32Array(N), R: new Float32Array(N) });
const mix = bus(), verb = bus();

function add(i0, buf, gain, pan = 0, send = 0) {
  const gl = gain * Math.cos(((pan + 1) * Math.PI) / 4) * Math.SQRT2;
  const gr = gain * Math.sin(((pan + 1) * Math.PI) / 4) * Math.SQRT2;
  for (let i = 0; i < buf.length; i++) {
    const j = i0 + i;
    if (j < 0 || j >= N) continue;
    mix.L[j] += buf[i] * gl;
    mix.R[j] += buf[i] * gr;
    verb.L[j] += buf[i] * gl * send;
    verb.R[j] += buf[i] * gr * send;
  }
}

function noise(n, seed) {
  const r = mulberry32(seed), b = new Float32Array(n);
  for (let i = 0; i < n; i++) b[i] = r() * 2 - 1;
  return b;
}

/** A sixteenth's time in a bar, the off ones swung, a little human looseness on top. */
const step = (bar, s, ms = 4) => bar * BAR + s * SIX + (s % 2 === 1 ? SWING * SIX : 0) + (rnd() - 0.5) * 2 * (ms / 1000);

// region Drums

function kick(t, vel = 1) {
  const n = at(0.4), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (2 * Math.PI * (48 + 85 * Math.exp(-tt / 0.025))) / SR;
    b[i] = Math.tanh(Math.sin(ph) * 1.6) * Math.exp(-tt / 0.2) * Math.min(1, tt / 0.002);
  }
  const click = noise(at(0.006), at(t) + 1);
  biquad(click, "highpass", 2000, 0.7);
  for (let i = 0; i < click.length; i++) b[i] += click[i] * 0.25 * (1 - i / click.length);
  add(at(t), b, 0.42 * vel);
}

/** The snare: a tight body and a crack of noise; ghost notes are the same, much softer and shorter. */
function snare(t, vel = 1) {
  const ghost = vel < 0.5, n = at(ghost ? 0.08 : 0.22), b = noise(n, at(t) + 3);
  biquad(b, "bandpass", 2400, 0.8);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (2 * Math.PI * (185 + 40 * Math.exp(-tt / 0.01))) / SR;
    const body = Math.sin(ph) * Math.exp(-tt / 0.045);
    b[i] = (b[i] * 1.4 * Math.exp(-tt / (ghost ? 0.025 : 0.07)) + body * 0.8) * Math.min(1, tt / 0.001);
  }
  add(at(t), b, 0.2 * vel, 0.05, ghost ? 0.03 : 0.12);
}

function hat(t, vel = 1, open = false) {
  const n = at(open ? 0.32 : 0.05), b = noise(n, at(t) + 7);
  biquad(b, "highpass", 7500, 0.8);
  for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / (open ? 0.12 : 0.014));
  add(at(t), b, 0.07 * vel, -0.3, 0.04);
}

function crash(t, vel = 1) {
  const n = at(2.2), b = noise(n, at(t) + 11);
  biquad(b, "highpass", 4500, 0.6);
  for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / 0.7) * Math.min(1, i / SR / 0.002);
  add(at(t), b, 0.09 * vel, 0.35, 0.25);
}

// endregion

// region The band

/** The bass: a saw and a square through a plucked low-pass, round; the octave pops brighter and shorter. */
function bass(t, midi, dur, vel = 1, pop = false) {
  const n = at(dur + 0.08), f = mtof(midi), b = new Float32Array(n);
  let ph = 0, lp = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph = (ph + f / SR) % 1;
    const raw = (2 * ph - 1) * 0.6 + (ph < 0.5 ? 0.4 : -0.4);
    // A one-pole low-pass whose cutoff falls with the pluck.
    const cut = (pop ? 2600 : 900) * Math.exp(-tt / (pop ? 0.05 : 0.09)) + 180;
    const a = 1 - Math.exp((-2 * Math.PI * cut) / SR);
    lp += (raw - lp) * a;
    const env = Math.min(1, tt / 0.003) * Math.exp(-tt / (pop ? 0.12 : 0.5)) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.015));
    b[i] = (lp + 0.5 * Math.sin(2 * Math.PI * f * tt)) * env;
  }
  add(at(t), b, 0.38 * vel, 0, 0);
}

/** The clavinet: a narrow pulse per note through a resonant band-pass that sweeps down as it plays (the wah). */
function clav(t, notes, vel = 1, short = false) {
  const n = at(short ? 0.05 : 0.2), b = new Float32Array(n);
  for (const m of notes) {
    const f = mtof(m);
    let ph = rnd();
    for (let i = 0; i < n; i++) {
      ph = (ph + f / SR) % 1;
      b[i] += ph < 0.22 ? 1 : -0.28;
    }
  }
  // The envelope filter, in chunks: its centre falls from bright to dark across the note.
  const out = new Float32Array(n), chunk = 64;
  let z1 = 0, z2 = 0;
  for (let i0 = 0; i0 < n; i0 += chunk) {
    const tt = i0 / SR, fc = 650 + 2400 * Math.exp(-tt / 0.045), q = 3.2;
    const w = (2 * Math.PI * fc) / SR, al = Math.sin(w) / (2 * q), c = Math.cos(w);
    const b0 = al / (1 + al), a1 = (-2 * c) / (1 + al), a2 = (1 - al) / (1 + al);
    for (let i = i0; i < Math.min(n, i0 + chunk); i++) {
      // Direct form II transposed, the band-pass's numerator b0, 0, -b0.
      const x = b[i], y = b0 * x + z1;
      z1 = -a1 * y + z2;
      z2 = -b0 * x - a2 * y;
      out[i] = y;
    }
  }
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    out[i] *= Math.min(1, tt / 0.0015) * Math.exp(-tt / (short ? 0.018 : 0.11));
  }
  add(at(t), out, (0.11 * vel) / Math.sqrt(notes.length), 0.3, 0.1);
}

/** Synth brass: three detuned saws a note, a filter that opens on the attack and settles. */
function brass(t, notes, dur, vel = 1) {
  const n = at(dur + 0.25), b = new Float32Array(n);
  for (const m of notes) {
    for (const d of [-0.12, 0, 0.11]) {
      const f = mtof(m + d);
      let ph = rnd();
      for (let i = 0; i < n; i++) {
        ph = (ph + f / SR) % 1;
        b[i] += 2 * ph - 1;
      }
    }
  }
  let lp = 0, lp2 = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    const cut = 1300 + 3200 * Math.exp(-tt / 0.08), a = 1 - Math.exp((-2 * Math.PI * cut) / SR);
    lp += (b[i] - lp) * a;
    lp2 += (lp - lp2) * a;
    const env = Math.min(1, tt / 0.012) * (0.75 + 0.25 * Math.exp(-tt / 0.1)) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.07));
    b[i] = lp2 * env;
  }
  add(at(t), b, (0.05 * vel) / Math.sqrt(notes.length * 3), -0.15, 0.22);
}

/** The hook's lead: a square softened by a low-pass, gliding into each note, a little vibrato once it is held. */
function lead(t, midi, dur, vel = 1, from = null) {
  const n = at(dur + 0.2), f1 = mtof(midi), f0 = from == null ? f1 : mtof(from), b = new Float32Array(n);
  let ph = 0, lp = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    const glide = f1 + (f0 - f1) * Math.exp(-tt / 0.025);
    const vib = 1 + 0.004 * Math.sin(2 * Math.PI * 5.6 * tt) * Math.min(1, Math.max(0, (tt - 0.15) / 0.2));
    ph = (ph + (glide * vib) / SR) % 1;
    const raw = ph < 0.5 ? 1 : -1;
    lp += (raw - lp) * 0.16;
    const env = Math.min(1, tt / 0.008) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.06));
    b[i] = lp * env;
  }
  add(at(t), b, 0.045 * vel, 0.2, 0.3);
}

/** A soft pad under the tutorial: two detuned saws a note, dark, swelling in. */
function pad(t, notes, dur, vel = 1) {
  const n = at(dur + 0.6), b = new Float32Array(n);
  for (const m of notes) {
    for (const d of [-0.07, 0.07]) {
      const f = mtof(m + d);
      let ph = rnd();
      for (let i = 0; i < n; i++) {
        ph = (ph + f / SR) % 1;
        b[i] += 2 * ph - 1;
      }
    }
  }
  biquad(b, "lowpass", 900, 0.7);
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    b[i] *= Math.min(1, tt / 0.35) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.25));
  }
  add(at(t), b, (0.022 * vel) / Math.sqrt(notes.length), -0.2, 0.3);
}

// endregion

// region The groove

// Em7 to A9, the dorian vamp, with a B7#9 turning it round; the tutorial's lift: C, D, Em.
const CHORDS = {
  Em7: { root: 40, minor: true, notes: [55, 59, 62, 64] },
  A9: { root: 45, minor: false, notes: [55, 59, 61, 64] },
  "B7#9": { root: 47, minor: true, notes: [57, 62, 63, 66] },
  Cmaj7: { root: 36, minor: false, notes: [55, 59, 60, 64] },
  D6: { root: 38, minor: false, notes: [54, 57, 59, 62] },
};
const CHANGES = { band: ["Em7", "Em7", "A9", "B7#9"], easy: ["Cmaj7", "D6", "Em7", "Em7"] };

// The bass riff, [sixteenth, interval from the root, length in sixteenths, velocity, an octave pop].
const RIFF = {
  minor: [[0, 0, 3, 1], [3, 0, 1, 0.45], [4, 12, 1, 0.9, 1], [6, 0, 2, 0.8], [8, 3, 1, 0.75], [10, 5, 2, 0.9],
    [12, 0, 1, 0.7], [13, 12, 1, 0.9, 1], [14, 10, 1, 0.75], [15, 7, 1, 0.7]],
  major: [[0, 0, 3, 1], [3, 0, 1, 0.45], [4, 12, 1, 0.9, 1], [6, 0, 2, 0.8], [8, 4, 1, 0.75], [10, 7, 2, 0.9],
    [12, 0, 1, 0.7], [13, 12, 1, 0.9, 1], [14, 9, 1, 0.75], [15, 7, 1, 0.7]],
  easy: [[0, 0, 3, 1], [6, 0, 1, 0.7], [8, 7, 2, 0.85], [11, 12, 1, 0.7, 1], [14, 7, 2, 0.75]],
};
// The clav's chops on the offbeats: [sixteenth, velocity]; under 0.6 is a muted ghost.
const CHOPS = [[2, 1], [3, 0.45], [5, 0.5], [7, 0.9], [10, 1], [11, 0.45], [13, 0.5], [15, 0.8]];
// The hook, two bars of E minor pentatonic: [sixteenth, note, length in sixteenths].
const HOOK = [[0, 71, 2], [2, 74, 2], [4, 76, 3], [8, 74, 1], [10, 71, 2], [12, 69, 4], [18, 67, 2], [20, 69, 2],
  [22, 71, 6]];

for (let bar = 0; bar < SECTIONS.find((s) => s.kind === "end").from; bar++) {
  const kind = sectionOf(bar), pos = bar - sectionAt(bar).from;
  const band = kind === "band", easy = kind === "easy", intro = kind === "intro";
  const ch = CHORDS[(easy ? CHANGES.easy : CHANGES.band)[pos % 4]];
  const lastOfSection = bar === sectionAt(bar).to - 1;
  if (pos === 0 && !intro) crash(step(bar, 0), (easy ? 0.7 : 1) * (CALM ? 0.6 : 1));

  // Hats: sixteenths through the band, eighths under the tutorial; an open one on the "and" of four now and then.
  for (let s = 0; s < 16; s++) {
    if ((easy || CALM) && s % 2 === 1) continue;
    if (intro && bar === 0 && s < 8) continue;
    const open = s === 14 && bar % 2 === 1 && !intro && !CALM;
    hat(step(bar, s), (s % 4 === 0 ? 1 : s % 2 === 0 ? 0.7 : 0.45) * (easy ? 0.7 : 1) * (CALM ? 0.65 : 1), open);
  }
  // Kick and snare, from the second intro bar; ghosts on the snare through the band.
  if (!(intro && bar === 0)) {
    const kicks = CALM ? (easy ? [0] : [0, 10]) : easy ? [0, 10] : intro ? [0, 8] : bar % 2 === 0 ? [0, 6, 10] : [0, 6, 9, 14];
    for (const s of kicks) kick(step(bar, s, 2), (s === 0 ? 1 : 0.8) * (CALM ? 0.85 : 1));
    for (const s of CALM ? [8] : [4, 12]) snare(step(bar, s, 3), (easy ? 0.75 : 1) * (CALM ? 0.8 : 1));
    if (band) for (const [s, v] of CALM ? [[15, 0.2]] : [[7, 0.22], [9, 0.18], [15, 0.28]]) snare(step(bar, s), v);
  }
  // A fill into each new section.
  if (lastOfSection && !easy) for (const [s, v] of [[13, 0.5], [14, 0.7], [15, 0.9]]) snare(step(bar, s), v * (CALM ? 0.6 : 1));

  // The bass, from the second intro bar.
  if (!(intro && bar === 0)) {
    const riff = easy || CALM ? RIFF.easy : ch.minor ? RIFF.minor : RIFF.major;
    for (const [s, iv, len, v, pop] of riff) {
      if (lastOfSection && s >= 13 && !easy) continue;
      bass(step(bar, s, 3), ch.root + iv, len * SIX * 0.92, v * (easy ? 0.85 : 1) * (CALM ? 0.9 : 1), !!pop && !CALM);
    }
  }
  // The clav: every chop through the intro and the band; only the strong ones, softer, under the tutorial.
  for (const [s, v] of CHOPS) {
    if ((easy || CALM) && v < 0.9) continue;
    clav(step(bar, s, 3), ch.notes, v * (easy ? 0.45 : intro ? 0.8 : 1) * (CALM ? 0.6 : 1), v < 0.6);
  }
  // Brass: a stab into the band, then two at the end of every fourth bar.
  if (band && pos === 0 && !CALM) brass(step(bar, 0), ch.notes.map((m) => m + 12), SIX * 3, 1);
  if (band && pos % 4 === 3 && !CALM) {
    brass(step(bar, 10), ch.notes.map((m) => m + 12), SIX * 1.5, 0.9);
    brass(step(bar, 14), CHORDS[CHANGES.band[0]].notes.map((m) => m + 12), SIX * 2, 1);
  }
  // The hook over the time-lapses, every four bars from the fifth.
  if (band && pos >= 4 && pos % 4 === 0) {
    let prev = null;
    for (const [s, m, len] of HOOK) {
      lead(bar * BAR + s * SIX + (s % 2 ? SWING * SIX : 0), m, len * SIX * 0.9, CALM ? 0.6 : 1, prev);
      prev = m;
    }
  }
  // Under the tutorial: a pad on each change, the hook once, quietly, near its end.
  if (easy || (CALM && !(intro && bar === 0))) pad(step(bar, 0, 0), ch.notes, BAR * 0.98, CALM && !easy ? 1.3 : 1);
  if (easy && (pos === 12)) {
    let prev = null;
    for (const [s, m, len] of HOOK) {
      lead(bar * BAR + s * SIX + (s % 2 ? SWING * SIX : 0), m, len * SIX * 0.9, 0.55, prev);
      prev = m;
    }
  }
}
// The end: one hit, everything on the one, the chord left ringing.
{
  const bar = SECTIONS.find((s) => s.kind === "end").from, t0 = bar * BAR;
  const fin = [55, 59, 62, 66]; // Em9
  kick(t0, 1);
  crash(t0, 1.1);
  snare(t0, 0.8);
  bass(t0, 40, BAR * 1.2, 1);
  brass(t0, fin.map((m) => m + 12), BAR * 1.1, CALM ? 0.5 : 1);
  pad(t0, fin, BAR * 1.6, 1.4);
  clav(t0, fin, 0.9);
  // A bar later, a soft button on the beat the end card's subtitle drops in on.
  clav(t0 + BAR, fin.map((m) => m + 12), 0.5);
  lead(t0 + BAR, 76, BEAT * 0.9, 0.5);
}

// endregion

// region Room and master

function reverb(inp, out, spread, room = 0.8, damp = 0.5) {
  const combs = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617].map((d) => d + spread);
  const alls = [556, 441, 341, 225].map((d) => d + spread);
  const acc = new Float32Array(N);
  for (const d of combs) {
    const buf = new Float32Array(d);
    let idx = 0, low = 0;
    for (let i = 0; i < N; i++) {
      const y = buf[idx];
      low = y * (1 - damp) + low * damp;
      buf[idx] = inp[i] * 0.015 + low * room;
      acc[i] += y;
      idx = (idx + 1) % d;
    }
  }
  for (const d of alls) {
    const buf = new Float32Array(d);
    let idx = 0;
    for (let i = 0; i < N; i++) {
      const b = buf[idx], x = acc[i];
      acc[i] = -x + b;
      buf[idx] = x + b * 0.5;
      idx = (idx + 1) % d;
    }
  }
  for (let i = 0; i < N; i++) out[i] += acc[i];
}

const wet = bus();
reverb(verb.L, wet.L, 0);
reverb(verb.R, wet.R, 23);
const L = new Float32Array(N), R = new Float32Array(N);
for (let i = 0; i < N; i++) {
  L[i] = mix.L[i] + wet.L[i] * (CALM ? 0.85 : 0.6);
  R[i] = mix.R[i] + wet.R[i] * (CALM ? 0.85 : 0.6);
}
biquad(L, "highpass", 30, 0.7);
biquad(R, "highpass", 30, 0.7);
if (CALM) for (const c of [L, R]) biquad(c, "lowpass", 8000, 0.6);
let peak = 0;
for (let i = 0; i < N; i++) {
  L[i] = Math.tanh(L[i] * 1.3) / 1.3;
  R[i] = Math.tanh(R[i] * 1.3) / 1.3;
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
const norm = 0.88 / peak, fade = at(2.5);
const out = Buffer.alloc(44 + N * 4);
out.write("RIFF", 0);
out.writeUInt32LE(36 + N * 4, 4);
out.write("WAVEfmt ", 8);
out.writeUInt32LE(16, 16);
out.writeUInt16LE(1, 20);
out.writeUInt16LE(2, 22);
out.writeUInt32LE(SR, 24);
out.writeUInt32LE(SR * 4, 28);
out.writeUInt16LE(4, 32);
out.writeUInt16LE(16, 34);
out.write("data", 36);
out.writeUInt32LE(N * 4, 40);
for (let i = 0; i < N; i++) {
  const f = i > N - fade ? (N - i) / fade : 1;
  out.writeInt16LE(Math.round(Math.max(-1, Math.min(1, L[i] * norm * f)) * 32767), 44 + i * 4);
  out.writeInt16LE(Math.round(Math.max(-1, Math.min(1, R[i] * norm * f)) * 32767), 46 + i * 4);
}
const file = FILE ?? "build/trailer/funk.wav";
mkdirSync(dirname(file), { recursive: true });
writeFileSync(file, out);
console.log(`${file}: ${LENGTH.toFixed(2)} s at ${BPM} BPM (a bar ${BAR.toFixed(4)} s)`);

// endregion
