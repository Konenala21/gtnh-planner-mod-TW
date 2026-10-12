// Rearranges a track by its bars: plays the given spans of bars one after another, so an approved track can follow a
// re-ordered or longer edit without its sound changing. Each span starts at full level (its downbeat keeps its
// attack, after a 2 ms rise so it never starts with a step); the span before it rings on under it for 15 ms, fading.
//
//   node tools/trailer/splice.mjs in.wav out.wav <bpm> <spans>
// spans: from-to bar pairs, "end" for the end of the file, e.g. 0-2,18-38,2-18,29-34,2-14,38-end

import { readFileSync, writeFileSync } from "node:fs";

const [inp, outp, bpm, spec] = process.argv.slice(2);
const buf = readFileSync(inp);
let pos = 12, fmt = null, data = null;
while (pos < buf.length) {
  const id = buf.toString("ascii", pos, pos + 4), size = buf.readUInt32LE(pos + 4);
  if (id === "fmt ") fmt = { ch: buf.readUInt16LE(pos + 10), sr: buf.readUInt32LE(pos + 12), bits: buf.readUInt16LE(pos + 22) };
  if (id === "data") data = { start: pos + 8, size };
  pos += 8 + size + (size % 2);
}
if (!fmt || !data || fmt.bits !== 16) throw new Error("need a 16-bit PCM WAV");
const frameBytes = fmt.ch * 2, frames = Math.floor(Math.min(data.size, buf.length - data.start) / frameBytes);
const bar = (60 / Number(bpm)) * 4 * fmt.sr;
const f = (b) => (b === "end" ? frames : Math.min(frames, Math.round(Number(b) * bar)));
const sample = (i, c) => buf.readInt16LE(data.start + i * frameBytes + c * 2);
const spans = spec.split(",").map((s) => s.split("-").map(f));

const fade = Math.round(0.015 * fmt.sr), rise = Math.round(0.002 * fmt.sr);
const out = [], tails = [];
spans.forEach(([a, b], k) => {
  for (let i = a; i < b; i++) {
    const g = k > 0 && i - a < rise ? (i - a) / rise : 1;
    const row = [];
    for (let c = 0; c < fmt.ch; c++) row.push(sample(i, c) * g);
    out.push(row);
  }
  if (k < spans.length - 1) for (let j = 0; j < fade && b + j < frames; j++) {
    tails.push({ at: out.length + j, row: Array.from({ length: fmt.ch }, (_, c) => sample(b + j, c) * (1 - j / fade)) });
  }
});
for (const t of tails) if (out[t.at]) for (let c = 0; c < fmt.ch; c++) out[t.at][c] += t.row[c];

const n = out.length, w = Buffer.alloc(44 + n * frameBytes);
w.write("RIFF", 0);
w.writeUInt32LE(36 + n * frameBytes, 4);
w.write("WAVEfmt ", 8);
w.writeUInt32LE(16, 16);
w.writeUInt16LE(1, 20);
w.writeUInt16LE(fmt.ch, 22);
w.writeUInt32LE(fmt.sr, 24);
w.writeUInt32LE(fmt.sr * frameBytes, 28);
w.writeUInt16LE(frameBytes, 32);
w.writeUInt16LE(16, 34);
w.write("data", 36);
w.writeUInt32LE(n * frameBytes, 40);
for (let i = 0; i < n; i++) {
  for (let c = 0; c < fmt.ch; c++) w.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(out[i][c]))), 44 + i * frameBytes + c * 2);
}
writeFileSync(outp, w);
console.log(`${outp}: ${(n / fmt.sr).toFixed(2)} s`);
