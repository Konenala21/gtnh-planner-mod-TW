// The trailer's music, a nice café: jazz harmony on a soft hip-hop pocket, not a swing band. A Rhodes comping lush
// chords, a round bass on the roots and a few pickups, a soft kick, a rim on two and four, a shaker, a vibraphone line
// now and then, a little vinyl warmth; the tutorial half sits back further; it ends on a held chord.
//
//   node tools/trailer/cafe.mjs build/trailer/cafe.wav [--demo] [--bpm=N]
//
// Cut to 112 BPM (a bar is 2.142857 s); --bpm plays it at another tempo.

import { studio, layout, mtof, SR, biquad } from "./kit.mjs";

const ARGS = process.argv.slice(2);
const BPM = Number((ARGS.find((a) => a.startsWith("--bpm=")) ?? "--bpm=112").slice(6));
const BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4, SWING = 0.18;
const { sections, bars, file } = layout(ARGS);
const LENGTH = bars * BAR + 3;
const S = studio(LENGTH, 1959);
const sectionAt = (bar) => sections.find((s) => bar >= s.from && bar < s.to);
const step = (bar, s, ms = 6) => bar * BAR + s * SIX + (s % 2 ? SWING * SIX : 0) + (S.rnd() - 0.5) * 2 * (ms / 1000);
const at = (t) => Math.round(t * SR);

// region Voices

/** The Rhodes: a sine with a sine modulating it, its bark fading, a bell tine on the attack, a slow stereo tremolo. */
function rhodes(t, notes, dur, vel = 1) {
  const n = at(dur + 0.9), L = new Float32Array(n), R = new Float32Array(n);
  for (const m of notes) {
    const f = mtof(m);
    let pc = S.rnd(), pm = S.rnd(), pt = S.rnd();
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      pm += f / SR;
      pt += (f * 7.1) / SR;
      pc += f / SR;
      const index = 1.2 * Math.exp(-tt / 0.25) + 0.2;
      const env = Math.min(1, tt / 0.005) * Math.exp(-tt / 1.6) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.2));
      const s = Math.sin(2 * Math.PI * pc + index * Math.sin(2 * Math.PI * pm)) * env +
        0.035 * Math.sin(2 * Math.PI * pt) * Math.exp(-tt / 0.05);
      const trem = 0.5 + 0.5 * Math.sin(2 * Math.PI * 3.6 * tt);
      L[i] += s * (0.6 + 0.4 * trem);
      R[i] += s * (1 - 0.4 * trem);
    }
  }
  biquad(L, "lowpass", 4200, 0.7);
  biquad(R, "lowpass", 4200, 0.7);
  const g = (0.06 * vel) / Math.sqrt(notes.length);
  S.add(at(t), L, g, -0.9, 0.3);
  S.add(at(t), R, g, 0.9, 0.3);
}

/** The bass: round, a sine and a little of its octave, a soft thump. */
function bass(t, midi, dur, vel = 1) {
  const n = at(dur + 0.1), f = mtof(midi), b = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    ph += (f * (1 + 0.015 * Math.exp(-tt / 0.02))) / SR;
    const env = Math.min(1, tt / 0.008) * Math.exp(-tt / 0.9) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.04));
    b[i] = (Math.sin(2 * Math.PI * ph) + 0.25 * Math.sin(4 * Math.PI * ph) + 0.06 * Math.sin(6 * Math.PI * ph)) * env;
  }
  biquad(b, "lowpass", 700, 0.7);
  S.add(at(t), b, 0.36 * vel, 0, 0.02);
}

/** The vibraphone: a sine and its fourth partial, the motor's slow tremolo, a long ring. */
function vibes(t, midi, dur, vel = 1, pan = 0.25) {
  const n = at(dur + 1.4), f = mtof(midi), b = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const tt = i / SR, w = 2 * Math.PI * f * tt;
    const env = Math.min(1, tt / 0.004) * Math.exp(-tt / 1.1) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.35));
    b[i] = (Math.sin(w) + 0.18 * Math.sin(4 * w) * Math.exp(-tt / 0.15)) * env * (0.8 + 0.2 * Math.sin(2 * Math.PI * 4.5 * tt));
  }
  S.add(at(t), b, 0.05 * vel, pan, 0.4);
}

/** The backbeat, soft: a brushed snare, a dark knock and a swish, nothing sharp. */
function rim(t, vel = 1) {
  const n = at(0.22), b = S.noise(n, at(t) + 21);
  biquad(b, "lowpass", 2600, 0.7);
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    const swell = Math.min(1, tt / 0.006);
    b[i] = (b[i] * 0.9 * Math.exp(-tt / 0.07) + Math.sin(2 * Math.PI * 190 * tt) * 0.5 * Math.exp(-tt / 0.03)) * swell;
  }
  S.add(at(t), b, 0.1 * vel, 0.1, 0.22);
}

/** A shaker: a soft swish of high noise. */
function shaker(t, vel = 1) {
  const n = at(0.09), b = S.noise(n, at(t) + 23);
  biquad(b, "bandpass", 5000, 0.8);
  for (let i = 0; i < n; i++) {
    const u = i / n;
    b[i] *= Math.sin(Math.PI * Math.min(1, u * 2.5)) * Math.exp(-u * 3);
  }
  S.add(at(t), b, 0.028 * vel, -0.35, 0.05);
}

// endregion

