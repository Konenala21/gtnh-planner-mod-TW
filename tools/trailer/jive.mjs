// The trailer's music: a cool medium swing, synthesized like the game's sounds (../sound/engine.mjs). A walking upright
// bass, a Rhodes comping on the swung offbeats, the ride's swing pattern with the hat on two and four, brushes, and a
// vibraphone over the time-lapses; the tutorial half sits back (brushes and comping), and it ends on a held chord.
//
//   node tools/trailer/jive.mjs build/trailer/jive.wav
//
// 112 BPM, a bar is 60/112*4 = 2.142857 s. SECTIONS lays it out by bar to match trailer.shot.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { SR, biquad, mulberry32 } from "../sound/engine.mjs";

export const BPM = 112;
const BEAT = 60 / BPM, BAR = BEAT * 4, SWING = (2 / 3) * BEAT; // the swung eighth falls two thirds into the beat

const SECTIONS = [
  { from: 0, to: 2, kind: "intro" }, // the showcase pan
  { from: 2, to: 18, kind: "band" }, // the time-lapses and their pans
  { from: 18, to: 38, kind: "easy" }, // the tutorial
  { from: 38, to: 40, kind: "end" }, // the end card
];
const BARS = 40, LENGTH = BARS * BAR + 3;
const N = Math.ceil(LENGTH * SR);
const sectionAt = (bar) => SECTIONS.find((s) => bar >= s.from && bar < s.to);
const sectionOf = (bar) => sectionAt(bar)?.kind ?? "end";

const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);
const at = (t) => Math.round(t * SR);
const rnd = mulberry32(1955);

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

// A little human timing: nothing lands exactly on the grid.
const loose = (t, ms = 8) => t + (rnd() - 0.5) * 2 * (ms / 1000);

// region Drums: ride, hat, brushes, a feathered kick

/** The ride: inharmonic square partials through a high band, a long shimmer. */
function ride(t, vel = 1) {
  const n = at(1.1), b = new Float32Array(n);
  const partials = [2730, 3312, 4178, 5084, 6213, 7431];
  const ph = partials.map(() => rnd());
  const hiss = noise(n, at(t) + 5);
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    let s = 0;
    for (let k = 0; k < partials.length; k++) {
      ph[k] += partials[k] / SR;
      s += ph[k] % 1 < 0.5 ? 1 : -1;
    }
    b[i] = (s / partials.length * 0.6 + hiss[i] * 0.4) * (Math.exp(-tt / 0.32) * 0.8 + 0.2 * Math.exp(-tt / 0.03));
  }
  biquad(b, "highpass", 4200, 0.7);
  biquad(b, "lowpass", 11000, 0.7);
  add(at(t), b, 0.075 * vel, 0.35, 0.15);
}

/** The hi-hat closed with the foot on two and four: a short chick. */
function chick(t, vel = 1) {
  const n = at(0.06), b = noise(n, at(t) + 9);
  biquad(b, "bandpass", 7000, 1.2);
  for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / 0.012);
  add(at(t), b, 0.09 * vel, -0.25, 0.05);
}

/** A brush: soft noise, swept on the beat, slapped on the backbeat. */
function brush(t, vel = 1, slap = false) {
  const n = at(slap ? 0.18 : 0.32), b = noise(n, at(t) + 13);
  biquad(b, "bandpass", slap ? 2600 : 1700, slap ? 0.9 : 0.6);
  for (let i = 0; i < n; i++) {
    const u = i / n;
    b[i] *= slap ? Math.exp(-i / SR / 0.05) : Math.sin(Math.PI * u) * 0.6;
  }
  add(at(t), b, (slap ? 0.12 : 0.05) * vel, 0.1, 0.2);
}

function kick(t, vel = 1) {
  const n = at(0.35), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (2 * Math.PI * (52 + 40 * Math.exp(-tt / 0.03))) / SR;
    b[i] = Math.sin(ph) * Math.exp(-tt / 0.16) * Math.min(1, tt / 0.004);
  }
  add(at(t), b, 0.32 * vel);
}

// endregion

// region The band

