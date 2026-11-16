/**
 * Writing around a picture, as Notes on a phone lets one: a tap under the last thing in a note
 * gives a line to type on, a file just added leaves the caret on the line below it, the caret
 * sits before or after an attachment, and a long press chooses one without opening it. What is
 * saved is checked as the page sends it (`note_save`): the attachment's run stays where it was,
 * once, and a line only looked at is never saved.
 */
import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { installDevHost, type DevHost } from "./dev-host";
import { buildNote } from "./fixtures";
import { mountNotesPage } from "./main";
import type { Run } from "../../screens/notes/Formatted";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let dev: DevHost;
let unmount: (() => void) | null = null;
let container: HTMLElement;

beforeEach(() => {
  // jsdom lays nothing out: a point is over nothing (the editor asks, for a tap on a line).
  document.elementFromPoint = () => null;
  dev = installDevHost();
  container = document.createElement("div");
  document.body.appendChild(container);
  act(() => {
    unmount = mountNotesPage(container);
  });
});
afterEach(() => {
  act(() => unmount?.());
  container.remove();
  document.body.replaceChildren();
  vi.restoreAllMocks();
});

const settle = () => act(async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
});
const open = async (guid: string) => {
  await act(async () => {
    window.skykeepNotes!.open({ guid, folderGuid: null, editable: true, locked: false, found: [], theme: "light" });
  });
  await settle();
};
/** A note of the test's own, held by the fake host. */
const note = (guid: string, lines: Parameters<typeof buildNote>[0]) => {
  const n = buildNote(lines);
  dev.notes.set(guid, { guid, folderGuid: null, ...n });
  return n;
};
const editor = () => container.querySelector<HTMLElement>(".ProseMirror")!;
const lines = () => [...editor().querySelectorAll<HTMLElement>(":scope > p")];
const saves = () => dev.sent.filter((s) => s.cmd === "note_save").map((s) => s.args as { body: string; runs: Run[] | null });
const flush = () => act(async () => {
  await window.skykeepNotes!.flush(1);
});

/**
 * The page laid out as on a phone 360px wide (jsdom lays nothing out): each line 40px tall,
 * one under the other from `top`, the note's column 16px in from each side.
 */
function layOut(top = 8) {
  const box = (t: number, h: number, l = 16, w = 328) => ({ top: t, bottom: t + h, left: l, right: l + w, width: w, height: h, x: l, y: t, toJSON: () => ({}) }) as DOMRect;
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (this: HTMLElement) {
    const all = lines();
    const line = this.closest(".ProseMirror > p") as HTMLElement | null;
    if (line) return box(top + all.indexOf(line) * 40, 40);
    if (this.classList.contains("ProseMirror")) return box(top, 600);
    return box(0, 640, 0, 360);
  });
}

/** A finger's tap at (x, y) on `target`, as the WebView hands it on: the mouse events after it. */
function tap(target: Element, x: number, y: number) {
  const at = { clientX: x, clientY: y, bubbles: true, cancelable: true, button: 0 };
  act(() => {
    target.dispatchEvent(new MouseEvent("mousedown", at));
    target.dispatchEvent(new MouseEvent("mouseup", at));
    target.dispatchEvent(new MouseEvent("click", at));
  });
}

/** A key, as the keyboard's own key event reaches the editor. */
function key(name: string) {
  act(() => void editor().dispatchEvent(new KeyboardEvent("keydown", { key: name, bubbles: true, cancelable: true })));
}

/** Where the caret is: the line it is in, and how far into it — its characters, an
 *  attachment counting one, as in the note's text. */
function caret(): { line: number; offset: number } {
  const sel = document.getSelection()!;
  const node = sel.anchorNode!;
  const line = (node instanceof Element ? node : node.parentElement)!.closest(".ProseMirror > p") ?? (node as Element);
  const range = document.createRange();
  range.selectNodeContents(line);
  range.setEnd(sel.anchorNode!, sel.anchorOffset);
  const before = range.cloneContents();
  const attachments = before.querySelectorAll(".nf-attachment");
  attachments.forEach((a) => a.remove());
  return { line: lines().indexOf(line as HTMLElement), offset: (before.textContent ?? "").length + attachments.length };
}

