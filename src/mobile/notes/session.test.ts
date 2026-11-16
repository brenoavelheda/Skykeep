import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NoteSession, type NoteHost, type SessionEditor } from "./session";
import type { Run } from "../../screens/notes/Formatted";
import type { NoteAttached } from "../../lib/ipc";

const run = (length: number, extra: Partial<Run> = {}): Run => ({
  length, style: null, indent: 0, checked: null, bold: false, italic: false, underline: false, strike: false, link: null, ...extra,
});

type Deferred<T> = { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void };
function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void;
  let reject!: (e: unknown) => void;
  const promise = new Promise<T>((a, b) => ((resolve = a), (reject = b)));
  return { promise, resolve, reject };
}

/** A host whose answers the test gives, one call at a time. */
function fakeHost(note: { text: string | null; runs: Run[] | "fail" }) {
  const saves: { args: [string, string, string | null, Run[] | null, Run[] | null]; answer: Deferred<string> }[] = [];
  const creates: { args: [string | null, string]; answer: Deferred<string> }[] = [];
  const attaches: { args: [string, string, number, string | null]; answer: Deferred<NoteAttached> }[] = [];
  const reads: Deferred<string | null>[] = [];
  let live = note;
  const h: NoteHost = {
    body: () => {
      if (!reads.length) return Promise.resolve(live.text);
      return reads.shift()!.promise;
    },
    runs: () => (live.runs === "fail" ? Promise.reject("unreadable") : Promise.resolve(live.runs)),
    save: (...args) => {
      const answer = deferred<string>();
      saves.push({ args, answer });
      return answer.promise;
    },
    create: (...args) => {
      const answer = deferred<string>();
      creates.push({ args, answer });
      return answer.promise;
    },
    attach: (guid, path, atOffset, base) => {
      const answer = deferred<NoteAttached>();
      attaches.push({ args: [guid, path, atOffset, base], answer });
      void answer.promise.then((done) => (live = { text: done.body, runs: live.runs }));
      return answer.promise;
    },
  };
  return { h, saves, creates, attaches, reads, set: (n: typeof note) => (live = n) };
}

function fakeEditor(): SessionEditor & {
  replaced: { text: string; runs: Run[] | null }[];
  shown: { text: string; runs: Run[] } | null;
  caretAt: number | null;
  placed: boolean;
  wroteAfter: string[];
} {
  const e = {
    replaced: [] as { text: string; runs: Run[] | null }[],
    shown: null as { text: string; runs: Run[] } | null,
    /** Where the caret was put, and whether it was placed at all. */
    caretAt: null as number | null,
    placed: false,
    replace(text: string, runs: Run[] | null) {
      e.replaced.push({ text, runs });
    },
    flushChange() {},
    current: () => e.shown,
    caret: () => e.caretAt,
    caretPlaced: () => e.placed,
    wroteAfter: [] as string[],
    writeAfter(id: string) {
      e.wroteAfter.push(id);
      return true;
    },
  };
  return e;
}

const events = () => ({ saved: vi.fn(), error: vi.fn(), changed: vi.fn(), write: vi.fn() });

beforeEach(() => vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] }));
afterEach(() => vi.useRealTimers());
/** Lets promise callbacks run while the timers stay fake. */
const tick = async () => {
  for (let i = 0; i < 5; i++) await Promise.resolve();
};