/** The upright: a plucked fundamental and its octave, a thump of attack, dark. */
function bass(t, midi, dur, vel = 1) {
  const n = at(dur + 0.25), f = mtof(midi), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (f * (1 + 0.02 * Math.exp(-tt / 0.02))) / SR;
    const env = Math.min(1, tt / 0.006) * Math.exp(-tt / 0.55) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.05));
    b[i] = (Math.sin(2 * Math.PI * ph) + 0.35 * Math.sin(4 * Math.PI * ph) + 0.12 * Math.sin(6 * Math.PI * ph)) * env;
  }
  biquad(b, "lowpass", 900, 0.7);
  add(at(t), b, 0.42 * vel, -0.05, 0.05);
}

/** The Rhodes: a sine with a sine modulating it (its bark fading), a bell tine on the attack, a slow tremolo. */
function rhodes(t, notes, dur, vel = 1) {
  const n = at(dur + 0.9);
  const L = new Float32Array(n), R = new Float32Array(n);
  for (const m of notes) {
    const f = mtof(m);
    let pc = rnd(), pm = rnd(), pt = rnd();
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      pm += f / SR;
      pt += (f * 7.1) / SR;
      const index = 1.6 * Math.exp(-tt / 0.25) + 0.25;
      pc += (f * (1 + 0.0 * 0)) / SR;
      const env = Math.min(1, tt / 0.004) * Math.exp(-tt / 1.4) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.18));
      const s = Math.sin(2 * Math.PI * pc + index * Math.sin(2 * Math.PI * pm)) * env +
        0.12 * Math.sin(2 * Math.PI * pt) * Math.exp(-tt / 0.06);
      const trem = 0.5 + 0.5 * Math.sin(2 * Math.PI * 4.2 * tt);
      L[i] += s * (0.55 + 0.45 * trem);
      R[i] += s * (1 - 0.45 * trem);
    }
  }
  const g = 0.075 * vel / Math.sqrt(notes.length);
  for (let i = 0; i < n; i++) {
    const j = at(t) + i;
    if (j >= N) break;
    mix.L[j] += L[i] * g;
    mix.R[j] += R[i] * g;
    verb.L[j] += L[i] * g * 0.25;
    verb.R[j] += R[i] * g * 0.25;
  }
}

/** The vibraphone: a sine and its fourth partial, the motor's tremolo, a long ring. */
function vibes(t, midi, dur, vel = 1, pan = 0.2) {
  const n = at(dur + 1.6), f = mtof(midi), b = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const tt = i / SR, w = 2 * Math.PI * f * tt;
    const env = Math.min(1, tt / 0.003) * Math.exp(-tt / 1.2) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.4));
    const motor = 0.75 + 0.25 * Math.sin(2 * Math.PI * 5.5 * tt);
    b[i] = (Math.sin(w) + 0.22 * Math.sin(4 * w) * Math.exp(-tt / 0.2)) * env * motor;
  }
  add(at(t), b, 0.07 * vel, pan, 0.35);
}

// endregion

// region The changes

// ii-V-I-VI in C, then IV-iii-ii-V for the tutorial's turnaround: rootless voicings, a walking line into each root.
const CHANGES = {
  band: [
    { name: "Dm9", voicing: [53, 57, 60, 64], walk: [38, 41, 45, 44] },
    { name: "G13", voicing: [59, 64, 65, 69], walk: [43, 47, 50, 49] },
    { name: "Cmaj9", voicing: [52, 55, 59, 62], walk: [36, 40, 43, 46] },
    { name: "A7b13", voicing: [61, 65, 67, 71], walk: [45, 49, 52, 39] },
  ],
  easy: [
    { name: "Fmaj9", voicing: [52, 55, 57, 60], walk: [41, 45, 48, 39] },
    { name: "Em7", voicing: [55, 59, 62, 66], walk: [40, 43, 47, 37] },
    { name: "Dm9", voicing: [53, 57, 60, 64], walk: [38, 41, 45, 42] },
    { name: "G13", voicing: [59, 64, 65, 69], walk: [43, 47, 50, 41] },
  ],
};
const END = { voicing: [52, 55, 59, 62, 69], root: 36 }; // Cmaj9 with the 13th

// The vibes' line over the band: a phrase every two bars, answered.
const LINE = [
  [[0, 1, 72], [SWING / BEAT, 0.5, 74], [1, 1, 77], [2.66, 1.2, 76]],
  [[0.66, 0.5, 74], [1, 0.8, 71], [2, 1.5, 69]],
  [[0, 1, 76], [1.66, 0.5, 79], [2, 1, 76], [3, 1, 74]],
  [[0, 2.5, 73], [2.66, 1, 69]],
];

