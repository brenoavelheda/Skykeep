import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NoteSession, type NoteHost, type SessionEditor } from "./session";
import type { Run } from "../../screens/notes/Formatted";

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
    attach: () => Promise.reject("not here"),
  };
  return { h, saves, creates, reads, set: (n: typeof note) => (live = n) };
}

function fakeEditor(): SessionEditor & { replaced: { text: string; runs: Run[] | null }[]; shown: { text: string; runs: Run[] } | null } {
  const e = {
    replaced: [] as { text: string; runs: Run[] | null }[],
    shown: null as { text: string; runs: Run[] } | null,
    replace(text: string, runs: Run[] | null) {
      e.replaced.push({ text, runs });
    },
    flushChange() {},
    current: () => e.shown,
    caret: () => null,
  };
  return e;
}

const events = () => ({ saved: vi.fn(), error: vi.fn(), changed: vi.fn() });

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

  it("a note with no text held yet is not opened for typing", async () => {
    const host = fakeHost({ text: null, runs: [] });
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

