// The trailer's music, a nice café: bossa nova by way of a jazz trio. A nylon guitar strums the chords in the bossa
// rhythm and carries a melody that keeps coming back, the electric piano answers it, an upright bass walks between
// roots with grace notes, brushes swirl with a soft kick under them. The band steps out now and then (under the calm
// shots) and leaves the guitar alone; the tutorial half sits back; it ends on one strum left ringing.
//
//   node tools/trailer/cafe.mjs build/trailer/cafe.wav [--demo] [--bpm=N]
//
// Cut to 112 BPM (a bar is 2.142857 s). The strings are plucked-string models (Karplus-Strong), not oscillators.

import { studio, layout, mtof, SR, biquad } from "./kit.mjs";

const ARGS = process.argv.slice(2);
const BPM = Number((ARGS.find((a) => a.startsWith("--bpm=")) ?? "--bpm=112").slice(6));
const BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4, SWING = 0.1;
const { sections, bars, file } = layout(ARGS);
const LENGTH = bars * BAR + 3;
const S = studio(LENGTH, 1962);
const sectionAt = (bar) => sections.find((s) => bar >= s.from && bar < s.to);
const step = (bar, s, ms = 7) => bar * BAR + s * SIX + (s % 2 ? SWING * SIX : 0) + (S.rnd() - 0.5) * 2 * (ms / 1000);
const at = (t) => Math.round(t * SR);

// region Plucked strings

/**
 * A plucked string: a delay line a period long fed back through a gentle low-pass (the string losing its top) and an
 * all-pass (the period's fraction, so it is in tune), excited by a burst of filtered noise with the pick's position
 * notched out of it. After the note's length the fingers damp it.
 */
function string(midi, dur, o = {}) {
  const f = mtof(midi) * (1 + ((S.rnd() - 0.5) * (o.drift ?? 4)) / 1730); // a few cents out, as real strings are
  const n = at(dur + (o.ring ?? 1.2)), out = new Float32Array(n);
  const period = SR / f - 0.5, M = Math.floor(period), frac = period - M;
  const c = (1 - frac) / (1 + frac);
  const d = new Float32Array(M);
  // The pluck: noise, darker for a softer finger, the pick position combed out.
  const burst = S.noise(M, at(S.rnd() * 100) + midi);
  biquad(burst, "lowpass", o.finger ?? 2600, 0.6);
  const pick = Math.max(1, Math.round(M * (o.pick ?? 0.18)));
  for (let i = 0; i < M; i++) d[i] = burst[i] - 0.9 * burst[(i + pick) % M];
  let idx = 0, prev = 0, apx = 0, apy = 0;
  const bright = o.bright ?? 0.5, loss = o.loss ?? 0.996, damp = o.damp ?? 0.9;
  for (let i = 0; i < n; i++) {
    const s = d[idx];
    out[i] = s;
    const held = i < at(dur);
    const lp = ((1 - bright) * s + bright * prev) * (held ? loss : damp);
    prev = s;
    const y = c * lp + apx - c * apy; // the fractional delay
    apx = lp;
    apy = y;
    d[idx] = y;
    idx = (idx + 1) % M;
  }
  return out;
}

/** The nylon guitar: a string through a small body (a resonance low, a softer top), its attack slightly blunted. */
function guitar(t, midi, dur, vel = 1, pan = 0.2, send = 0.25) {
  const b = string(midi, dur, { finger: 1800 + 1400 * vel, pick: 0.13 + S.rnd() * 0.06, bright: 0.48, loss: 0.9965, ring: 1.4 });
  const body = Float32Array.from(b);
  biquad(body, "bandpass", 210, 1.4);
  for (let i = 0; i < b.length; i++) b[i] = b[i] + body[i] * 0.6;
  biquad(b, "lowpass", 5200, 0.7);
  for (let i = 0; i < Math.min(b.length, at(0.004)); i++) b[i] *= i / at(0.004);
  S.add(at(t), b, 0.16 * vel, pan, send);
}

/** A strum: the chord's strings a few milliseconds apart, down (low to high) or up, the top string a touch louder. */
function strum(t, notes, dur, vel = 1, up = false, pan = 0.25) {
  const order = up ? [...notes].reverse() : notes, gap = up ? 0.009 : 0.014;
  order.forEach((m, k) => guitar(t + k * gap, m, dur - k * gap, vel * (0.75 + 0.25 * (k / (order.length - 1 || 1))) / Math.sqrt(notes.length) * 1.6, pan, 0.22));
}

/** The upright: a long string, plucked soft and deep, the finger's thump on the front of it. */
function upright(t, midi, dur, vel = 1) {
  const b = string(midi, dur, { finger: 900, pick: 0.28, bright: 0.55, loss: 0.9975, damp: 0.75, ring: 0.25, drift: 2 });
  biquad(b, "lowpass", 1100, 0.7);
  const thump = S.noise(at(0.03), at(t) + 31);
  biquad(thump, "lowpass", 300, 0.7);
  for (let i = 0; i < thump.length; i++) b[i] += thump[i] * 0.8 * Math.exp(-i / at(0.008));
  S.add(at(t), b, 0.6 * vel, -0.05, 0.04);
}

