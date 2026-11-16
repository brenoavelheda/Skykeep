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

describe("the note page offline", () => {
  // Its own file: what each attachment is, once known, is kept for the page's life.
  it("offline, an attachment keeps its frame and the note still saves", async () => {
    dev.refuse.set("note_attachment_info", "You're offline.");
    dev.refuse.set("note_table", "You're offline.");
    await open("DEV-MEDIA");
    await settle();
    expect(container.querySelectorAll(".nf-attachment.is-pending").length).toBe(5);
    // Bold on the whole note, saved at once when the host asks.
    const dom = container.querySelector<HTMLElement>(".ProseMirror")!;
    act(() => void dom.dispatchEvent(new KeyboardEvent("keydown", { key: "a", ctrlKey: true, bubbles: true })));
    act(() => void window.skykeepNotes!.command("bold"));
    await act(async () => window.skykeepNotes!.flush("t-1"));
    const saves = dev.sent.filter((s) => s.cmd === "note_save");
    expect(saves).toHaveLength(1);
    expect(saves[0].args.guid).toBe("DEV-MEDIA");
    // The words bold; each attachment kept in its place, by its id.
    const runs = saves[0].args.runs as { length: number; bold: boolean; attachment?: string | null }[];
    expect(runs[0]).toMatchObject({ length: "Kitchen plan".length, bold: true });
    expect(runs.filter((r) => r.attachment).map((r) => r.attachment)).toEqual(["TABLE-1", "IMG-1", "DRAW-1", "PDF-1", "IMG-BROKEN"]);
    expect(dev.events("host_flushed")).toEqual([{ token: "t-1", unsaved: false }]);
    expect(dev.events("host_saved")).toContainEqual({ guid: "DEV-MEDIA" });
  });

});