/** Typed where the caret is, as the keyboard types it into the line. */
async function typeIn(p: Element, text: string) {
  const node = p.insertBefore(document.createTextNode(text), p.querySelector("br.ProseMirror-trailingBreak"));
  const range = document.createRange();
  range.setStart(node, node.length);
  range.collapse(true);
  const sel = document.getSelection()!;
  sel.removeAllRanges();
  sel.addRange(range);
  await settle();
}

/** The attachment runs of a save, by where they sit in its text. */
function attachmentsIn(save: { body: string; runs: Run[] | null }) {
  const out: { at: number; id: string }[] = [];
  let at = 0;
  for (const r of save.runs ?? []) {
    if (r.attachment) out.push({ at, id: r.attachment });
    at += r.length;
  }
  expect(at).toBe(save.body.length);
  return out;
}

describe("1. there is always a way to type below", () => {
  it("a tap under a picture that ends the note gives an empty line after it, typed on as the note's next line", async () => {
    await open("DEV-PICTURE");
    const shown = dev.notes.get("DEV-PICTURE")!.text;
    layOut();
    expect(lines()).toHaveLength(3);

    tap(editor(), 180, 400);

    expect(lines()).toHaveLength(4);
    expect(lines()[2].querySelector(".nf-attachment")).not.toBeNull();
    expect(lines()[3].textContent).toBe("");
    expect(document.activeElement).toBe(editor());
    expect(caret()).toEqual({ line: 3, offset: 0 });
    // The keyboard is asked for: the host brings it up.
    expect(dev.events("host_keyboard")).toHaveLength(1);
    // Only looked at, the line is not the note's: nothing is saved for it.
    await flush();
    expect(saves()).toEqual([]);

    await typeIn(lines()[3], "Grout: light grey");
    await flush();
    const save = saves()[saves().length - 1];
    expect(save.body).toBe(`${shown}\nGrout: light grey`);
    expect(attachmentsIn(save)).toEqual([{ at: shown.length - 1, id: "IMG-1" }]);
  });

  it.each([
    ["a table", "TABLE-1"],
    ["a drawing", "DRAW-1"],
    ["a document", "PDF-1"],
  ])("so does a tap under %s that ends the note", async (_, id) => {
    note("N-END", [
      { set: {}, pieces: [["Plans", {}]] },
      { set: {}, pieces: [["￼", { attachment: id }]] },
    ]);
    await open("N-END");
    layOut();
    tap(container.querySelector(".note-editor")!, 180, 620);
    expect(lines()).toHaveLength(3);
    expect(lines()[2].textContent).toBe("");
    expect(caret()).toEqual({ line: 2, offset: 0 });
  });

  it("a tap under a note that ends in text puts the caret at its end, with no line added", async () => {
    await open("DEV-MEDIA");
    layOut();
    const count = lines().length;
    tap(editor(), 180, 590);
    expect(lines()).toHaveLength(count);
    expect(caret()).toEqual({ line: count - 1, offset: "Ask about delivery.".length });
    expect(dev.events("host_keyboard")).toHaveLength(1);
  });

  it("a tap above a note that starts with a picture gives an empty line before it", async () => {
    const n = note("N-FIRST", [
      { set: {}, pieces: [["￼", { attachment: "IMG-1" }]] },
      { set: {}, pieces: [["Tiles for the bathroom", {}]] },
    ]);
    await open("N-FIRST");
    layOut(32);
    tap(editor(), 180, 14);
    expect(lines()).toHaveLength(3);
    expect(lines()[0].textContent).toBe("");
    expect(caret()).toEqual({ line: 0, offset: 0 });
    await flush();
    expect(saves()).toEqual([]);

    await typeIn(lines()[0], "Bathroom");
    await flush();
    const save = saves()[saves().length - 1];
    expect(save.body).toBe(`Bathroom\n${n.text}`);
    expect(attachmentsIn(save)).toEqual([{ at: "Bathroom\n".length, id: "IMG-1" }]);
  });

  it("a line made over the note and then one under it: only one is kept, and nothing is saved", async () => {
    note("N-BOTH", [
      { set: {}, pieces: [["￼", { attachment: "IMG-1" }]] },
      { set: {}, pieces: [["￼", { attachment: "DRAW-1" }]] },
    ]);
    await open("N-BOTH");
    layOut(32);
    tap(editor(), 180, 14);
    expect(lines()).toHaveLength(3);
    layOut(32);
    tap(editor(), 180, 590);
    expect(lines()).toHaveLength(3);
    expect(lines()[0].querySelector(".nf-attachment")).not.toBeNull();
    expect(caret()).toEqual({ line: 2, offset: 0 });
    await flush();
    expect(saves()).toEqual([]);
  });

  it("a note opened and left without typing saves nothing, a tap under it included", async () => {
    await open("DEV-PICTURE");
    layOut();
    tap(editor(), 180, 400);
    await act(async () => window.skykeepNotes!.open({ guid: "DEV-FORMATTED", folderGuid: null, editable: true, locked: false, found: [], theme: "light" }));
    await settle();
    expect(saves()).toEqual([]);
    expect(dev.notes.get("DEV-PICTURE")!.text.endsWith("￼")).toBe(true);
  });

  it("the line under the picture stays through a re-read after a sync", async () => {
    await open("DEV-PICTURE");
    layOut();
    tap(editor(), 180, 400);
    // Another device changed the title meanwhile.
    const device = dev.notes.get("DEV-PICTURE")!;
    device.text = device.text.replace("Bathroom", "Bathroom upstairs");
    device.runs = [{ ...device.runs[0], length: device.runs[0].length + " upstairs".length }, ...device.runs.slice(1)];
    const synced = device.text;
    await act(async () => window.skykeepNotes!.refresh());
    await settle();
    expect(lines()[0].textContent).toBe("Bathroom upstairs");
    expect(lines()).toHaveLength(4);
    expect(lines()[3].textContent).toBe("");

    await typeIn(lines()[3], "Grout");
    await flush();
    const save = saves()[saves().length - 1];
    expect(save.body).toBe(`${synced}\nGrout`);
    expect(attachmentsIn(save)).toEqual([{ at: synced.length - 1, id: "IMG-1" }]);
  });

  it("a read-only note is not given a line", async () => {
    await act(async () => window.skykeepNotes!.open({ guid: "DEV-PICTURE", folderGuid: null, editable: false, locked: false, found: [], theme: "light" }));
    await settle();
    layOut();
    tap(editor(), 180, 400);
    expect(lines()).toHaveLength(3);
  });
});