// endregion

// region Keys and drums

/**
 * The electric piano: a tine (a sine bending its own phase a little, the bark softening as it rings) through a pickup
 * that warms it unevenly, two copies a few cents apart for width, a hammer's soft knock.
 */
function piano(t, notes, dur, vel = 1) {
  const n = at(dur + 1.2), L = new Float32Array(n), R = new Float32Array(n);
  for (const m of notes) {
    for (const [side, cents] of [[L, -3], [R, 3]]) {
      const f = mtof(m) * Math.pow(2, cents / 1200);
      let pc = S.rnd(), pm = S.rnd();
      for (let i = 0; i < n; i++) {
        const tt = i / SR;
        pc += f / SR;
        pm += f / SR;
        const index = (0.9 * vel + 0.3) * Math.exp(-tt / 0.35) + 0.12;
        const env = Math.min(1, tt / 0.006) * (0.7 * Math.exp(-tt / 1.8) + 0.3 * Math.exp(-tt / 0.4)) * (tt < dur ? 1 : Math.exp(-(tt - dur) / 0.25));
        const x = Math.sin(2 * Math.PI * pc + index * Math.sin(2 * Math.PI * pm)) * env;
        side[i] += x + 0.22 * x * x - 0.08 * x * x * x; // the pickup
      }
    }
  }
  for (const c of [L, R]) {
    biquad(c, "highpass", 60, 0.7);
    biquad(c, "lowpass", 3000, 0.6);
  }
  const g = (0.075 * vel) / Math.sqrt(notes.length);
  S.add(at(t), L, g, -0.7, 0.3);
  S.add(at(t), R, g, 0.7, 0.3);
}

/** Brushes: a swirl of soft noise round the beat, a tap on two and four, nothing sharp. */
function swirl(t, len, vel = 1) {
  const n = at(len), b = S.noise(n, at(t) + 41);
  biquad(b, "bandpass", 2200, 0.5);
  for (let i = 0; i < n; i++) {
    const u = i / n;
    b[i] *= Math.sin(Math.PI * u) * (0.6 + 0.4 * Math.sin(2 * Math.PI * 2 * u));
  }
  S.add(at(t), b, 0.035 * vel, -0.15, 0.15);
}

function tap(t, vel = 1) {
  const n = at(0.25), b = S.noise(n, at(t) + 43);
  biquad(b, "lowpass", 3200, 0.7);
  for (let i = 0; i < n; i++) {
    const tt = i / SR;
    b[i] = b[i] * Math.min(1, tt / 0.004) * Math.exp(-tt / 0.08) + Math.sin(2 * Math.PI * 180 * tt) * 0.4 * Math.exp(-tt / 0.03);
  }
  S.add(at(t), b, 0.11 * vel, 0.1, 0.2);
}

const kick = (t, v) => S.kick(t, v, { low: 52, top: 45, decay: 0.18, drive: 1.05, click: 0, gain: 0.38 });

// endregion

// region The tune

// IV, iii, ii, then I turning through A7 back to IV, in C. The guitar's voicings sit in its middle.
const CHANGES = [
  { root: 41, guitar: [53, 57, 64, 67], keys: [57, 60, 64, 67] }, // Fmaj9
  { root: 40, guitar: [52, 55, 62, 66], keys: [55, 59, 62, 66] }, // Em9
  { root: 38, guitar: [50, 53, 60, 64], keys: [53, 57, 60, 64] }, // Dm9
  { root: 36, guitar: [48, 55, 59, 62], keys: [55, 59, 62, 64], turn: { root: 45, guitar: [49, 55, 60, 64], keys: [55, 60, 61, 67] } }, // Cmaj9, A7#9
];
// The bossa: the chord on these sixteenths, alternately down and up strokes.
const BOSSA = [[0, 1], [3, 0.7], [6, 0.85], [10, 0.8], [13, 0.7]];
// The melody, four bars: [bar, beat, length in beats, note]. It comes back, and the piano answers it lower.
const MELODY = [
  [0, 0.5, 0.5, 76], [0, 1, 1, 79], [0, 2.5, 0.5, 81], [0, 3, 1, 79],
  [1, 0, 1.5, 71], [1, 1.5, 0.5, 74], [1, 2, 2, 76],
  [2, 0.5, 0.5, 77], [2, 1, 0.5, 76], [2, 1.5, 0.5, 74], [2, 2, 1, 72], [2, 3, 1, 69],
  [3, 0, 2, 71], [3, 2.5, 0.5, 73], [3, 3, 1, 76],
];
// What the guitar plays alone in a break: the chord picked slowly, then a falling line.
const BREAK = [[0, 0], [0.5, 1], [1, 2], [1.5, 3], [2.5, 2], [3, 1]];

