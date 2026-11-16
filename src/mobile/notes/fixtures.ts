/**
 * Made-up notes for the fake host (dev-host.ts) and the tests: every kind of formatting, a
 * table, pictures, a drawing, a file, a picture Apple will not give, and a locked note. None
 * of it is anyone's: no real note is ever read here. Plain data — the dev server's config
 * reads `devPicture` too.
 */
import type { Run } from "../../screens/notes/Formatted";
import type { NoteAttachment } from "../../lib/ipc";

export type DevNote = {
  guid: string;
  folderGuid: string | null;
  text: string;
  runs: Run[];
  locked?: { password: string; hint: string | null };
};

const plain = (length: number, extra: Partial<Run> = {}): Run => ({
  length, style: null, indent: 0, checked: null, bold: false, italic: false, underline: false, strike: false, link: null, ...extra,
});

/** A note built line by line: each line's setting, its pieces with their marks, and the
 *  newline that carries the setting, merged as Apple merges runs. */
export function buildNote(lines: { set: Partial<Run>; pieces: [string, Partial<Run>][] }[]): { text: string; runs: Run[] } {
  let text = "";
  const runs: Run[] = [];
  const key = (r: Run) => JSON.stringify({ ...r, length: 0 });
  const push = (piece: string, run: Run) => {
    if (!piece) return;
    text += piece;
    const last = runs[runs.length - 1];
    if (last && !last.attachment && !run.attachment && key(last) === key(run)) last.length += piece.length;
    else runs.push({ ...run, length: piece.length });
  };
  lines.forEach(({ set, pieces }, i) => {
    for (const [piece, marks] of pieces) push(piece, plain(piece.length, { ...set, ...marks }));
    if (i < lines.length - 1) push("\n", plain(1, set));
  });
  return { text, runs };
}

const formatted = buildNote([
  { set: { style: 0 }, pieces: [["Weekend in Paraty", {}]] },
  { set: {}, pieces: [["Leave Friday after work, back ", {}], ["Sunday night", { bold: true }], [". Book the ", {}], ["pousada", { italic: true }], [" before ", {}], ["Wednesday", { underline: true }], [".", {}]] },
  { set: { style: 1 }, pieces: [["Packing", {}]] },
  { set: { style: 103, checked: true }, pieces: [["Swimsuit and towel", {}]] },
  { set: { style: 103, checked: false }, pieces: [["Sunscreen", {}]] },
  { set: { style: 103, checked: false }, pieces: [["Charger for the camera", {}]] },
  { set: { style: 2 }, pieces: [["Places", {}]] },
  { set: { style: 100 }, pieces: [["Centro Histórico at dusk", {}]] },
  { set: { style: 100, indent: 1 }, pieces: [["Igreja de Santa Rita", {}]] },
  { set: { style: 101 }, pieces: [["Trindade beach — ", {}], ["Cachadaço pool", { emphasis: 4 }]] },
  { set: { style: 102 }, pieces: [["Boat to the islands", {}]] },
  { set: { style: 102 }, pieces: [["Waterfall in the afternoon", {}]] },
  { set: { quote: true }, pieces: [["“Go slow on the cobblestones.” 😀", {}]] },
  { set: {}, pieces: [["Colours: ", {}], ["purple", { emphasis: 1 }], [" ", {}], ["pink", { emphasis: 2 }], [" ", {}], ["orange", { emphasis: 3 }], [" ", {}], ["blue", { emphasis: 5 }], [" and ", {}], ["struck", { strike: true }]] },
  { set: {}, pieces: [["Tide table: ", {}], ["tabuademares.com", { link: "https://tabuademares.com" }]] },
  { set: { style: 4 }, pieces: [["Gate code 4417#", {}]] },
  { set: {}, pieces: [["", {}]] },
]);

const media = buildNote([
  { set: { style: 0 }, pieces: [["Kitchen plan", {}]] },
  { set: {}, pieces: [["What we measured on Saturday:", {}]] },
  { set: {}, pieces: [["\ufffc", { attachment: "TABLE-1" }]] },
  { set: {}, pieces: [["The tiles we liked:", {}]] },
  { set: {}, pieces: [["\ufffc", { attachment: "IMG-1" }]] },
  { set: {}, pieces: [["Sketch of the island:", {}]] },
  { set: {}, pieces: [["\ufffc", { attachment: "DRAW-1" }]] },
  { set: {}, pieces: [["\ufffc", { attachment: "PDF-1" }]] },
  { set: {}, pieces: [["\ufffc", { attachment: "IMG-BROKEN" }]] },
  { set: {}, pieces: [["Ask about delivery.", {}]] },
]);

/** A note that ends with a picture, on a line of its own, as More > Add File leaves one. */
const picture = buildNote([
  { set: { style: 0 }, pieces: [["Bathroom", {}]] },
  { set: {}, pieces: [["The tiles we chose:", {}]] },
  { set: {}, pieces: [["￼", { attachment: "IMG-1" }]] },
]);

const secret = buildNote([
  { set: { style: 0 }, pieces: [["Wi-Fi and alarm", {}]] },
  { set: {}, pieces: [["Router: ", {}], ["skykeep-home", { bold: true }]] },
]);

