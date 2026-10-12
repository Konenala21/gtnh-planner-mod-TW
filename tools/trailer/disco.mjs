// The trailer's music, nu-disco: four on the floor, claps on two and four, open hats on the offbeats, an octave bass,
// filtered chord stabs on the "and"s, a muted guitar chopping sixteenths, string runs into each turn; the tutorial half
// thins out to the kick, the bass and the strings; it ends on one stab left ringing.
//
//   node tools/trailer/disco.mjs build/trailer/disco.wav [--demo]
//
// 112 BPM, a bar is 2.142857 s, sixteen sixteenths a bar.

import { studio, layout } from "./kit.mjs";

const BPM = 112, BEAT = 60 / BPM, BAR = BEAT * 4, SIX = BEAT / 4;
const { sections, bars, file } = layout(process.argv.slice(2));
const S = studio(bars * BAR + 3, 1979);
const sectionAt = (bar) => sections.find((s) => bar >= s.from && bar < s.to);
const step = (bar, s) => bar * BAR + s * SIX + (s % 2 ? 0.08 * SIX : 0);

// Am9 for two bars, Dm9, then G6 turning it round.
const CHORDS = [
  { bass: 45, stab: [60, 64, 67, 71], walk: 43 }, // Am9 (C E G B over A)
  { bass: 45, stab: [60, 64, 67, 71], walk: 43 },
  { bass: 38, stab: [60, 64, 65, 69], walk: 40 }, // Dm9 (F A C E over D)
  { bass: 43, stab: [59, 62, 64, 67], walk: 44 }, // G6 (B D E G)
];
const RUN = [69, 71, 72, 74, 76, 77, 79, 81]; // up the A minor scale into the next turn

for (let bar = 0; bar < bars; bar++) {
  const sec = sectionAt(bar);
  if (sec.kind === "end") break;
  const pos = bar - sec.from, ch = CHORDS[pos % 4];
  const band = sec.kind === "band", easy = sec.kind === "easy", intro = sec.kind === "intro";
  if (pos === 0 && !intro) S.crash(step(bar, 0), easy ? 0.6 : 0.9);
  // Drums: the kick on every beat (from the second intro bar), claps on two and four, open hats between.
  if (!(intro && bar === 0)) for (const s of [0, 4, 8, 12]) S.kick(step(bar, s), easy ? 0.8 : 1, { low: 50, top: 75, decay: 0.18 });
  if (!intro) for (const s of easy ? [12] : [4, 12]) S.clap(step(bar, s), easy ? 0.7 : 1);
  if (!easy) for (const s of [2, 6, 10, 14]) S.hat(step(bar, s), 0.8, true, { openDecay: 0.09, gain: 0.06, pan: 0.25 });
  for (let s = 0; s < 16; s++) if (s % 2 || easy) S.hat(step(bar, s), s % 4 === 1 ? 0.6 : 0.45, false, { gain: 0.045 });
  // The octave bass on the eighths, a walk into the next chord at the end of the bar.
  if (!(intro && bar === 0)) for (let s = 0; s < 16; s += 2) {
    const m = s === 14 ? ch.walk : ch.bass + ((s / 2) % 2 ? 12 : 0);
    S.tone(step(bar, s), m, SIX * 1.4, {
      wave: "square", cut: 420, env: 1500, envTau: 0.05, poles: 2, decay: 0.1, sustain: 0.45, release: 0.02,
      gain: 0.22 * (easy ? 0.85 : 1), send: 0,
    });
  }
  // Chord stabs on the "and"s, filtered open on the attack.
  if (!easy) for (const s of [2, 6, 10, 14]) S.chord(step(bar, s), ch.stab, SIX * 1.1, {
    wave: "saw", detune: [-7, 7], cut: 900, env: 2600, envTau: 0.05, poles: 2, decay: 0.1, sustain: 0.3, release: 0.06,
    gain: 0.075 * (intro ? 0.8 : 1), pan: -0.1, send: 0.25,
  });
  // A muted guitar chopping the top of the chord.
  if (band) for (const s of [1, 3, 5, 9, 11, 13]) S.chord(step(bar, s), ch.stab.slice(2).map((m) => m + 12), SIX * 0.5, {
    wave: "pulse", duty: 0.3, cut: 1200, env: 2400, envTau: 0.015, poles: 2, decay: 0.03, sustain: 0, release: 0.02,
    gain: 0.03, pan: 0.45, send: 0.08,
  });
  // Strings: a soft bed under the tutorial, a run up into every fourth bar's turn through the band.
  if (easy) S.chord(step(bar, 0), ch.stab, BAR * 0.98, { wave: "saw", detune: [-10, 0, 10], cut: 2400, attack: 0.3, decay: 1, sustain: 0.9, release: 0.4, gain: 0.05, send: 0.45 });
  if (band && pos % 4 === 3) RUN.forEach((m, k) => S.tone(step(bar, 8 + k), m, SIX * (k === 7 ? 3 : 0.9), {
    wave: "saw", detune: [-9, 0, 9], cut: 3500, attack: 0.01, decay: 0.2, sustain: 0.8, release: 0.1, gain: 0.035, pan: 0.2, send: 0.4,
  }));
}
// The end: one Am9 stab on everything, the strings left ringing.
{
  const t0 = sections.find((s) => s.kind === "end").from * BAR, fin = [60, 64, 67, 71, 76];
  S.kick(t0, 1, { low: 50, top: 75, decay: 0.3 });
  S.clap(t0, 0.8);
  S.crash(t0, 1);
  S.tone(t0, 45, BAR * 0.9, { wave: "square", cut: 500, env: 1200, poles: 2, decay: 0.4, sustain: 0.4, release: 0.3, gain: 0.22 });
  S.chord(t0, fin, BAR * 1.3, { wave: "saw", detune: [-10, 0, 10], cut: 2600, attack: 0.01, decay: 1.2, sustain: 0.5, release: 0.8, gain: 0.08, send: 0.5 });
}

S.master(file ?? "build/trailer/disco.wav", { wet: 0.5, drive: 1.35 });
console.log(`${file ?? "build/trailer/disco.wav"}: ${(bars * BAR + 3).toFixed(2)} s`);