const BREAKS = new Set(bars > 20 ? [7, 8, 16, 17, 23, 29, 34, 35] : [6, 7]);

function melody(bar0, which, vel, octave = 0, inst = "guitar") {
  for (const [b, beat, len, m] of MELODY) {
    if (Math.floor(b / 2) !== which && which >= 0) continue;
    const t = (bar0 + b) * BAR + beat * BEAT + (beat % 1 ? SWING * SIX * 2 : 0) + (S.rnd() - 0.5) * 0.01;
    if (inst === "guitar") guitar(t, m + octave, len * BEAT * 0.95, vel, 0.3, 0.3);
    else piano(t, [m + octave], len * BEAT * 0.95, vel);
  }
}

for (let bar = 0; bar < bars; bar++) {
  const sec = sectionAt(bar);
  if (sec.kind === "end") break;
  const pos = bar - sec.from, ch = CHANGES[pos % 4];
  const easy = sec.kind === "easy", intro = sec.kind === "intro", band = sec.kind === "band";
  const brk = BREAKS.has(bar), before = BREAKS.has(bar + 1) && !brk, soft = easy ? 0.8 : 1;
  const chord = (s) => (ch.turn && s >= 12 && !intro ? ch.turn : ch);

  if (brk) {
    // Alone: the guitar picks the chord slowly and lets it ring; the piano holds it under, far back.
    for (const [beat, k] of BREAK) guitar(bar * BAR + beat * BEAT + (beat % 1 ? SWING * SIX * 2 : 0), ch.guitar[k] + 12 * (k === 3 ? 0 : 0), BEAT * 2.5, 0.7, -0.2, 0.35);
    piano(step(bar, 0), ch.keys, BAR * 0.95, 0.45);
    continue;
  }
  // The guitar's bossa, every bar.
  BOSSA.forEach(([s, v], k) => strum(step(bar, s), chord(s).guitar, SIX * (k === BOSSA.length - 1 ? 3 : 2.6), v * (intro ? 0.85 : 1) * soft, k % 2 === 1));
  // The piano: a soft chord on one through the band, answering the melody when it is the piano's turn.
  if (!intro) piano(step(bar, 0), ch.keys, BEAT * 1.8, 0.5 * soft);
  // The bass from the second bar: root, a grace note into the fifth, the fifth, a step toward the next root.
  if (bar >= 1) {
    const next = CHANGES[(pos + 1) % 4].root;
    upright(step(bar, 0, 4), ch.root, BEAT * 1.4, soft);
    if (!easy) upright(step(bar, 7, 4), ch.root + 6, SIX * 0.6, 0.45);
    upright(step(bar, 8, 4), ch.root + 7, BEAT * 1.3, 0.85 * soft);
    if (ch.turn) upright(step(bar, 12, 4), ch.turn.root, BEAT * 0.9, 0.8 * soft);
    else if (!before) upright(step(bar, 14, 4), next + (next > ch.root ? -1 : 1), SIX * 1.6, 0.6 * soft);
  }
  // Brushes from the second bar: a swirl on every beat, a tap on two and four, the kick soft on one and the "and" of two.
  if (bar >= 1) {
    for (let b = 0; b < (before ? 2 : 4); b++) swirl(bar * BAR + b * BEAT, BEAT * 1.1, soft);
    for (const s of [4, 12]) if (!(before && s === 12)) tap(step(bar, s, 4), 0.9 * soft);
    for (const s of easy ? [0] : [0, 6]) kick(step(bar, s, 3), s === 0 ? 0.8 : 0.55);
  }
  // The melody: the guitar over the band in four-bar turns from the third bar, the piano answering an octave lower;
  // under the tutorial the piano alone, once in a while.
  if (band && pos >= 1 && (pos - 1) % 8 === 0) melody(bar, -1, 0.95);
  if (band && pos >= 1 && (pos - 1) % 8 === 4 && bar + 3 < 16) melody(bar, -1, 0.7, -12, "piano");
  if (easy && pos % 8 === 1) melody(bar, -1, 0.55, -12, "piano");
}
// The end: one strum left ringing, the bass's low C, the piano under it; a bar later a single high harmonic, the button.
{
  const t0 = sections.find((s) => s.kind === "end").from * BAR;
  strum(t0, [48, 55, 59, 64, 69], BAR * 2, 1.1);
  upright(t0, 36, BAR * 1.5, 1);
  piano(t0, [52, 55, 59, 62], BAR * 1.8, 0.6);
  kick(t0, 0.7);
  guitar(t0 + BAR, 88, BEAT * 2, 0.6, 0.3, 0.4);
}

// endregion

S.master(file ?? "build/trailer/cafe.wav", { wet: 0.6, room: 0.82, damp: 0.55, lowpass: 12000, drive: 1.2 });
console.log(`${file ?? "build/trailer/cafe.wav"}: ${LENGTH.toFixed(2)} s at ${BPM} BPM`);
