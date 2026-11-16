/**
 * A fake Android host, for the browser and the tests only — never in the APK (main.tsx loads it
 * only under `npm run dev:mobile-notes`, and the build leaves it out). It answers the page's
 * commands as the real host does (BRIDGE.md) from made-up notes held in memory (fixtures.ts).
 *
 * In the browser: http://localhost:5274/?note=formatted|media|locked|new&theme=light|dark
 */
import type { Run } from "../../screens/notes/Formatted";
import type { NoteAttached, NoteAttachment } from "../../lib/ipc";
import { carryRuns, runsFit } from "../../screens/notes/rich";
import { DEV_ATTACHMENTS, DEV_NOTES, DEV_TABLES, type DevNote } from "./fixtures";
import { host as pageHost, type Host } from "./host";

export type Sent = { id: number; cmd: string; args: Record<string, unknown> };

export type DevHost = {
  /** Every message the page posted, in order. */
  sent: Sent[];
  notes: Map<string, DevNote>;
  /** Commands answered with this refusal instead, by name. */
  refuse: Map<string, string>;
  /** Answers held back until `release()`, when on. */
  hold: boolean;
  release: () => void;
  events: (cmd: string) => Record<string, unknown>[];
};

const plainRun = (length: number): Run => ({
  length, style: null, indent: 0, checked: null, bold: false, italic: false, underline: false, strike: false, link: null,
});

export function installDevHost(opts: { delayMs?: number; page?: Host } = {}): DevHost {
  const page = opts.page ?? pageHost;
  const notes = new Map(DEV_NOTES.map((n) => [n.guid, { ...n, runs: n.runs.map((r) => ({ ...r })) }]));
  const tables = new Map(Object.entries(DEV_TABLES).map(([k, v]) => [k, v.map((r) => [...r])]));
  const attachments = new Map<string, NoteAttachment>(Object.entries(DEV_ATTACHMENTS));
  const opened = new Set<string>();
  let made = 0;
  const held: (() => void)[] = [];

  const answer = (cmd: string, a: Record<string, unknown>): unknown => {
    const note = (guid: unknown) => {
      const n = notes.get(String(guid));
      if (!n) throw "That note isn't in the library.";
      return n;
    };
    switch (cmd) {
      case "note_body": {
        const n = notes.get(String(a.guid));
        return n && !n.locked ? n.text : null;
      }
      case "note_runs":
        return notes.get(String(a.guid))?.runs ?? [];
      case "note_save": {
        const n = note(a.guid);
        const body = String(a.body);
        const runs = a.runs as Run[] | null;
        // As the engine keeps them: the runs sent, or Apple's carried over the new text.
        n.runs = runs && runsFit(body, runs) ? runs : carryRuns(n.text, n.runs, body);
        n.text = body;
        return body;
      }
      case "note_create": {
        made += 1;
        const guid = `DEV-NEW-${made}`;
        const body = String(a.body);
        notes.set(guid, { guid, folderGuid: (a.folderGuid as string | null) ?? null, text: body, runs: [plainRun(body.length)] });
        return guid;
      }
      case "note_table":
        return tables.get(String(a.id)) ?? null;
      case "note_table_set_cell": {
        const cells = tables.get(String(a.id));
        if (!cells?.[Number(a.row)]) throw "That table isn't in the note any more.";
        cells[Number(a.row)][Number(a.column)] = String(a.text);
        return cells;
      }
      case "note_attachment_info": {
        const found = attachments.get(String(a.id));
        if (!found) throw "That attachment isn't in the library.";
        return found;
      }
      case "note_unlock": {
        const n = note(a.guid);
        if (!n.locked) return { body: n.text, runs: n.runs };
        const typed = a.password as string | null;
        if (typed === null || typed === undefined) {
          if (!opened.has(n.guid)) throw "Enter this note's password.";
        } else if (typed !== n.locked.password) {
          throw "That password is wrong.";
        }
        opened.add(n.guid);
        return { body: n.text, runs: n.runs };
      }
      case "note_lock_hint":
        return note(a.guid).locked?.hint ?? null;
      case "note_lock_forget":
        opened.clear();
        return null;
      case "note_attach_file": {
        const n = note(a.guid);
        const base = typeof a.base === "string" ? a.base : n.text;
        const at = Math.max(0, Math.min(Number(a.atOffset) || 0, base.length));
        made += 1;
        const id = `ATT-${made}`;
        attachments.set(id, { id, uti: "public.jpeg", kind: "image", filename: String(a.path).split(/[\\/]/).pop() ?? "File", title: null, width: 1600, height: 1000, size: 1000, hasPreview: false });
        const body = `${base.slice(0, at)}\ufffc${base.slice(at)}`;
        const carried = carryRuns(base, runsFit(base, n.runs) ? n.runs : [plainRun(base.length)], body);
        // The new character stands for the file: a run of its own.
        const laid: Run[] = [];
        let pos = 0;
        for (const r of carried) {
          if (at >= pos && at < pos + r.length) {
            const before = at - pos;
            if (before) laid.push({ ...r, length: before });
            laid.push({ ...r, length: 1, attachment: id });
            if (r.length - before - 1) laid.push({ ...r, length: r.length - before - 1 });
          } else laid.push(r);
          pos += r.length;
        }
        n.text = body;
        n.runs = laid;
        const done: NoteAttached = { attachment: id, media: id, uti: "public.jpeg", offset: at, body };
        return done;
      }
      case "clipboard_read":
        return { files: [], text: null };
      default:
        throw `The fake host has no ${cmd}.`;
    }
  };

  const dev: DevHost = {
    sent: [],
    notes,
    refuse: new Map(),
    hold: false,
    release: () => held.splice(0).forEach((f) => f()),
    events: (cmd) => dev.sent.filter((s) => s.id === 0 && s.cmd === cmd).map((s) => s.args),
  };
  window.SkykeepHost = {
    post: (json: string) => {
      const msg = JSON.parse(json) as Sent;
      dev.sent.push(msg);
      if (msg.id === 0) {
        if (opts.delayMs !== undefined) console.info("[skykeep host] event", msg.cmd, msg.args);
        return;
      }
      const reply = () => {
        const refused = dev.refuse.get(msg.cmd);
        if (refused) return page.resolve(msg.id, false, JSON.stringify(refused));
        try {
          page.resolve(msg.id, true, JSON.stringify(answer(msg.cmd, msg.args) ?? null));
        } catch (e) {
          page.resolve(msg.id, false, JSON.stringify(typeof e === "string" ? e : String(e)));
        }
      };
      const go = () => (opts.delayMs ? setTimeout(reply, opts.delayMs) : queueMicrotask(reply));
      if (dev.hold) held.push(go);
      else go();
    },
  };
  return dev;
}

/** The browser's dev page: a fake host, and a note opened as the query asks. */
export function startDevHost() {
  installDevHost({ delayMs: 120 });
  const q = new URLSearchParams(window.location.search);
  const which = q.get("note") ?? "formatted";
  const guid = which === "new" ? null : `DEV-${which.toUpperCase()}`;
  const theme = q.get("theme") === "dark" ? "dark" : "light";
  const open = () =>
    window.skykeepNotes?.open({ guid, folderGuid: null, editable: which !== "readonly", locked: which === "locked", found: q.getAll("found"), theme });
  if (window.skykeepNotes) open();
  else window.addEventListener("skykeep:ready", open, { once: true });
}