/** An old note, as one from 2021 is: a scan of several pages, and a web link's card. */
const old = buildNote([
  { set: { style: 0 }, pieces: [["Papers", {}]] },
  { set: {}, pieces: [["The scan:", {}]] },
  { set: {}, pieces: [["\ufffc", { attachment: "SCAN-1" }]] },
  { set: {}, pieces: [["Tides for the boat:", {}]] },
  { set: {}, pieces: [["\ufffc", { attachment: "LINK-1" }]] },
]);

export const DEV_NOTES: DevNote[] = [
  { guid: "DEV-FORMATTED", folderGuid: null, ...formatted },
  { guid: "DEV-MEDIA", folderGuid: null, ...media },
  { guid: "DEV-PICTURE", folderGuid: null, ...picture },
  { guid: "DEV-LOCKED", folderGuid: null, ...secret, locked: { password: "paraty", hint: "Where we went in March" } },
  { guid: "DEV-OLD", folderGuid: null, ...old },
];

export const DEV_TABLES: Record<string, string[][]> = {
  "TABLE-1": [
    ["Wall", "Width", "Height"],
    ["Window side", "312 cm", "260 cm"],
    ["Door side", "288 cm", "260 cm"],
  ],
};

const info = (a: Partial<NoteAttachment> & Pick<NoteAttachment, "id" | "kind">): NoteAttachment => ({
  uti: "", filename: null, title: null, width: null, height: null, size: null, hasPreview: false, ...a,
});

export const DEV_ATTACHMENTS: Record<string, NoteAttachment> = {
  "TABLE-1": info({ id: "TABLE-1", kind: "table", uti: "com.apple.notes.table" }),
  "IMG-1": info({ id: "IMG-1", kind: "image", uti: "public.jpeg", filename: "Tiles.jpeg", width: 1600, height: 1000, size: 812_000 }),
  "DRAW-1": info({ id: "DRAW-1", kind: "drawing", uti: "com.apple.drawing.2", title: "Island", width: 800, height: 400 }),
  "PDF-1": info({ id: "PDF-1", kind: "document", uti: "com.adobe.pdf", filename: "Quote from the joiner.pdf", size: 184_000, hasPreview: true }),
  // A picture Apple will not give (a fetch that fails): drawn as its file's chip.
  "IMG-BROKEN": info({ id: "IMG-BROKEN", kind: "image", uti: "public.heic", filename: "IMG_2041.HEIC", width: 4032, height: 3024, size: 2_400_000 }),
  // An old scan: a gallery with no file, drawn as the pages it holds.
  "SCAN-1": info({
    id: "SCAN-1", kind: "scan", uti: "com.apple.notes.gallery", title: "Passport", hasPreview: true,
    pages: [
      { id: "SCAN-P1", width: 1240, height: 1754 },
      { id: "SCAN-P2", width: 1240, height: 1754 },
    ],
  }),
  // A web address kept as a link card.
  "LINK-1": info({ id: "LINK-1", kind: "link", uti: "public.url", title: "Tide table", url: "https://tabuademares.com/tides", hasPreview: true }),
};

/** The fake host's picture for an attachment's part, as SVG; null where there is none. */
export function devPicture(id: string, part: string): string | null {
  if (id === "IMG-1" && (part === "inline" || part === "file" || part === "thumb")) {
    const tiles: string[] = [];
    const colours = ["#1f6f8b", "#99a8b2", "#e6d5b8", "#1c4b5e", "#d9c7a7", "#6b8f9c"];
    for (let y = 0; y < 5; y++) for (let x = 0; x < 8; x++) tiles.push(`<rect x="${x * 200 + 6}" y="${y * 200 + 6}" width="188" height="188" rx="10" fill="${colours[(x * 3 + y * 5) % colours.length]}"/>`);
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 1000"><rect width="1600" height="1000" fill="#f4efe6"/>${tiles.join("")}</svg>`;
  }
  if (id === "DRAW-1" && (part === "drawing" || part === "drawing-dark")) {
    const ink = part === "drawing" ? "#151a22" : "#eef2f7";
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 400"><g fill="none" stroke="${ink}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"><rect x="120" y="120" width="560" height="160" rx="12"/><path d="M160 280v60M640 280v60M120 200h560"/><circle cx="260" cy="80" r="26"/><circle cx="400" cy="80" r="26"/><circle cx="540" cy="80" r="26"/></g></svg>`;
  }
  if (id === "PDF-1" && part === "preview") {
    const lines = Array.from({ length: 9 }, (_, i) => `<rect x="40" y="${120 + i * 40}" width="${i % 3 === 2 ? 220 : 340}" height="12" rx="6" fill="#c8d0da"/>`).join("");
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 420 560"><rect width="420" height="560" fill="#fff"/><rect x="40" y="48" width="200" height="28" rx="6" fill="#7a5600"/>${lines}</svg>`;
  }
  if ((id === "SCAN-P1" || id === "SCAN-P2" || id === "LINK-1") && (part === "preview" || part === "file")) {
    const lines = Array.from({ length: 12 }, (_, i) => `<rect x="60" y="${160 + i * 44}" width="${i % 4 === 3 ? 260 : 420}" height="14" rx="7" fill="#b8c2cc"/>`).join("");
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 620 800"><rect width="620" height="800" fill="#fff"/><rect x="60" y="70" width="240" height="34" rx="8" fill="#7a5600"/>${lines}</svg>`;
  }
  return null;
}