describe("2. after attaching, writing goes on", () => {
  it("the caret is on a new line right below the file just added, the keyboard asked for", async () => {
    const n = note("N-ADD", [
      { set: {}, pieces: [["Packing", {}]] },
      { set: {}, pieces: [["Towels", {}]] },
    ]);
    await open("N-ADD");
    await act(async () => window.skykeepNotes!.attach("/cache/IMG_1.jpg"));
    await settle();

    const added = dev.notes.get("N-ADD")!.text;
    expect(added).toBe(`${n.text}\n￼`);
    expect(lines()).toHaveLength(4);
    expect(lines()[2].querySelector(".nf-attachment")).not.toBeNull();
    expect(lines()[3].textContent).toBe("");
    expect(editor().getAttribute("contenteditable")).toBe("true");
    expect(document.activeElement).toBe(editor());
    expect(caret()).toEqual({ line: 3, offset: 0 });
    expect(dev.events("host_keyboard")).toHaveLength(1);
    await flush();
    expect(saves()).toEqual([]);

    await typeIn(lines()[3], "Sunscreen");
    await flush();
    const save = saves()[saves().length - 1];
    expect(save.body).toBe(`${added}\nSunscreen`);
    expect(attachmentsIn(save)).toEqual([{ at: added.length - 1, id: "ATT-1" }]);
  });

  it("a file added inside the note leaves the caret on a new line between it and the text after it", async () => {
    note("N-MID", [
      { set: {}, pieces: [["Packing", {}]] },
      { set: {}, pieces: [["Towels", {}]] },
    ]);
    await open("N-MID");
    // The caret put at the end of the first line, as a tap there puts it.
    const p = lines()[0];
    const range = document.createRange();
    range.selectNodeContents(p);
    range.collapse(false);
    document.getSelection()!.removeAllRanges();
    document.getSelection()!.addRange(range);
    act(() => void editor().dispatchEvent(new KeyboardEvent("keydown", { key: "q", ctrlKey: true, bubbles: true })));
    await act(async () => window.skykeepNotes!.attach("/cache/IMG_1.jpg"));
    await settle();

    expect(dev.notes.get("N-MID")!.text).toBe("Packing\n￼\nTowels");
    expect(lines().map((l) => l.textContent)).toEqual(["Packing", "", "", "Towels"]);
    expect(lines()[1].querySelector(".nf-attachment")).not.toBeNull();
    expect(caret()).toEqual({ line: 2, offset: 0 });
    await typeIn(lines()[2], "Hat");
    await flush();
    const save = saves()[saves().length - 1];
    expect(save.body).toBe("Packing\n￼\nHat\nTowels");
    expect(attachmentsIn(save)).toEqual([{ at: "Packing\n".length, id: "ATT-1" }]);
  });
});

