/**
 * The open note's save session on the phone, with the desktop's rules (NotesView.tsx): the
 * page decides when to save and the Android host only carries each call to the engine.
 *
 *   - A save is made against a base: the text (and runs) Apple last confirmed, so the engine
 *     can tell an edit made on another device meanwhile and keep both.
 *   - One save at a time, in order: a second started while the first was out could land last
 *     with older text.
 *   - A re-read of the note is never adopted over what is being typed, nor one begun before a
 *     save landed: it would lay an older copy over the newer one.
 *   - Runs that do not describe the text exactly open the note plain and save the text alone
 *     (runs null), which leaves Apple's formatting where it is.
 *   - A save that fails goes back in line, never dropped, and goes with the next flush.
 *   - A new note is made only once something is typed, once, and saved to that note after.
 */
import type { Run } from "../../screens/notes/Formatted";
import { carryRuns, runsFit, sameFormatting } from "../../screens/notes/rich";
import { mergeEdits } from "../../screens/notes/model";
import { titled } from "../../screens/notes/titled";
import { messageOf, type NoteAttached } from "../../lib/ipc";

/** The engine, as the host offers it (the desktop's command names; BRIDGE.md). */
export type NoteHost = {
  body: (guid: string) => Promise<string | null>;
  runs: (guid: string) => Promise<Run[]>;
  save: (guid: string, body: string, base: string | null, runs: Run[] | null, baseRuns: Run[] | null) => Promise<string>;
  create: (folderGuid: string | null, body: string) => Promise<string>;
  attach: (guid: string, path: string, atOffset: number, base: string | null) => Promise<NoteAttached>;
};

/** The editor on screen, as the session needs it (NoteEditor's handle). */
export type SessionEditor = {
  replace: (text: string, runs: Run[] | null) => void;
  flushChange: () => void;
  current: () => { text: string; runs: Run[] } | null;
  caret: () => number | null;
  /** Whether a caret was placed in the note at all: without one, a file goes in at its end. */
  caretPlaced: () => boolean;
};

export type SessionEvents = {
  /** A save or a create landed: the note `guid` holds what was typed. */
  saved: (guid: string) => void;
  error: (message: string) => void;
  /** Something the page draws from changed: state, dirty, attaching. */
  changed: () => void;
};

/** A pause in typing this long and the note is saved (the desktop's). */
export const SAVE_MS = 900;

const plain = (length: number): Run => ({
  length, style: null, indent: 0, checked: null, bold: false, italic: false, underline: false, strike: false, link: null,
});

export class NoteSession {
  /** loading: being read; ready: open for reading and typing; missing: the library holds no
   *  text for it yet; failed: it could not be read (offline). */
  state: "loading" | "ready" | "missing" | "failed" = "loading";
  guid: string | null;
  readonly folderGuid: string | null;
  /** What Apple holds for the note, as last read or confirmed; null until known. */
  body: string | null = null;
  runs: Run[] | null = null;
  /** What the editor opens with: the text, and its runs only when they fit it. */
  initial: { text: string; runs: Run[] | null } = { text: "", runs: null };
  editor: SessionEditor | null = null;
  attaching = false;

