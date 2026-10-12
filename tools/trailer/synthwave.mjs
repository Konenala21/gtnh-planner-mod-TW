// The trailer's music, synthwave: a galloping saw bass, a plucked sixteenth arpeggio with a dotted echo, wide detuned
// pads, a big roomy snare on two and four and a gliding lead over the time-lapses; the tutorial half keeps the pads
// and the bass and pulls the drums back; it ends on a held chord.
//
//   node tools/trailer/synthwave.mjs build/trailer/synthwave.wav [--demo]
//
// 112 BPM, a bar is 2.142857 s, sixteen sixteenths a bar.

import { studio, layout } from "./kit.mjs";

const BPM = 112, BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4;
const { sections, bars, file } = layout(process.argv.slice(2));
const S = studio(bars * BAR + 3, 1984);
const sectionAt = (bar) => sections.find((s) => bar >= s.from && bar < s.to);
const step = (bar, s) => bar * BAR + s * SIX;

// i, VI, III, VII in A minor.
const CHORDS = [
  { bass: 45, pad: [57, 60, 64, 69], arp: [69, 72, 76, 81] }, // Am
  { bass: 41, pad: [57, 60, 65, 69], arp: [65, 69, 72, 77] }, // F
  { bass: 48, pad: [55, 60, 64, 67], arp: [67, 72, 76, 79] }, // C
  { bass: 43, pad: [55, 59, 62, 67], arp: [67, 71, 74, 79] }, // G
];
const MELODY = [[0, 76, 6], [6, 74, 2], [8, 72, 4], [12, 69, 4], [16, 72, 6], [22, 74, 2], [24, 76, 8], [32, 79, 6],
  [38, 77, 2], [40, 76, 4], [44, 72, 4], [48, 74, 6], [54, 72, 2], [56, 71, 8]];

for (let bar = 0; bar < bars; bar++) {
  const sec = sectionAt(bar);
  if (sec.kind === "end") break;
  const pos = bar - sec.from, ch = CHORDS[pos % 4];
  const band = sec.kind === "band", easy = sec.kind === "easy", intro = sec.kind === "intro";
  if (pos === 0 && !intro) S.crash(step(bar, 0), easy ? 0.6 : 0.9);
  // The pad: wide, swelling, a bar long.
  S.chord(step(bar, 0), ch.pad, BAR * 0.98, {
    wave: "saw", detune: [-14, 0, 13], cut: 1800, attack: 0.25, decay: 1, sustain: 0.85, release: 0.45, gain: 0.07,
    pan: 0, send: 0.45,
  });
  // The arpeggio: sixteenths up and down the chord, plucked, with an echo a dotted eighth behind.
  if (!(intro && bar === 0) && !easy) for (let s = 0; s < 16; s++) {
    const m = ch.arp[[0, 1, 2, 3, 2, 1][s % 6]];
    const o = { wave: "saw", detune: [-6, 6], cut: 600, env: 3800, envTau: 0.05, poles: 2, decay: 0.07, sustain: 0, release: 0.03 };
    S.tone(step(bar, s), m, SIX * 0.9, { ...o, gain: 0.028 * (s % 4 === 0 ? 1 : 0.75), pan: s % 2 ? 0.35 : -0.35, send: 0.3 });
    S.tone(step(bar, s) + 3 * SIX, m, SIX * 0.9, { ...o, gain: 0.01, pan: s % 2 ? -0.5 : 0.5, send: 0.4 });
  }
  // The bass: a galloping saw on the root (eighth, two sixteenths), dark, a sub under it.
  if (!(intro && bar === 0)) for (const s of easy ? [0, 2, 4, 6, 8, 10, 12, 14] : [0, 2, 3, 4, 6, 7, 8, 10, 11, 12, 14, 15]) {
    const o = { wave: "saw", detune: [-5, 5], cut: 380, env: 1400, envTau: 0.05, poles: 2, decay: 0.12, sustain: 0.5, release: 0.02 };
    S.tone(step(bar, s), ch.bass, SIX * 0.8, { ...o, gain: 0.2 * (s % 4 === 0 ? 1 : 0.8) * (easy ? 0.8 : 1), send: 0 });
    S.tone(step(bar, s), ch.bass - 12, SIX * 0.8, { wave: "sine", decay: 0.2, sustain: 0.7, release: 0.02, gain: 0.12, send: 0 });
  }
  // Drums: four on the floor through the band, a big snare on two and four.
  if (!(intro && bar === 0)) {
    for (const s of easy || intro ? [0, 8] : [0, 4, 8, 12]) S.kick(step(bar, s), s % 8 === 0 ? 1 : 0.8, { low: 46, top: 70, decay: 0.22 });
    for (const s of [4, 12]) S.snare(step(bar, s), easy ? 0.6 : 1, { tone: 170, decay: 0.12, len: 0.4, bright: 1800, send: 0.55, gain: 0.22 });
    for (let s = 0; s < 16; s += easy ? 2 : 1) S.hat(step(bar, s), (s % 2 ? 0.45 : 0.75) * (easy ? 0.7 : 1), false, { gain: 0.05 });
    if (bar === sec.to - 1 && !easy) for (const s of [12, 13, 14, 15]) S.snare(step(bar, s), 0.4 + (s - 12) * 0.15, { tone: 170, send: 0.4 });
  }
  // The lead: gliding saws over the band, every four bars from the third.
  if ((band && pos >= 2 && pos % 4 === 2) || (easy && pos === 12)) {
    let prev = null;
    for (const [s, m, len] of MELODY) {
      S.tone(step(bar, s), m, len * SIX * 0.95, {
        wave: "saw", detune: [-8, 8], from: prev, glide: 0.04, vib: 0.005, cut: 2600, attack: 0.02, decay: 0.4,
        sustain: 0.8, release: 0.12, gain: easy ? 0.03 : 0.05, pan: 0.1, send: 0.4,
      });
      prev = m;
    }
  }
}
// The end: the pad on A minor, held, the bass and a crash.
{
  const t0 = sections.find((s) => s.kind === "end").from * BAR;
  S.chord(t0, [57, 60, 64, 71], BAR * 1.4, { wave: "saw", detune: [-14, 0, 13], cut: 2200, attack: 0.02, decay: 1.2, sustain: 0.6, release: 0.8, gain: 0.1, send: 0.5 });
  S.tone(t0, 45, BAR * 1.1, { wave: "saw", detune: [-5, 5], cut: 500, env: 1200, poles: 2, decay: 0.5, sustain: 0.4, release: 0.4, gain: 0.2 });
  S.kick(t0, 1, { low: 46, top: 70, decay: 0.3 });
  S.crash(t0, 1);
}

S.master(file ?? "build/trailer/synthwave.wav", { wet: 0.75, room: 0.86, drive: 1.25 });
console.log(`${file ?? "build/trailer/synthwave.wav"}: ${(bars * BAR + 3).toFixed(2)} s`);