describe("3. the caret sits beside a picture", () => {
  /** DEV-MEDIA's "The tiles we liked:" and the picture on the line under it. */
  const words = 3;
  const picture = 4;
  const caretAt = (line: number, where: "start" | "end") => {
    const range = document.createRange();
    range.selectNodeContents(lines()[line]);
    range.collapse(where === "start");
    document.getSelection()!.removeAllRanges();
    document.getSelection()!.addRange(range);
    act(() => void editor().dispatchEvent(new KeyboardEvent("keydown", { key: "q", ctrlKey: true, bubbles: true })));
  };

  it("the arrow keys go to just before the picture, then just after it, and back", async () => {
    await open("DEV-MEDIA");
    act(() => editor().focus());
    caretAt(words, "end");
    key("ArrowRight");
    expect(caret()).toEqual({ line: picture, offset: 0 });
    expect(editor().querySelector(".ProseMirror-selectednode")).toBeNull();
    key("ArrowRight");
    expect(caret()).toEqual({ line: picture, offset: 1 });
    key("ArrowLeft");
    expect(caret()).toEqual({ line: picture, offset: 0 });
    key("ArrowLeft");
    expect(caret()).toEqual({ line: words, offset: "The tiles we liked:".length });
  });

  it("a tap in the margin beside a picture puts the caret before or after it", async () => {
    await open("DEV-MEDIA");
    layOut();
    tap(container.querySelector(".note-editor")!, 350, 8 + picture * 40 + 20);
    expect(caret()).toEqual({ line: picture, offset: 1 });
    tap(container.querySelector(".note-editor")!, 6, 8 + picture * 40 + 20);
    expect(caret()).toEqual({ line: picture, offset: 0 });
  });

  it("Enter just after a picture makes a line under it", async () => {
    await open("DEV-MEDIA");
    const before = dev.notes.get("DEV-MEDIA")!.text;
    act(() => editor().focus());
    caretAt(words, "end");
    key("ArrowRight");
    key("ArrowRight");
    key("Enter");
    expect(lines()[picture + 1].textContent).toBe("");
    await flush();
    const save = saves()[saves().length - 1];
    const at = before.indexOf("￼", before.indexOf("liked:"));
    expect(save.body).toBe(`${before.slice(0, at + 1)}\n${before.slice(at + 1)}`);
    expect(attachmentsIn(save).find((a) => a.id === "IMG-1")).toEqual({ at, id: "IMG-1" });
  });

  it("Backspace just after a picture removes it", async () => {
    await open("DEV-MEDIA");
    const before = dev.notes.get("DEV-MEDIA")!.text;
    act(() => editor().focus());
    caretAt(words, "end");
    key("ArrowRight");
    key("ArrowRight");
    key("Backspace");
    await settle();
    expect(container.querySelector('img[src$="/note-media/IMG-1/inline"]')).toBeNull();
    await flush();
    const save = saves()[saves().length - 1];
    const at = before.indexOf("￼", before.indexOf("liked:"));
    expect(save.body).toBe(before.slice(0, at) + before.slice(at + 1));
    expect(attachmentsIn(save).map((a) => a.id)).toEqual(["TABLE-1", "DRAW-1", "PDF-1", "IMG-BROKEN"]);
  });
});

