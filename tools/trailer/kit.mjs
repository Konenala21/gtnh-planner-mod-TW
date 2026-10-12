// A small studio for the trailer's music: a stereo mix and a reverb send, drums and one general synth voice, and the
// master (room, a soft clip, normalised, faded, written as a 16-bit WAV). The tracks (funk.mjs, chip.mjs,
// synthwave.mjs, disco.mjs) play their parts through it.

import { writeFileSync, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { SR, biquad, mulberry32 } from "../sound/engine.mjs";

export { SR, biquad, mulberry32 };

export const mtof = (m) => 440 * Math.pow(2, (m - 69) / 12);

/**
 * The bars a track plays: the full layout matching trailer.shot (intro, the band under the teaser, an easier feel
 * under the tutorial, the end), or with --demo a short piece to choose by (intro, eight bars of the band, the end).
 */
export function layout(argv) {
  const demo = argv.includes("--demo");
  const sections = demo
    ? [
      { from: 0, to: 2, kind: "intro" },
      { from: 2, to: 10, kind: "band" },
      { from: 10, to: 12, kind: "end" },
    ]
    : [
      { from: 0, to: 2, kind: "intro" },
      { from: 2, to: 18, kind: "band" },
      { from: 18, to: 38, kind: "easy" },
      { from: 38, to: 40, kind: "end" },
    ];
  const file = argv.find((a) => a.endsWith(".wav"));
  return { demo, sections, bars: sections[sections.length - 1].to, file };
}

export function studio(seconds, seed) {
  const N = Math.ceil(seconds * SR);
  const at = (t) => Math.round(t * SR);
  const rnd = mulberry32(seed);
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

  function noise(n, s) {
    const r = mulberry32(s), b = new Float32Array(n);
    for (let i = 0; i < n; i++) b[i] = r() * 2 - 1;
    return b;
  }

  // region Drums

  function kick(t, vel = 1, o = {}) {
    const n = at(o.len ?? 0.4), b = new Float32Array(n);
    let ph = 0;
    const low = o.low ?? 48, top = o.top ?? 85, sweep = o.sweep ?? 0.025, decay = o.decay ?? 0.2, drive = o.drive ?? 1.6;
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      ph += (2 * Math.PI * (low + top * Math.exp(-tt / sweep))) / SR;
      const w = o.square ? (Math.sin(ph) >= 0 ? 1 : -1) * 0.6 : Math.sin(ph);
      b[i] = Math.tanh(w * drive) * Math.exp(-tt / decay) * Math.min(1, tt / 0.002);
    }
    if (o.click !== 0) {
      const c = noise(at(0.006), at(t) + 1);
      biquad(c, "highpass", 2000, 0.7);
      for (let i = 0; i < c.length; i++) b[i] += c[i] * (o.click ?? 0.25) * (1 - i / c.length);
    }
    add(at(t), b, (o.gain ?? 0.42) * vel, 0, o.send ?? 0);
  }

  function snare(t, vel = 1, o = {}) {
    const ghost = vel < 0.5, len = o.len ?? (ghost ? 0.08 : 0.22), n = at(len), b = noise(n, at(t) + 3);
    biquad(b, "bandpass", o.bright ?? 2400, 0.8);
    let ph = 0;
    const tone = o.tone ?? 185, decay = o.decay ?? (ghost ? 0.025 : 0.07);
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      ph += (2 * Math.PI * (tone + 40 * Math.exp(-tt / 0.01))) / SR;
      const body = Math.sin(ph) * Math.exp(-tt / 0.045);
      b[i] = (b[i] * 1.4 * Math.exp(-tt / decay) + body * (o.body ?? 0.8)) * Math.min(1, tt / 0.001);
    }
    add(at(t), b, (o.gain ?? 0.2) * vel, o.pan ?? 0.05, o.send ?? (ghost ? 0.03 : 0.12));
  }

  /** A clap: three quick bursts of band-passed noise and a short tail. */
  function clap(t, vel = 1, o = {}) {
    const n = at(0.25), b = new Float32Array(n), src = noise(n, at(t) + 17);
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      let e = 0;
      for (const d of [0, 0.011, 0.022]) if (tt >= d) e = Math.max(e, Math.exp(-(tt - d) / 0.006));
      e = Math.max(e, tt >= 0.022 ? 0.5 * Math.exp(-(tt - 0.022) / 0.07) : 0);
      b[i] = src[i] * e;
    }
    biquad(b, "bandpass", 1300, 1.1);
    biquad(b, "highpass", 600, 0.7);
    add(at(t), b, (o.gain ?? 0.3) * vel, o.pan ?? -0.05, o.send ?? 0.2);
  }

  function hat(t, vel = 1, open = false, o = {}) {
    const n = at(open ? 0.32 : 0.05), b = noise(n, at(t) + 7);
    biquad(b, "highpass", o.cut ?? 7500, 0.8);
    for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / (open ? (o.openDecay ?? 0.12) : (o.decay ?? 0.014)));
    add(at(t), b, (o.gain ?? 0.07) * vel, o.pan ?? -0.3, 0.04);
  }

  function crash(t, vel = 1, o = {}) {
    const n = at(2.2), b = noise(n, at(t) + 11);
    biquad(b, "highpass", 4500, 0.6);
    for (let i = 0; i < n; i++) b[i] *= Math.exp(-i / SR / (o.decay ?? 0.7)) * Math.min(1, i / SR / 0.002);
    add(at(t), b, (o.gain ?? 0.09) * vel, 0.35, 0.25);
  }

  // endregion

  // region One synth voice

  /**
   * A note: oscillators (saw, square, pulse with a duty, a stepped triangle, sine; detuned copies), an envelope
   * (attack, a decay to the sustain level, a release after the length), a one-pole or two-pole low-pass whose cutoff
   * the envelope can open, a glide in from another note, a vibrato that comes in once the note is held, and an
   * arpeggio (semitone offsets cycled at a rate) for chiptune chords.
   */
  function tone(t, midi, dur, o = {}) {
    const rel = o.release ?? 0.05, n = at(dur + rel + 0.01), b = new Float32Array(n);
    const wave = o.wave ?? "saw", duty = o.duty ?? 0.5, detune = o.detune ?? [0];
    const f1 = mtof(midi), f0 = o.from == null ? f1 : mtof(o.from);
    const attack = o.attack ?? 0.005, decay = o.decay ?? 0.3, sus = o.sustain ?? 0.6;
    for (const cents of detune) {
      let ph = rnd();
      for (let i = 0; i < n; i++) {
        const tt = i / SR;
        let f = f1 + (f0 - f1) * Math.exp(-tt / (o.glide ?? 0.03));
        if (o.vib) f *= 1 + o.vib * Math.sin(2 * Math.PI * (o.vibRate ?? 5.5) * tt) * Math.min(1, Math.max(0, (tt - 0.15) / 0.2));
        if (o.arp) f *= Math.pow(2, o.arp[Math.floor(tt * (o.arpRate ?? 30)) % o.arp.length] / 12);
        f *= Math.pow(2, cents / 1200);
        ph = (ph + f / SR) % 1;
        let s;
        if (wave === "saw") s = 2 * ph - 1;
        else if (wave === "square") s = ph < 0.5 ? 1 : -1;
        else if (wave === "pulse") s = ph < duty ? 1 : -1;
        else if (wave === "tri") s = Math.round((1 - 4 * Math.abs(ph - 0.5)) * 7.5) / 7.5; // NES-style, sixteen steps
        else s = Math.sin(2 * Math.PI * ph);
        b[i] += s;
      }
    }
    // Filter and envelope.
    let lp = 0, lp2 = 0;
    const cut = o.cut ?? 20000, env = o.env ?? 0, envTau = o.envTau ?? 0.1, poles = o.poles ?? 1;
    for (let i = 0; i < n; i++) {
      const tt = i / SR;
      if (cut < 20000 || env > 0) {
        const fc = Math.min(18000, cut + env * Math.exp(-tt / envTau)), a = 1 - Math.exp((-2 * Math.PI * fc) / SR);
        lp += (b[i] - lp) * a;
        lp2 += (lp - lp2) * a;
        b[i] = poles === 2 ? lp2 : lp;
      }
      const a = tt < attack ? 0.5 - 0.5 * Math.cos((Math.PI * tt) / attack) : 1;
      const d = tt < attack ? 1 : sus + (1 - sus) * Math.exp(-(tt - attack) / decay);
      const r = tt < dur ? 1 : Math.exp(-(tt - dur) / Math.max(0.005, rel / 3));
      b[i] *= (a * d * r) / Math.sqrt(detune.length);
    }
    add(at(t), b, o.gain ?? 0.05, o.pan ?? 0, o.send ?? 0.1);
  }

  function chord(t, notes, dur, o = {}) {
    for (const m of notes) tone(t, m, dur, { ...o, gain: (o.gain ?? 0.05) / Math.sqrt(notes.length) });
  }

  // endregion

  // region Room and master

  function reverb(inp, out, spread, room, damp) {
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
        const bb = buf[idx], x = acc[i];
        acc[i] = -x + bb;
        buf[idx] = x + bb * 0.5;
        idx = (idx + 1) % d;
      }
    }
    for (let i = 0; i < N; i++) out[i] += acc[i];
  }

  /** The room, a soft clip, normalised to a peak, faded at the end, written as a 16-bit stereo WAV. */
  function master(file, o = {}) {
    const wet = bus();
    reverb(verb.L, wet.L, 0, o.room ?? 0.8, o.damp ?? 0.5);
    reverb(verb.R, wet.R, 23, o.room ?? 0.8, o.damp ?? 0.5);
    const L = new Float32Array(N), R = new Float32Array(N);
    for (let i = 0; i < N; i++) {
      L[i] = mix.L[i] + wet.L[i] * (o.wet ?? 0.6);
      R[i] = mix.R[i] + wet.R[i] * (o.wet ?? 0.6);
    }
    for (const c of [L, R]) {
      biquad(c, "highpass", 30, 0.7);
      if (o.lowpass) biquad(c, "lowpass", o.lowpass, 0.6);
    }
    let peak = 0;
    const drive = o.drive ?? 1.3;
    for (let i = 0; i < N; i++) {
      L[i] = Math.tanh(L[i] * drive) / drive;
      R[i] = Math.tanh(R[i] * drive) / drive;
      peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
    }
    const norm = 0.88 / peak, fade = at(o.fade ?? 2.5);
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
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, out);
  }

  // endregion

  return { N, at, rnd, add, noise, kick, snare, clap, hat, crash, tone, chord, master };
}
