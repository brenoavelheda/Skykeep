import { beforeEach, describe, expect, it } from "vitest";
import { convertFileSrc, invoke, listen } from "./tauri-shim";
import { host } from "./host";
import { api, noteMediaSrc } from "../../lib/ipc";

type Sent = { id: number; cmd: string; args: Record<string, unknown> };
let sent: Sent[] = [];
beforeEach(() => {
  sent = [];
  window.SkykeepHost = { post: (json) => void sent.push(JSON.parse(json)) };
});
const answer = (ok: boolean, payload: unknown) => host.resolve(sent[sent.length - 1].id, ok, JSON.stringify(payload));

describe("tauri shim", () => {
  it("invoke becomes the envelope and resolve settles it", async () => {
    const saved = api.noteSave("N1", "Milk\nEggs", "Milk", null, null);
    expect(sent.at(-1)).toMatchObject({ cmd: "note_save", args: { guid: "N1", body: "Milk\nEggs", base: "Milk", runs: null, baseRuns: null } });
    expect(sent.at(-1)!.id).toBeGreaterThan(0);
    answer(true, "Milk\nEggs");
    await expect(saved).resolves.toBe("Milk\nEggs");

    const cell = invoke("note_table_set_cell", { id: "T1", row: 0, column: 1, text: "x" });
    expect(sent.at(-1)).toMatchObject({ cmd: "note_table_set_cell", args: { id: "T1", row: 0, column: 1, text: "x" } });
    answer(false, "Apple refused the cell");
    await expect(cell).rejects.toBe("Apple refused the cell");
  });

  it("convertFileSrc gives the note-media address", () => {
    expect(convertFileSrc("note/AB12-cd/inline", "quince")).toBe("https://appassets.androidplatform.net/note-media/AB12-cd/inline");
    // An id is one path segment whatever it holds.
    expect(noteMediaSrc("a/b c", "drawing-dark")).toBe("https://appassets.androidplatform.net/note-media/a%2Fb%20c/drawing-dark");
  });

  it("opening an attachment is told to the host as what it is", async () => {
    const info = api.noteAttachmentInfo("F1");
    answer(true, { id: "F1", uti: "com.adobe.pdf", kind: "document", filename: "Plan.pdf", title: null, width: null, height: null, size: 2048, hasPreview: true });
    await info;
    sent = [];
    await api.noteAttachmentOpen("F1");
    expect(sent).toEqual([{ id: 0, cmd: "host_open_attachment", args: { id: "F1", kind: "document", name: "Plan.pdf" } }]);
  });

  it("listen is quiet: nothing on the phone sends desktop events", async () => {
    const stop = await listen("quince://contrast", () => {});
    expect(typeof stop).toBe("function");
    expect(sent).toEqual([]);
  });
});