  private base: { text: string; runs: Run[] | null } | null = null;
  private pending: { body: string; runs: Run[] | null } | null = null;
  /** The text and runs on screen, as last typed. */
  private draft: string | null = null;
  private draftRuns: Run[] | null = null;
  private saving = false;
  /** The last save failed: the edit waits for the next flush or refresh. */
  private unsent = false;
  private creating = false;
  private closed = false;
  /** Moves with each edit typed, and with each save sent and landed. */
  private edits = 0;
  private saveSeq = 0;
  private inFlight: Promise<void> = Promise.resolve();
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly host: NoteHost,
    private readonly events: SessionEvents,
    opts: { guid: string | null; folderGuid: string | null },
  ) {
    this.guid = opts.guid;
    this.folderGuid = opts.folderGuid;
  }

  /** Whether this note's formatting can be written from the editor (the desktop's formatSafe). */
  get formatSafe(): boolean {
    return this.body !== null && runsFit(this.body, this.runs);
  }

  /** Something typed is not yet with Apple. */
  get dirty(): boolean {
    return this.pending !== null || this.saving || this.creating || (this.guid === null && Boolean(this.draft?.trim()));
  }

  async load(): Promise<void> {
    if (this.guid === null) {
      this.state = "ready";
      this.initial = { text: "", runs: null };
      this.events.changed();
      return;
    }
    await this.readIn();
  }

  /** The editor's text and runs, as typed. */
  type(text: string, runs: Run[]): void {
    if (this.closed) return;
    this.edits += 1;
    if (this.guid === null) {
      this.draft = text;
      this.draftRuns = runs;
      this.soon();
      this.events.changed();
      return;
    }
    // Nothing may be typed into a note whose text has not arrived.
    if (this.body === null) return;
    // The first keystroke marks what the note read as the base of the edit.
    if (!this.base) this.base = { text: this.body, runs: this.formatSafe ? this.runs : null };
    this.draft = text;
    this.draftRuns = runs;
    // Back to what Apple holds, as formatting rather than as runs (Apple's come in pieces).
    const unchanged = text === this.body && (!this.formatSafe || sameFormatting(text, runs, this.runs));
    this.pending = unchanged ? null : { body: text, runs: this.formatSafe ? runs : null };
    this.soon();
    this.events.changed();
  }

  /** Everything typed sent now: the editor's last keys, the note made if it is new, the save. */
  flush(): Promise<void> {
    this.editor?.flushChange();
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
    const run = this.inFlight.then(async () => {
      if (this.guid === null) await this.create();
      await this.saveNext();
    });
    this.inFlight = run.catch(() => {});
    return run;
  }

  /** The library changed: the note read again, and taken in only over nothing being typed. */
  async refresh(): Promise<void> {
    if (this.closed) return;
    // Apple was reached: an edit that could not be sent goes again now.
    if (this.unsent && this.pending && !this.saving) void this.flush();
    if (this.guid === null) return;
    if (this.state !== "ready") {
      await this.readIn();
      return;
    }
    const seq = this.saveSeq;
    const edits = this.edits;
    let read: { text: string | null; runs: Run[] | null };
    try {
      read = await this.read(this.guid);
    } catch {
      return;
    }
    if (this.closed || read.text === null) return;
    // Begun before a save went or landed: it may hold the note from before that save.
    if (this.saveSeq !== seq || this.saving) return;
    // Never over what is being typed. Typing already kept, with nothing new since, is not
    // being typed: what Apple made of it — merged with another device's edit — is taken in.
    const typing = this.pending !== null || this.edits !== edits || (this.draft !== null && this.draft !== this.base?.text);
    if (typing) return;
    this.adopt(read.text, read.runs ?? this.runs ?? []);
  }

  /** A file added at the caret, as the desktop's attach does: what is typed goes first. */
  async attach(path: string): Promise<void> {
    await this.flush();
    const guid = this.guid;
    if (guid === null) {
      this.events.error("Type something in the note first, then add the file.");
      return;
    }
    if (this.pending) {
      this.events.error("The note isn't saved yet, so the file can't be added. Try again in a moment.");
      return;
    }
    const shown = this.editor?.current();
    const text = shown?.text ?? this.body;
    // A caret the person placed is honoured; with none (a note only opened, a checklist circle
    // tapped) the file goes in at the end of the note, on a line of its own — never at offset
    // 0, which put it above the title and changed it. The engine adds the newline when the
    // place is mid-line.
    const at = this.editor?.caretPlaced() === true ? (this.editor.caret() ?? text?.length ?? 0) : (text?.length ?? 0);
    this.attaching = true;
    this.events.changed();
    try {
      await this.host.attach(guid, path, at, text);
      // The note as Apple now holds it, file in place: the editor waited, so it is taken whole.
      const read = await this.read(guid);
      if (read.text !== null) {
        this.draft = null;
        this.base = null;
        this.adopt(read.text, read.runs ?? []);
      }
      this.events.saved(guid);
    } catch (e) {
      this.events.error(messageOf(e));
    } finally {
      this.attaching = false;
      this.events.changed();
    }
  }

  /** Left: what is typed sent, and nothing more taken in. */
  async close(): Promise<void> {
    await this.flush().catch(() => {});
    this.closed = true;
    if (this.timer !== null) clearTimeout(this.timer);
  }

  private soon() {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.flush();
    }, SAVE_MS);
  }

  private async read(guid: string): Promise<{ text: string | null; runs: Run[] | null }> {
    const [text, runs] = await Promise.all([
      this.host.body(guid),
      // Unreadable formatting still opens the note, as text only.
      this.host.runs(guid).then((r) => (Array.isArray(r) ? r : []), () => null),
    ]);
    return { text, runs };
  }

  /** The note read to be opened: for the first time, or again after it could not be. */
  private async readIn() {
    const guid = this.guid!;
    try {
      const read = await this.read(guid);
      if (this.closed) return;
      if (read.text === null) {
        this.state = "missing";
      } else {
        this.body = read.text;
        this.runs = read.runs ?? [];
        this.initial = { text: read.text, runs: this.formatSafe ? this.runs : null };
        this.state = "ready";
      }
    } catch (e) {
      if (this.closed) return;
      this.state = "failed";
      this.events.error(messageOf(e));
    }
    this.events.changed();
  }

  /** What Apple holds, taken in by the editor — unless a key typed that very moment came in. */
  private adopt(text: string, runs: Run[]) {
    const fit = runsFit(text, runs);
    const before = this.edits;
    this.editor?.replace(text, fit ? runs : null);
    if (this.edits !== before) return;
    this.body = text;
    this.runs = runs;
    this.initial = { text, runs: fit ? runs : null };
    this.draftRuns = fit ? runs : null;
    if (this.draft !== null && this.draft !== text) {
      this.draft = null;
      this.base = null;
    }
    this.events.changed();
  }

  /** The new note made from what is typed, once; what is typed meanwhile is saved onto it. */
  private async create() {
    const text = this.draft ?? "";
    // Nothing typed: nothing to keep, so nothing is sent.
    if (!text.trim() || this.creating) return;
    const runsAt = this.draftRuns;
    this.creating = true;
    this.events.changed();
    let guid: string;
    try {
      guid = await this.host.create(this.folderGuid, text);
    } catch (e) {
      // The write failed, not the typing: it stays on screen, to go with the next flush.
      this.creating = false;
      this.events.error(messageOf(e));
      this.events.changed();
      return;
    }
    this.creating = false;
    this.guid = guid;
    const latest = this.draft ?? text;
    this.body = text;
    this.base = { text, runs: null };
    // Made from its text; its formatting follows straight after — the first line a title, as
    // Notes makes it, and whatever was set while it was being written.
    this.runs = runsAt && runsFit(text, runsAt) ? titled(text, runsAt) : titled(text, text.length ? [plain(text.length)] : []);
    const typedRuns = this.draftRuns && runsFit(latest, this.draftRuns) ? titled(latest, this.draftRuns) : null;
    this.pending = { body: latest, runs: typedRuns };
    this.state = "ready";
    this.events.saved(guid);
    this.events.changed();
  }

  private async saveNext() {
    const next = this.pending;
    const guid = this.guid;
    if (!next || guid === null) return;
    this.pending = null;
    this.saving = true;
    this.saveSeq += 1;
    const from = this.base?.text ?? null;
    const fromRuns = this.base?.runs ?? null;
    let kept: string;
    try {
      kept = await this.host.save(guid, next.body, from, next.runs, next.runs ? fromRuns : null);
    } catch (e) {
      this.saving = false;
      this.unsent = true;
      // Not sent: back in line (unless newer typing already replaced it), never dropped.
      if (!this.pending) this.pending = next;
      this.events.error(messageOf(e));
      this.events.changed();
      return;
    }
    this.saving = false;
    this.unsent = false;
    this.saveSeq += 1;
    const keptRuns = next.runs ? (kept === next.body ? next.runs : carryRuns(next.body, next.runs, kept)) : null;
    this.base = { text: kept, runs: keptRuns };
    this.body = kept;
    if (keptRuns) this.runs = keptRuns;
    // Apple holds `kept`: ours, or ours with another device's edit combined. What is on screen
    // takes it in: unchanged if nothing was typed since, combined if something was.
    if (kept !== next.body) {
      const now = this.draft ?? next.body;
      const merged = now === next.body ? kept : (mergeEdits(next.body, now, kept) ?? now);
      if (merged !== now) {
        const laid = this.draftRuns ? carryRuns(now, this.draftRuns, merged) : null;
        const before = this.edits;
        this.editor?.replace(merged, laid);
        // A key typed this very moment came in instead, with its own save to follow.
        if (this.edits === before) {
          this.draftRuns = laid;
          this.draft = merged;
          if (merged !== kept) this.pending = { body: merged, runs: next.runs ? laid : null };
        }
      } else {
        this.draft = merged;
      }
    }
    this.events.saved(guid);
    this.events.changed();
  }
}


