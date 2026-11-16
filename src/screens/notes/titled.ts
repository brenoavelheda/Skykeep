import type { Run } from "./Formatted";

/** A new note's first line set as its title, as Notes sets it, unless it was given a style. */
export function titled(text: string, runs: Run[]): Run[] {
  const end = text.indexOf("\n");
  const firstLine = end < 0 ? text.length : end + 1;
  const out: Run[] = [];
  let at = 0;
  for (const r of runs) {
    const from = at;
    at += r.length;
    if (from >= firstLine || r.style !== null) { out.push(r); continue; }
    const inside = Math.min(at, firstLine) - from;
    out.push({ ...r, length: inside, style: 0 });
    if (r.length > inside) out.push({ ...r, length: r.length - inside });
  }
  return out;
}