describe("note session", () => {
  it("a re-read is not adopted over typing", async () => {
    const host = fakeHost({ text: "Groceries\nMilk", runs: [run(14)] });
    const ev = events();
    const s = new NoteSession(host.h, ev, { guid: "N1", folderGuid: null });
    const ed = fakeEditor();
    s.editor = ed;
    await s.load();
    expect(s.state).toBe("ready");
    // Typing, not yet saved: the phone's copy of the note changes meanwhile.
    s.type("Groceries\nMilk\nEggs", [run(19)]);
    host.set({ text: "Groceries\nMilk\nBread", runs: [run(20)] });
    await s.refresh();
    expect(ed.replaced).toEqual([]);
    expect(s.body).toBe("Groceries\nMilk");
    // The save goes with the base it began from, so the engine can merge both.
    vi.advanceTimersByTime(900);
    await tick();
    expect(host.saves).toHaveLength(1);
    expect(host.saves[0].args).toEqual(["N1", "Groceries\nMilk\nEggs", "Groceries\nMilk", [run(19)], [run(14)]]);
    // Apple's answer merges the other device's line in; the editor takes it in.
    host.saves[0].answer.resolve("Groceries\nMilk\nBread\nEggs");
    await tick();
    expect(ed.replaced[ed.replaced.length - 1]?.text).toBe("Groceries\nMilk\nBread\nEggs");
    expect(ev.saved).toHaveBeenCalledWith("N1");
  });

  it("a re-read with nothing typed is taken in", async () => {
    const host = fakeHost({ text: "A", runs: [run(1)] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    const ed = fakeEditor();
    s.editor = ed;
    await s.load();
    host.set({ text: "A\nB", runs: [run(3)] });
    await s.refresh();
    expect(ed.replaced).toEqual([{ text: "A\nB", runs: [run(3)] }]);
    expect(s.body).toBe("A\nB");
  });

  it("a re-read begun before a save landed is not adopted", async () => {
    const host = fakeHost({ text: "A", runs: [run(1)] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    const ed = fakeEditor();
    s.editor = ed;
    await s.load();
    const slow = deferred<string | null>();
    host.reads.push(slow);
    s.type("AB", [run(2)]);
    const flushing = s.flush();
    const reading = s.refresh();
    await tick();
    host.saves[0].answer.resolve("AB");
    await flushing;
    // The read was answered from before the save: it must not put "A" back.
    slow.resolve("A");
    await reading;
    expect(ed.replaced.map((r) => r.text)).not.toContain("A");
    expect(s.body).toBe("AB");
  });

  it("saves go one at a time, in order, each with its base", async () => {
    const host = fakeHost({ text: "One", runs: [run(3)] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    s.editor = fakeEditor();
    await s.load();
    s.type("One two", [run(7)]);
    const first = s.flush();
    await tick();
    s.type("One two three", [run(13)]);
    const second = s.flush();
    await tick();
    // The second waits for the first to land.
    expect(host.saves).toHaveLength(1);
    expect(host.saves[0].args.slice(1, 3)).toEqual(["One two", "One"]);
    host.saves[0].answer.resolve("One two");
    await first;
    await tick();
    expect(host.saves).toHaveLength(2);
    // Its base is what Apple confirmed for the first.
    expect(host.saves[1].args).toEqual(["N1", "One two three", "One two", [run(13)], [run(7)]]);
    host.saves[1].answer.resolve("One two three");
    await second;
    expect(s.dirty).toBe(false);
  });

  it("runs that do not fit open plain and save runs null", async () => {
    // Runs for 5 units over a text of 9: a note edited elsewhere since its runs were read.
    const host = fakeHost({ text: "Hello 😀!", runs: [run(5, { bold: true })] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    s.editor = fakeEditor();
    await s.load();
    expect(s.initial).toEqual({ text: "Hello 😀!", runs: null });
    s.type("Hello 😀!!", [run(10)]);
    void s.flush();
    await tick();
    expect(host.saves[0].args).toEqual(["N1", "Hello 😀!!", "Hello 😀!", null, null]);
  });

  it("formatting that cannot be read opens plain too", async () => {
    const host = fakeHost({ text: "Hi", runs: "fail" });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    await s.load();
    expect(s.state).toBe("ready");
    expect(s.initial).toEqual({ text: "Hi", runs: null });
  });

  it("emoji before a formatted stretch keep it on the same characters", async () => {
    // "😀 " is three UTF-16 units; the bold "bold" follows.
    const runs = [run(3), run(4, { bold: true }), run(1)];
    const host = fakeHost({ text: "😀 bold\n", runs });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    await s.load();
    expect(s.initial).toEqual({ text: "😀 bold\n", runs });
  });

  it("a picture deleted, saved, then put back by Undo is saved with it again", async () => {
    // A title, a first line, the picture on a line of its own (one object character), a last
    // line: the picture's run carries the attachment's id, as the engine keeps it.
    const picture = "A-1";
    const text = "Fix check\nfirst\n\ufffc\nlast\n";
    const runs = [run(10, { style: 0 }), run(6), run(1, { attachment: picture }), run(1), run(5)];
    const host = fakeHost({ text, runs });
    const s = new NoteSession(host.h, { saved: vi.fn(), error: vi.fn(), changed: vi.fn(), write: vi.fn() }, { guid: "N1", folderGuid: null });
    s.editor = fakeEditor();
    await s.load();

    // Backspace on the chosen picture: its character goes, its line left empty — and that
    // save lands in Apple.
    const deleted = "Fix check\nfirst\n\nlast\n";
    const without = [run(10, { style: 0 }), run(6), run(1), run(5)];
    s.type(deleted, without);
    void s.flush();
    await tick();
    expect(host.saves[0].args).toEqual(["N1", deleted, text, without, runs]);
    host.saves[0].answer.resolve(deleted);
    await tick();
    expect(s.body).toBe(deleted);

    // Undo, a moment later: the picture is on screen again, so the save after it carries its
    // run again — against the text and formatting Apple holds now, never as text alone.
    s.type(text, runs);
    void s.flush();
    await tick();
    expect(host.saves).toHaveLength(2);
    expect(host.saves[1].args).toEqual(["N1", text, deleted, runs, without]);
    host.saves[1].answer.resolve(text);
    await tick();
    expect(s.body).toBe(text);
    expect(s.dirty).toBe(false);
  });

  it("typing after Undo saves the picture with the typing, and Redo saves it gone", async () => {
    const picture = "A-1";
    const text = "Fix check\nfirst\n\ufffc\nlast\n";
    const runs = [run(10, { style: 0 }), run(6), run(1, { attachment: picture }), run(1), run(5)];
    const host = fakeHost({ text, runs });
    const s = new NoteSession(host.h, { saved: vi.fn(), error: vi.fn(), changed: vi.fn(), write: vi.fn() }, { guid: "N1", folderGuid: null });
    s.editor = fakeEditor();
    await s.load();
    const deleted = "Fix check\nfirst\n\nlast\n";
    const without = [run(10, { style: 0 }), run(6), run(1), run(5)];
    s.type(deleted, without);
    void s.flush();
    await tick();
    host.saves[0].answer.resolve(deleted);
    await tick();

    // Undo and type on the last line at once: one save, the picture and the words.
    const typed = "Fix check\nfirst\n\ufffc\nlast!!\n";
    const typedRuns = [run(10, { style: 0 }), run(6), run(1, { attachment: picture }), run(1), run(7)];
    s.type(typed, typedRuns);
    void s.flush();
    await tick();
    expect(host.saves[1].args).toEqual(["N1", typed, deleted, typedRuns, without]);
    host.saves[1].answer.resolve(typed);
    await tick();

    // Redo: the picture out again, saved over the note that holds it.
    s.type("Fix check\nfirst\n\nlast!!\n", [run(10, { style: 0 }), run(6), run(1), run(7)]);
    void s.flush();
    await tick();
    expect(host.saves[2].args).toEqual([
      "N1",
      "Fix check\nfirst\n\nlast!!\n",
      typed,
      [run(10, { style: 0 }), run(6), run(1), run(7)],
      typedRuns,
    ]);
  });

  it("a failed save goes back in line and is sent on the next flush", async () => {
    const host = fakeHost({ text: "Draft", runs: [run(5)] });
    const ev = events();
    const s = new NoteSession(host.h, ev, { guid: "N1", folderGuid: null });
    s.editor = fakeEditor();
    await s.load();
    s.type("Draft two", [run(9)]);
    const failing = s.flush();
    await tick();
    host.saves[0].answer.reject("You're offline.");
    await failing;
    expect(ev.error).toHaveBeenCalledWith("You're offline.");
    expect(s.dirty).toBe(true);
    const again = s.flush();
    await tick();
    expect(host.saves).toHaveLength(2);
    expect(host.saves[1].args).toEqual(["N1", "Draft two", "Draft", [run(9)], [run(5)]]);
    host.saves[1].answer.resolve("Draft two");
    await again;
    expect(s.dirty).toBe(false);
  });

  it("a new note is created once, on first typing, then saved to the same guid", async () => {
    const host = fakeHost({ text: null, runs: [] });
    const ev = events();
    const s = new NoteSession(host.h, ev, { guid: null, folderGuid: "F1" });
    s.editor = fakeEditor();
    await s.load();
    expect(s.initial).toEqual({ text: "", runs: null });
    // Opened and left empty: nothing is made.
    await s.flush();
    expect(host.creates).toHaveLength(0);
    s.type("Trip", [run(4)]);
    vi.advanceTimersByTime(900);
    await tick();
    expect(host.creates).toHaveLength(1);
    expect(host.creates[0].args).toEqual(["F1", "Trip"]);
    // Typing while it is being made, and a flush asked meanwhile: still one create.
    s.type("Trip to Rio", [run(11)]);
    const flushing = s.flush();
    await tick();
    expect(host.creates).toHaveLength(1);
    host.creates[0].answer.resolve("NEW1");
    await tick();
    expect(ev.saved).toHaveBeenCalledWith("NEW1");
    expect(s.guid).toBe("NEW1");
    // What was typed during the create goes to the new note, its first line a title.
    expect(host.saves).toHaveLength(1);
    expect(host.saves[0].args).toEqual(["NEW1", "Trip to Rio", "Trip", [run(11, { style: 0 })], null]);
    host.saves[0].answer.resolve("Trip to Rio");
    await flushing;
    s.type("Trip to Rio!", [run(12, { style: 0 })]);
    void s.flush();
    await tick();
    expect(host.creates).toHaveLength(1);
    expect(host.saves[1].args[0]).toBe("NEW1");
  });

  it("a file added with no caret placed goes in at the end of the note", async () => {
    const host = fakeHost({ text: "Title\nBody\n", runs: [run(11)] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    const ed = fakeEditor();
    ed.shown = { text: "Title\nBody\n", runs: [run(11)] };
    // Opened and only read: no tap put the caret anywhere, so the editor answers 0 but says so.
    ed.caretAt = 0;
    ed.placed = false;
    s.editor = ed;
    await s.load();

    const adding = s.attach("/cache/b.png");
    await tick();
    expect(host.attaches).toHaveLength(1);
    expect(host.attaches[0].args).toEqual(["N1", "/cache/b.png", 11, "Title\nBody\n"]);
    // The note as Apple now holds it, the file's character at its end.
    host.attaches[0].answer.resolve({
      attachment: "A-1", media: "A-1", uti: "public.png", offset: 11, body: "Title\nBody\n\ufffc\n",
    });
    await adding;
    expect(ed.replaced[ed.replaced.length - 1]?.text).toBe("Title\nBody\n\ufffc\n");
    // Writing goes on under it: the caret on the line below, the keyboard asked for.
    expect(ed.wroteAfter).toEqual(["A-1"]);
  });

  it("a file added at a caret the person placed goes in there", async () => {
    const host = fakeHost({ text: "Title\nBody\n", runs: [run(11)] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    const ed = fakeEditor();
    ed.shown = { text: "Title\nBody\n", runs: [run(11)] };
    ed.caretAt = 6;
    ed.placed = true;
    s.editor = ed;
    await s.load();

    const adding = s.attach("/cache/b.png");
    await tick();
    expect(host.attaches[0].args[2]).toBe(6);
    host.attaches[0].answer.resolve({
      attachment: "A-1", media: "A-1", uti: "public.png", offset: 6, body: "Title\n\ufffcBody\n",
    });
    await adding;
  });

  it("a note with no text held yet is not opened for typing", async () => {    const host = fakeHost({ text: null, runs: [] });
    const s = new NoteSession(host.h, events(), { guid: "N1", folderGuid: null });
    await s.load();
    expect(s.state).toBe("missing");
    host.set({ text: "Here now", runs: [run(8)] });
    await s.refresh();
    expect(s.state).toBe("ready");
    expect(s.initial).toEqual({ text: "Here now", runs: [run(8)] });
  });

  it("a note that cannot be read says so and opens on the next refresh", async () => {
    const host = fakeHost({ text: "x", runs: [run(1)] });
    const failing = deferred<string | null>();
    host.reads.push(failing);
    const ev = events();
    const s = new NoteSession(host.h, ev, { guid: "N1", folderGuid: null });
    const loading = s.load();
    failing.reject("You're offline.");
    await loading;
    expect(s.state).toBe("failed");
    expect(ev.error).toHaveBeenCalledWith("You're offline.");
    await s.refresh();
    expect(s.state).toBe("ready");
  });
});