for (let bar = 0; bar < SECTIONS.find((s) => s.kind === "end").from; bar++) {
  const t0 = bar * BAR, kind = sectionOf(bar);
  const set = kind === "easy" ? CHANGES.easy : CHANGES.band;
  // Each section starts its changes from the top.
  const pos = bar - sectionAt(bar).from, ch = set[pos % 4];
  const band = kind === "band", easy = kind === "easy", intro = kind === "intro";
  // Comping: the Charleston (one, and the swung "and" of two), sometimes a push on the "and" of four.
  rhodes(loose(t0), ch.voicing, BEAT * 0.9, intro ? 0.7 : 1);
  rhodes(loose(t0 + BEAT + SWING), ch.voicing, BEAT * 0.45, 0.75);
  if (bar % 2 === 1 && !intro) rhodes(loose(t0 + 3 * BEAT + SWING), set[(pos + 1) % 4].voicing, BEAT * 0.6, 0.7);
  // The walking bass, from the second bar on.
  if (bar >= 1) for (let b = 0; b < 4; b++) bass(loose(t0 + b * BEAT, 6), ch.walk[b], BEAT * 0.92, b === 0 ? 1 : 0.85);
  // The ride's swing: ding, ding-ga, ding, ding-ga.
  if (!intro || bar === 1) for (let b = 0; b < 4; b++) {
    ride(loose(t0 + b * BEAT, 5), b % 2 === 0 ? 0.9 : 1);
    if (b % 2 === 1) ride(loose(t0 + b * BEAT + SWING, 5), 0.55);
    if (b % 2 === 1) chick(loose(t0 + b * BEAT, 4), easy ? 0.8 : 1);
  }
  // Brushes under it all, a slap on four now and then; a feathered kick through the band.
  if (!intro) for (let b = 0; b < 4; b++) {
    brush(loose(t0 + b * BEAT), easy ? 0.8 : 1);
    if (b === 3 && bar % 2 === 1) brush(loose(t0 + b * BEAT + SWING), 0.8, true);
    if (band) kick(loose(t0 + b * BEAT, 4), b === 0 ? 0.55 : 0.35);
  }
  if (band && bar >= 4) for (const [b, d, m] of LINE[Math.floor(bar / 2) % 4].filter(() => bar % 2 === 0)) {
    vibes(loose(t0 + b * BEAT, 6), m, d * BEAT, 0.9, 0.3);
  }
  if (easy && pos % 4 === 1) vibes(loose(t0 + 2 * BEAT), ch.voicing[3] + 12, BEAT * 2, 0.55, -0.3);
}
// The end: the chord held, a roll of vibes up through it, the bass's low C.
{
  const t0 = SECTIONS.find((s) => s.kind === "end").from * BAR;
  rhodes(t0, END.voicing, BAR * 1.8, 1.1);
  bass(t0, END.root, BAR * 1.6, 0.9);
  ride(t0, 0.9);
  [72, 76, 79, 83, 86].forEach((m, k) => vibes(t0 + k * SWING * 0.5, m, BAR, 0.6 - k * 0.06, k % 2 ? 0.3 : -0.3));
}

// endregion

// region Room and master

function reverb(inp, out, spread, room = 0.82, damp = 0.45) {
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
  L[i] = mix.L[i] + wet.L[i] * 0.8;
  R[i] = mix.R[i] + wet.R[i] * 0.8;
}
biquad(L, "highpass", 32, 0.7);
biquad(R, "highpass", 32, 0.7);
// A touch of warmth: the top rolled off a little, as on a record.
biquad(L, "lowpass", 14000, 0.6);
biquad(R, "lowpass", 14000, 0.6);
let peak = 0;
for (let i = 0; i < N; i++) {
  L[i] = Math.tanh(L[i] * 1.1) / 1.1;
  R[i] = Math.tanh(R[i] * 1.1) / 1.1;
  peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
const norm = 0.85 / peak, fade = at(2.5);
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
const file = process.argv[2] ?? "build/trailer/jive.wav";
mkdirSync(dirname(file), { recursive: true });
writeFileSync(file, out);
console.log(`${file}: ${LENGTH.toFixed(2)} s at ${BPM} BPM (a bar ${BAR.toFixed(4)} s), the end at bar 38`);

// endregion