// IV, iii, ii, I in C with a passing chord: lush voicings, the bass on the roots.
const CHANGES = [
  { name: "Fmaj9", root: 41, voicing: [57, 60, 64, 67] },
  { name: "Em9", root: 40, voicing: [55, 59, 62, 66] },
  { name: "Dm9", root: 38, voicing: [53, 57, 60, 64] },
  { name: "Cmaj9", root: 36, voicing: [55, 59, 62, 64], push: { root: 45, voicing: [55, 60, 63, 67] } }, // A7#9 into F
];
// The vibes' phrases, answered: [beat, length in beats, note].
const LINE = [
  [[0.5, 1, 76], [1.5, 0.5, 74], [2, 1.5, 72]],
  [[0, 1, 71], [1, 0.5, 72], [1.5, 2, 74]],
  [[0.5, 0.5, 77], [1, 1, 76], [2, 1, 72], [3, 1, 69]],
  [[0, 3, 71]],
];

const BREAKS = new Set(bars > 20 ? [7, 8, 16, 17, 23, 29, 34, 35] : [5, 6]);

for (let bar = 0; bar < bars; bar++) {
  const sec = sectionAt(bar), brk = BREAKS.has(bar), before = BREAKS.has(bar + 1) && !brk;
  if (sec.kind === "end") break;
  const pos = bar - sec.from, ch = CHANGES[pos % 4];
  const easy = sec.kind === "easy", intro = sec.kind === "intro", band = sec.kind === "band";
  const soft = easy ? 0.8 : 1;
  // Comping: on one, a soft push on the "and" of two, sometimes on the "and" of four into the next chord.
  // In a break the chord rings out longer, alone.
  rhodes(step(bar, 0), ch.voicing, brk ? BEAT * 3.5 : BEAT * 1.6, (intro ? 0.85 : 1) * soft);
  if (!brk) rhodes(step(bar, 6), ch.voicing, BEAT * 0.8, 0.6 * soft);
  if (ch.push && !intro) rhodes(step(bar, 14), ch.push.voicing, BEAT * 0.5, 0.65 * soft);
  // The bass from the second bar: the root, a pickup before three, the fifth, a step into the next.
  if (bar >= 1 && !brk) {
    bass(step(bar, 0, 4), ch.root, BEAT * 1.4, 1 * soft);
    bass(step(bar, 7, 4), ch.root, SIX * 0.9, 0.55 * soft);
    bass(step(bar, 8, 4), ch.root + 7, BEAT * 1.2, 0.8 * soft);
    if (ch.push) bass(step(bar, 14, 4), ch.push.root, SIX * 1.8, 0.75 * soft);
    else if (!easy && !before) bass(step(bar, 14, 4), ch.root + 12, SIX * 1.5, 0.5);
  }
  // Drums: a soft kick on one and the "and" of two, a rim on two and four, a shaker on the sixteenths.
  if (bar >= 1 && !brk) {
    for (const s of easy ? [0] : [0, 6, ...(bar % 2 ? [11] : [])]) S.kick(step(bar, s, 3), s === 0 ? 0.75 : 0.55, { low: 50, top: 55, decay: 0.16, drive: 1.1, click: 0 });
    for (const s of [4, 12]) rim(step(bar, s, 4), soft);
    for (let s = 0; s < (before ? 8 : 16); s += easy ? 2 : 1) shaker(step(bar, s, 5), (s % 4 === 0 ? 0.9 : s % 2 ? 0.45 : 0.65) * soft);
  } else if (bar === 0) for (let s = 8; s < 16; s += 2) shaker(step(bar, s, 5), 0.6);
  // A break always gets the vibes, softly, in the space the band left.
  if (brk) for (const [b, d, m] of LINE[bar % 4]) vibes(step(bar, Math.round(b * 4)), m, d * BEAT, 0.6, -0.2);
  // Vibes: a phrase every other bar through the band, answered; now and then under the tutorial.
  if (!brk && ((band && bar % 2 === 0 && pos >= 2) || (easy && pos % 4 === 1))) {
    for (const [b, d, m] of LINE[Math.floor(bar / 2) % 4]) vibes(step(bar, Math.round(b * 4)), m, d * BEAT, easy ? 0.65 : 0.9);
  }
}
// The end: the chord held, the bass's low C, a roll of vibes up through it; a bar later one note, the button.
{
  const t0 = sections.find((s) => s.kind === "end").from * BAR;
  rhodes(t0, [52, 55, 59, 62, 69], BAR * 1.8, 1.05);
  bass(t0, 36, BAR * 1.6, 0.9);
  S.kick(t0, 0.7, { low: 50, top: 55, decay: 0.2, drive: 1.1, click: 0 });
  [72, 76, 79, 83].forEach((m, k) => vibes(t0 + k * SIX, m, BAR, 0.55 - k * 0.06, k % 2 ? 0.3 : -0.3));
  vibes(t0 + BAR, 88, BEAT, 0.4, 0.2);
}
// A little vinyl: a low hiss and a few soft crackles under everything.
{
  const n = S.N, hiss = S.noise(n, 77);
  biquad(hiss, "bandpass", 3000, 0.4);
  for (let i = 0; i < n; i++) hiss[i] *= 0.12;
  for (let k = 0; k < LENGTH * 1.5; k++) {
    const i = Math.floor(S.rnd() * (n - 200)), a = (S.rnd() * 0.5 + 0.1);
    for (let j = 0; j < 60; j++) hiss[i + j] += a * Math.exp(-j / 8) * (j % 2 ? -1 : 1);
  }
  S.add(0, hiss, 0.012, 0, 0);
}

S.master(file ?? "build/trailer/cafe.wav", { wet: 0.75, room: 0.84, damp: 0.6, lowpass: 11000, drive: 1.15 });
console.log(`${file ?? "build/trailer/cafe.wav"}: ${LENGTH.toFixed(2)} s at ${BPM} BPM`);
