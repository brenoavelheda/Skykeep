import { act } from "react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { installDevHost, type DevHost } from "./dev-host";
import { mountNotesPage } from "./main";

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

let dev: DevHost;
let unmount: (() => void) | null = null;
let container: HTMLElement;

beforeEach(() => {
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
});

const open = async (guid: string | null, more: Record<string, unknown> = {}) => {
  await act(async () => {
    window.skykeepNotes!.open({ guid, folderGuid: null, editable: true, locked: false, found: [], theme: "light", ...more });
  });
};
const settle = () => act(async () => {
  for (let i = 0; i < 10; i++) await new Promise((r) => setTimeout(r, 0));
});
const attachmentFor = (id: string) =>
  container.querySelector<HTMLImageElement>(`img[src$="/note-media/${id}/inline"]`)?.closest<HTMLElement>(".nf-attachment") ?? null;

function typeInto(input: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(input, value);
  input.dispatchEvent(new Event("input", { bubbles: true }));
}

describe("the note page", () => {
  it("tells the host it is ready, and opens a formatted note in the light or dark palette", async () => {
    expect(dev.events("host_ready")).toHaveLength(1);
    await open("DEV-FORMATTED", { theme: "dark" });
    await settle();
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.documentElement.dataset.app).toBe("notes");
    const lines = container.querySelectorAll(".ProseMirror p");
    expect(lines[0].textContent).toBe("Weekend in Paraty");
    expect(lines[0].getAttribute("data-style")).toBe("0");
    expect(container.querySelector('p[data-style="103"][data-checked="true"]')?.textContent).toBe("Swimsuit and towel");
    expect(dev.events("host_status").length).toBeGreaterThan(0);
  });

  it("a picture that fails to load leaves its placeholder", async () => {
    await open("DEV-MEDIA");
    await settle();
    const good = attachmentFor("IMG-1");
    expect(good?.querySelector("img")?.getAttribute("src")).toBe("https://appassets.androidplatform.net/note-media/IMG-1/inline");
    const broken = attachmentFor("IMG-BROKEN")!;
    expect(broken).not.toBeNull();
    act(() => void broken.querySelector("img")!.dispatchEvent(new Event("error")));
    // The file's chip, named, where the picture was — never a broken frame.
    expect(broken.classList.contains("is-file")).toBe(true);
    expect(broken.querySelector(".nf-file-name")?.textContent).toBe("IMG_2041.HEIC");
    // And it still opens, in the app the phone uses for it.
    act(() => broken.querySelector<HTMLButtonElement>(".nf-file")!.click());
    expect(dev.events("host_open_attachment")).toContainEqual({ id: "IMG-BROKEN", kind: "image", name: "IMG_2041.HEIC" });
  });

  it("taps on a picture, a drawing and a document go to the host", async () => {
    await open("DEV-MEDIA");
    await settle();
    act(() => attachmentFor("IMG-1")!.querySelector("img")!.click());
    act(() => container.querySelector<HTMLImageElement>("img.nf-ink-light")!.click());
    act(() => container.querySelector<HTMLButtonElement>(".nf-doc")!.click());
    expect(dev.events("host_open_attachment")).toEqual([
      { id: "IMG-1", kind: "image", name: "Tiles.jpeg" },
      { id: "DRAW-1", kind: "drawing", name: "Island" },
      { id: "PDF-1", kind: "document", name: "Quote from the joiner.pdf" },
    ]);
  });

  it("a table is drawn with its cells, and a cell typed in is saved", async () => {
    await open("DEV-MEDIA");
    await settle();
    const cells = [...container.querySelectorAll<HTMLElement>(".nf-table .nf-cell-edit")];
    expect(cells.map((c) => c.textContent)).toEqual(["Wall", "Width", "Height", "Window side", "312 cm", "260 cm", "Door side", "288 cm", "260 cm"]);
    cells[4].textContent = "315 cm";
    await act(async () => void cells[4].dispatchEvent(new FocusEvent("blur")));
    await settle();
    expect(dev.sent.filter((s) => s.cmd === "note_table_set_cell").map((s) => s.args)).toEqual([{ id: "TABLE-1", row: 1, column: 1, text: "315 cm" }]);
  });

  it("a link tapped while reading is handed to the host; a refused one is not", async () => {
    await open("DEV-FORMATTED", { editable: false });
    await settle();
    const link = [...container.querySelectorAll<HTMLAnchorElement>(".ProseMirror a[href]")].find((a) => a.textContent === "tabuademares.com")!;
    act(() => link.click());
    expect(dev.events("host_open_link")).toEqual([{ href: "https://tabuademares.com/" }]);
    const odd = document.createElement("a");
    odd.href = "file:///etc/passwd";
    container.appendChild(odd);
    act(() => odd.click());
    expect(dev.events("host_open_link")).toHaveLength(1);
  });

  it("a locked note asks for its password, opens read-only, and Lock Now locks it again", async () => {
    await open("DEV-LOCKED", { locked: true, editable: false });
    await settle();
    const field = () => container.querySelector<HTMLInputElement>(".note-lock-field")!;
    expect(field()).not.toBeNull();
    // A wrong one says so.
    act(() => typeInto(field(), "rio"));
    await act(async () => void container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    await settle();
    expect(container.querySelector(".note-lock-problem")?.textContent).toMatch(/didn’t match/);
    // The hint, asked for.
    act(() => container.querySelector<HTMLButtonElement>(".note-lock-hint-ask")!.click());
    await settle();
    expect(container.querySelector(".note-lock-hint")?.textContent).toMatch(/Where we went in March/);
    act(() => typeInto(field(), "paraty"));
    await act(async () => void container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    await settle();
    const editor = container.querySelector(".ProseMirror")!;
    expect(editor.textContent).toContain("Wi-Fi and alarm");
    expect(editor.getAttribute("contenteditable")).toBe("false");
    act(() => void window.skykeepNotes!.command("lockNow"));
    await settle();
    expect(container.querySelector(".ProseMirror")).toBeNull();
    expect(field()).not.toBeNull();
    expect(dev.sent.some((s) => s.cmd === "note_lock_forget")).toBe(true);
  });

  it("a locked note opened before opens again without asking", async () => {
    dev.sent.length = 0;
    await open("DEV-LOCKED", { locked: true });
    await settle();
    act(() => typeInto(container.querySelector<HTMLInputElement>(".note-lock-field")!, "paraty"));
    await act(async () => void container.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })));
    await settle();
    await open("DEV-FORMATTED");
    await settle();
    await open("DEV-LOCKED", { locked: true });
    await settle();
    expect(container.querySelector(".note-lock-field")).toBeNull();
    expect(container.querySelector(".ProseMirror")?.textContent).toContain("Wi-Fi and alarm");
  });

  it("a note with no text yet says so, and opens once a refresh finds it", async () => {
    const n = dev.notes.get("DEV-FORMATTED")!;
    const text = n.text;
    n.text = null as unknown as string;
    await open("DEV-FORMATTED");
    await settle();
    expect(container.querySelector(".m-state")?.textContent).toMatch(/isn't here yet/);
    expect(container.querySelector(".ProseMirror")).toBeNull();
    n.text = text;
    await act(async () => window.skykeepNotes!.refresh());
    await settle();
    expect(container.querySelector(".ProseMirror p")?.textContent).toBe("Weekend in Paraty");
  });

  it("the searched words are marked in the note", async () => {
    await open("DEV-FORMATTED", { found: ["paraty", "SUNSCR"] });
    await settle();
    expect([...container.querySelectorAll(".note-found")].map((m) => m.textContent)).toEqual(["Paraty", "Sunscr"]);
  });

  it("commands the editor does not know are refused, and nothing breaks", async () => {
    await open("DEV-FORMATTED");
    await settle();
    expect(window.skykeepNotes!.command("explode")).toBe(false);
    expect(window.skykeepNotes!.command("undo")).toBe(true);
  });

  /** The caret put in a line as a finger puts it: the browser's selection there, which the
   *  editor takes in. */
  function caretAt(p: Element) {
    const dom = container.querySelector<HTMLElement>(".ProseMirror")!;
    const range = document.createRange();
    range.selectNodeContents(p);
    range.collapse(false);
    const sel = document.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    act(() => void dom.dispatchEvent(new KeyboardEvent("keydown", { key: "q", ctrlKey: true, bubbles: true })));
  }

  function lastLine() {
    const lines = container.querySelectorAll(".ProseMirror p");
    const p = lines[lines.length - 1];
    caretAt(p);
    return p;
  }

  async function typeLine(p: Element, text: string) {
    const node = p.appendChild(document.createTextNode(text));
    const range = document.createRange();
    range.setStart(node, node.length);
    range.collapse(true);
    const sel = document.getSelection()!;
    sel.removeAllRanges();
    sel.addRange(range);
    await act(async () => {
      for (let i = 0; i < 8; i++) await new Promise((r) => setTimeout(r, 10));
    });
  }

  it("a format chosen with nothing selected holds through a re-read, for the whole word", async () => {
    await open("DEV-FORMATTED");
    await settle();
    const p = lastLine();

    // Bold chosen on the empty line at the end, with nothing selected.
    expect(window.skykeepNotes!.command("bold")).toBe(true);
    await settle();

    // The note changed on another device meanwhile, and the page is asked to read it again as
    // it is after every sync: the re-read is taken in, the caret stays on its line.
    const device = dev.notes.get("DEV-FORMATTED")!;
    const more = "Written on the iPhone.\n";
    device.text += more;
    device.runs = [...device.runs.slice(0, -1), { ...device.runs[device.runs.length - 1], length: device.runs[device.runs.length - 1].length + more.length }];
    await act(async () => window.skykeepNotes!.refresh());
    await settle();
    expect(p.isConnected).toBe(true);
    caretAt(p);

    // The keys the person typed next, the keyboard's capital included.
    await typeLine(p, "Boldword");
    await settle();

    const typed = [...container.querySelectorAll<HTMLElement>(".ProseMirror p")].find((line) => line.textContent === "Boldword");
    expect(typed?.innerHTML).toBe("<strong>Boldword</strong>");
  });
});

