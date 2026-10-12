// The trailer's music, chiptune: a game console's voices played as a groove. Pulse-wave arpeggios for the chords, a
// stepped triangle bass bouncing in octaves, a square lead with its own echo, noise drums; the tutorial half drops the
// lead and lightens the drums; it ends on one bright chord.
//
//   node tools/trailer/chip.mjs build/trailer/chip.wav [--demo]
//
// 112 BPM, a bar is 2.142857 s, sixteen sixteenths a bar.

import { studio, layout } from "./kit.mjs";

const BPM = 112, BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4;
const { sections, bars, file } = layout(process.argv.slice(2));
const S = studio(bars * BAR + 3, 1985);
const sectionAt = (bar) => sections.find((s) => bar >= s.from && bar < s.to);
const step = (bar, s) => bar * BAR + s * SIX;

// IV, V, iii, vi in C: [root for the bass, the arpeggio's notes].
const CHORDS = [
  { bass: 41, arp: [65, 69, 72, 77] }, // F
  { bass: 43, arp: [67, 71, 74, 79] }, // G
  { bass: 40, arp: [64, 67, 71, 76] }, // Em
  { bass: 45, arp: [69, 72, 76, 81] }, // Am
];
// The lead over four bars: [sixteenth, note, length in sixteenths].
const MELODY = [[0, 72, 2], [2, 74, 2], [4, 77, 4], [8, 76, 2], [10, 74, 2], [12, 72, 4], [16, 74, 2], [18, 76, 2],
  [20, 79, 6], [28, 76, 2], [30, 74, 2], [32, 76, 2], [34, 79, 2], [36, 83, 4], [40, 81, 2], [42, 79, 2], [44, 76, 4],
  [48, 77, 2], [50, 76, 2], [52, 74, 2], [54, 72, 2], [56, 69, 8]];

const kick = (t, v) => S.kick(t, v, { square: true, low: 52, top: 190, sweep: 0.018, decay: 0.09, click: 0, gain: 0.32 });
const snare = (t, v) => S.snare(t, v, { tone: 230, body: 0.35, bright: 3200, decay: 0.06, len: 0.12, send: 0.02 });
const hat = (t, v) => S.hat(t, v, false, { cut: 9000, decay: 0.008, gain: 0.06 });

for (let bar = 0; bar < bars; bar++) {
  const sec = sectionAt(bar);
  if (sec.kind === "end") break;
  const pos = bar - sec.from, ch = CHORDS[pos % 4];
  const band = sec.kind === "band", easy = sec.kind === "easy", intro = sec.kind === "intro";
  // The arpeggio: a short pulse chord on every eighth, cycling its notes fast, the way old consoles played chords.
  for (let s = 0; s < 16; s += 2) {
    const accent = s % 4 === 0 ? 1 : 0.7;
    S.tone(step(bar, s), ch.arp[0], SIX * 1.6, {
      wave: "pulse", duty: 0.25, arp: ch.arp.map((m) => m - ch.arp[0]), arpRate: 32,
      decay: 0.08, sustain: 0.35, release: 0.02, gain: 0.03 * accent * (easy ? 0.7 : intro ? 0.85 : 1), pan: -0.2,
      send: 0.08,
    });
  }
  // The bass from the second bar: octaves on the eighths, a step up into the next chord on the last.
  if (!(intro && bar === 0)) for (let s = 0; s < 16; s += 2) {
    const up = (s / 2) % 2 === 1, last = s === 14 && !easy;
    const m = last ? ch.bass + 2 : ch.bass + (up && !easy ? 12 : 0);
    if (easy && s % 4 !== 0) continue;
    S.tone(step(bar, s), m, SIX * (easy ? 3.6 : 1.7), { wave: "tri", decay: 0.2, sustain: 0.7, release: 0.02, gain: 0.2, send: 0 });
  }
  // Drums: from the second intro bar; a fill into each new section.
  if (!(intro && bar === 0)) {
    for (const s of easy ? [0, 8] : [0, 8, ...(bar % 2 ? [10] : [])]) kick(step(bar, s), 1);
    for (const s of [4, 12]) snare(step(bar, s), easy ? 0.6 : 0.9);
    for (let s = 0; s < 16; s += easy ? 4 : 2) hat(step(bar, s), s % 4 === 0 ? 0.9 : 0.6);
    if (bar === sec.to - 1 && !easy) for (const s of [13, 14, 15]) snare(step(bar, s), 0.5 + (s - 13) * 0.2);
  }
  // The lead over the band, every four bars from the third, with its echo three sixteenths behind.
  if (band && pos >= 2 && pos % 4 === 2) {
    for (const [s, m, len] of MELODY) {
      const t = bar * BAR + s * SIX;
      const o = { wave: "pulse", duty: 0.5, vib: 0.006, vibRate: 6, decay: 0.25, sustain: 0.6, release: 0.03 };
      S.tone(t, m, len * SIX * 0.85, { ...o, gain: 0.045, pan: 0.15, send: 0.12 });
      S.tone(t + 3 * SIX, m, len * SIX * 0.85, { ...o, gain: 0.016, pan: -0.4, send: 0.12 });
    }
  }
  if (easy && pos === 12) for (const [s, m, len] of MELODY) {
    S.tone(bar * BAR + s * SIX, m, len * SIX * 0.85, { wave: "pulse", duty: 0.125, decay: 0.25, sustain: 0.5, gain: 0.025, pan: 0.15, send: 0.15 });
  }
}
// The end: one bright C chord, arpeggiated slower as it rings, the bass's low C, a noise crash.
{
  const t0 = sections.find((s) => s.kind === "end").from * BAR;
  S.tone(t0, 72, BAR * 1.3, { wave: "pulse", duty: 0.25, arp: [0, 4, 7, 12], arpRate: 24, decay: 0.9, sustain: 0.3, release: 0.4, gain: 0.04, send: 0.2 });
  S.tone(t0, 48, BAR * 1.0, { wave: "tri", decay: 0.6, sustain: 0.4, release: 0.3, gain: 0.22 });
  kick(t0, 1);
  S.crash(t0, 0.8, { decay: 0.5 });
}

S.master(file ?? "build/trailer/chip.wav", { wet: 0.35, lowpass: 9500, drive: 1.2 });
console.log(`${file ?? "build/trailer/chip.wav"}: ${(bars * BAR + 3).toFixed(2)} s`);
