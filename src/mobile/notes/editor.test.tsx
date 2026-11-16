import { act, createRef } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, describe, expect, it } from "vitest";
import { fromDoc, toDoc } from "../../screens/notes/rich";
import { NoteEditor, type EditorHandle } from "../../screens/notes/NoteEditor";
import type { Run } from "../../screens/notes/Formatted";
import { runCommand } from "./commands";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const plain = (length: number, extra: Partial<Run> = {}): Run => ({
  length, style: null, indent: 0, checked: null, bold: false, italic: false, underline: false, strike: false, link: null, ...extra,
});

/** A note built line by line: each line's setting, its pieces of text with their marks, and
 *  the newline that carries the setting — runs merged as Apple's editor merges them. */
function note(lines: { set: Partial<Run>; pieces: [string, Partial<Run>][] }[]): { text: string; runs: Run[] } {
  let text = "";
  const runs: Run[] = [];
  const push = (piece: string, run: Run) => {
    text += piece;
    const last = runs[runs.length - 1];
    const key = (r: Run) => JSON.stringify({ ...r, length: 0 });
    if (last && !last.attachment && !run.attachment && key(last) === key(run)) last.length += piece.length;
    else runs.push({ ...run, length: piece.length });
  };
  lines.forEach(({ set, pieces }, i) => {
    for (const [piece, marks] of pieces) push(piece, plain(piece.length, { ...set, ...marks }));
    if (i < lines.length - 1) push("\n", plain(1, set));
  });
  return { text, runs };
}

describe("round trip", () => {
  it("round trip keeps every run", () => {
    const fixture = note([
      { set: { style: 0 }, pieces: [["Trip to Rio", {}]] },
      { set: { style: 1 }, pieces: [["Heading", {}]] },
      { set: { style: 2 }, pieces: [["Subheading", {}]] },
      { set: { style: 4 }, pieces: [["let x = 1", {}]] },
      {
        set: {},
        pieces: [
          ["Plain ", {}],
          ["bold", { bold: true }],
          [" ", {}],
          ["italic", { italic: true }],
          [" ", {}],
          ["under", { underline: true }],
          [" ", {}],
          ["struck", { strike: true }],
          [" ", {}],
          ["link", { link: "https://example.com/a" }],
        ],
      },
      {
        set: {},
        pieces: [
          ["purple", { emphasis: 1 }],
          ["pink", { emphasis: 2 }],
          ["orange", { emphasis: 3 }],
          ["mint", { emphasis: 4 }],
          ["blue", { emphasis: 5, bold: true }],
        ],
      },
      // An emoji (two UTF-16 units) before a formatted stretch: it stays on the same characters.
      { set: { style: 3 }, pieces: [["😀 ", {}], ["after", { bold: true }]] },
      { set: { style: 100, indent: 1 }, pieces: [["Bullet", {}]] },
      { set: { style: 101 }, pieces: [["Dash", {}]] },
      { set: { style: 102, start: 3 }, pieces: [["Third", {}]] },
      { set: { style: 102 }, pieces: [["Fourth", {}]] },
      { set: { style: 103, checked: true }, pieces: [["Done", {}]] },
      { set: { style: 103, checked: false }, pieces: [["To do", {}]] },
      { set: { quote: true }, pieces: [["Quoted", {}]] },
      { set: {}, pieces: [["￼", { attachment: "IMG-1" }]] },
      { set: {}, pieces: [["End", {}]] },
    ]);
    expect(fixture.text.length).toBe(fixture.runs.reduce((n, r) => n + r.length, 0));
    expect(fromDoc(toDoc(fixture.text, fixture.runs))).toEqual(fixture);
  });
});

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  root = null;
});