describe("4. a long press chooses a picture without opening it", () => {
  const press = (target: Element) => {
    const ev = new MouseEvent("contextmenu", { bubbles: true, cancelable: true, clientX: 180, clientY: 200 });
    act(() => void target.dispatchEvent(ev));
    return ev;
  };

  it("a long press selects the picture, drawn as chosen; Backspace then removes it", async () => {
    await open("DEV-MEDIA");
    const before = dev.notes.get("DEV-MEDIA")!.text;
    const img = container.querySelector<HTMLImageElement>('img[src$="/note-media/IMG-1/inline"]')!;
    const ev = press(img);
    // The phone's own menu for a picture is not shown, and the viewer does not open.
    expect(ev.defaultPrevented).toBe(true);
    expect(dev.events("host_open_attachment")).toEqual([]);
    // Chosen, drawn so; the caret just after it, where the keyboard's delete takes it out.
    expect(img.closest(".nf-attachment")!.hasAttribute("data-chosen")).toBe(true);
    expect(caret()).toEqual({ line: 4, offset: 1 });
    expect(document.activeElement).toBe(editor());
    expect(dev.events("host_keyboard")).toHaveLength(1);

    key("Backspace");
    await settle();
    expect(container.querySelector('img[src$="/note-media/IMG-1/inline"]')).toBeNull();
    await flush();
    const save = saves()[saves().length - 1];
    const at = before.indexOf("￼", before.indexOf("liked:"));
    expect(save.body).toBe(before.slice(0, at) + before.slice(at + 1));
    expect(attachmentsIn(save).map((a) => a.id)).toEqual(["TABLE-1", "DRAW-1", "PDF-1", "IMG-BROKEN"]);
  });

  it("Delete removes the chosen picture too; a key that moves the caret lets it go", async () => {
    await open("DEV-MEDIA");
    const att = () => container.querySelector('img[src$="/note-media/IMG-1/inline"]')?.closest(".nf-attachment") ?? null;
    press(att()!.querySelector("img")!);
    key("ArrowLeft");
    expect(att()!.hasAttribute("data-chosen")).toBe(false);
    press(att()!.querySelector("img")!);
    key("Delete");
    await settle();
    expect(att()).toBeNull();
  });

  it("a single tap still opens it", async () => {
    await open("DEV-MEDIA");
    act(() => container.querySelector<HTMLImageElement>('img[src$="/note-media/IMG-1/inline"]')!.click());
    expect(dev.events("host_open_attachment")).toEqual([{ id: "IMG-1", kind: "image", name: "Tiles.jpeg" }]);
  });

  it("a long press in a table's cell is the cell's own: nothing is chosen", async () => {
    await open("DEV-MEDIA");
    const cell = container.querySelector<HTMLElement>(".nf-table .nf-cell-edit")!;
    const ev = press(cell);
    expect(ev.defaultPrevented).toBe(false);
    expect(editor().querySelector("[data-chosen]")).toBeNull();
  });

  it("in a note that can't be written, a long press chooses nothing", async () => {
    await act(async () => window.skykeepNotes!.open({ guid: "DEV-MEDIA", folderGuid: null, editable: false, locked: false, found: [], theme: "light" }));
    await settle();
    press(container.querySelector<HTMLImageElement>('img[src$="/note-media/IMG-1/inline"]')!);
    expect(editor().querySelector("[data-chosen]")).toBeNull();
  });
});