function mount(text: string, runs: Run[] | null) {
  host = document.createElement("div");
  host.className = "note-editor";
  document.body.appendChild(host);
  const ref = createRef<EditorHandle>();
  const changes: { text: string; runs: Run[] }[] = [];
  root = createRoot(host);
  act(() => {
    root!.render(<NoteEditor ref={ref} text={text} runs={runs} editable label="Note" onChange={(t, r) => changes.push({ text: t, runs: r })} />);
  });
  const dom = host.querySelector<HTMLElement>(".ProseMirror")!;
  /** The words from `from` to `to` in line `line` chosen, as a finger's selection is: the
   *  browser's selection, which the editor takes in before a shortcut (selectionFirst). */
  const select = (line: number, from: number, to: number) => {
    const words = dom.querySelectorAll("p")[line].firstChild!;
    const range = document.createRange();
    range.setStart(words, from);
    range.setEnd(words, to);
    const sel = document.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    act(() => void dom.dispatchEvent(new KeyboardEvent("keydown", { key: "q", ctrlKey: true, bubbles: true })));
  };
  const run = (name: string, arg?: unknown) =>
    act(() => {
      expect(runCommand(ref.current!, name, arg)).toBe(true);
      ref.current!.flushChange();
    });
  return { ref, changes, select, run, last: () => changes[changes.length - 1] };
}

describe("format commands", () => {
  const text = "Hello world\nSecond line";
  const start = [plain(text.length)];

  it("each command writes the desktop's runs", () => {
    const bold = mount(text, start);
    bold.select(0, 6, 11);
    bold.run("bold");
    expect(bold.last()).toEqual({ text, runs: [plain(6), plain(5, { bold: true }), plain(12)] });
    act(() => root?.unmount());

    const check = mount(text, start);
    check.run("style", 103);
    expect(check.last()).toEqual({ text, runs: [plain(12, { style: 103, checked: false }), plain(11)] });
    act(() => root?.unmount());

    const colour = mount(text, start);
    colour.select(0, 6, 11);
    colour.run("colour", 3);
    expect(colour.last()).toEqual({ text, runs: [plain(6), plain(5, { emphasis: 3 }), plain(12)] });
    act(() => root?.unmount());

    const indent = mount(text, start);
    indent.run("indent");
    expect(indent.last()).toEqual({ text, runs: [plain(12, { indent: 1 }), plain(11)] });
    act(() => root?.unmount());

    const quote = mount(text, start);
    quote.run("quote");
    expect(quote.last()).toEqual({ text, runs: [plain(12, { quote: true }), plain(11)] });
  });

  it("the rest of the commands, as the desktop's keys do them", () => {
    const e = mount(text, start);
    e.select(1, 0, 6);
    e.run("italic");
    e.run("underline");
    e.run("strike");
    expect(e.last().runs).toEqual([plain(12), plain(6, { italic: true, underline: true, strike: true }), plain(5)]);
    e.run("style", null);
    e.run("checklist");
    expect(e.last().runs).toEqual([plain(12), plain(6, { style: 103, checked: false, italic: true, underline: true, strike: true }), plain(5, { style: 103, checked: false })]);
    e.run("moveUp");
    expect(e.last().text).toBe("Second line\nHello world");
    e.run("moveDown");
    expect(e.last().text).toBe(text);
    e.run("colour", null);
  });

  it("undo and redo, with whether each can be done", () => {
    const e = mount(text, start);
    expect(e.ref.current!.history()).toEqual({ canUndo: false, canRedo: false });
    e.select(0, 0, 5);
    e.run("bold");
    expect(e.ref.current!.history()).toEqual({ canUndo: true, canRedo: false });
    e.run("undo");
    expect(e.last()).toEqual({ text, runs: start });
    expect(e.ref.current!.history()).toEqual({ canUndo: false, canRedo: true });
    e.run("redo");
    expect(e.last().runs).toEqual([plain(5, { bold: true }), plain(18)]);
  });

  it("an unknown command is refused", () => {
    const e = mount(text, start);
    expect(runCommand(e.ref.current!, "explode")).toBe(false);
    expect(runCommand(e.ref.current!, "colour", 9)).toBe(false);
    expect(runCommand(e.ref.current!, "style", 55)).toBe(false);
  });
});
