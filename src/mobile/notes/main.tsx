/**
 * The Android app's note page: one note, read and edited on one surface with the desktop's
 * editor (src/screens/notes/NoteEditor.tsx). Folders, the list, the top bar and the format bar
 * are the app's own, in Compose; this page is the note. It owns the save session (session.ts)
 * and talks to the app only through `window.SkykeepHost` and `window.skykeepNotes` — the whole
 * contract is BRIDGE.md.
 */
import "../../styles/tokens.css";
import "../../styles/kit.css";
import "../../styles/notes.css";
import "./mobile.css";
import { StrictMode, useCallback, useRef, useSyncExternalStore } from "react";
import { createRoot } from "react-dom/client";
import { NoteEditor, PICTURE_EVENT, refreshTables, type EditorHandle, type EditorStatus, type PictureShown } from "../../screens/notes/NoteEditor";
import { LockScreen } from "../../screens/notes/LockedNote";
import { queryWords } from "../../screens/notes/found";
import { runsFit } from "../../screens/notes/rich";
import { linkedNote, outsideHref } from "../../lib/links";
import { api, notesLockApi, type UnlockedNote } from "../../lib/ipc";
import { setLanguage, t } from "../../lib/i18n";
import { host } from "./host";
import { runCommand } from "./commands";
import { NoteSession, type NoteHost } from "./session";

/** What the host opens (BRIDGE.md, `open`). */
export type OpenArgs = {
  /** The note; null for a new one, made with `note_create` only once something is typed. */
  guid: string | null;
  folderGuid?: string | null;
  editable?: boolean;
  locked?: boolean;
  /** The words of the search the note was opened from, marked where they fall. */
  found?: string[];
  theme?: "light" | "dark";
};

export type SkykeepNotes = {
  open: (args: OpenArgs) => void;
  command: (name: string, arg?: unknown) => boolean;
  flush: (token: unknown) => Promise<void>;
  refresh: () => void;
  attach: (path: string) => void;
  resolve: (id: number, ok: boolean, payloadJson: unknown) => void;
};

declare global {
  interface Window {
    skykeepNotes?: SkykeepNotes;
  }
}

/** The engine, through the host, under the desktop's command names (src/lib/ipc.ts). */
const engine: NoteHost = {
  body: (guid) => api.noteBody(guid),
  runs: (guid) => api.noteRuns(guid),
  save: (guid, body, base, runs, baseRuns) => api.noteSave(guid, body, base, runs, baseRuns),
  create: (folderGuid, body) => api.noteCreate(folderGuid, body),
  attach: (guid, path, atOffset, base) => notesLockApi.noteAttachFile(guid, path, atOffset, base),
};

/** What is open now, and how it is shown. */
type Opened =
  | { kind: "none" }
  | { kind: "note"; seq: number; args: OpenArgs; session: NoteSession }
  | { kind: "locked"; seq: number; args: OpenArgs & { guid: string }; unlocked: UnlockedNote | null; checking: boolean };

/** The page's state, kept outside React so the host's calls reach it before and after a draw. */
function createPage() {
  let opened: Opened = { kind: "none" };
  let seq = 0;
  let version = 0;
  const listeners = new Set<() => void>();
  const changed = () => {
    version += 1;
    listeners.forEach((l) => l());
  };
  const editor = { current: null as EditorHandle | null };
  let status: EditorStatus | null = null;
  let told = "";

  /** The format bar's state, told to the host only when it changed. */
  const tell = () => {
    const h = editor.current?.history() ?? { canUndo: false, canRedo: false };
    const s = opened.kind === "note" && opened.session.state === "ready" ? status : null;
    const next = {
      style: s?.style ?? null,
      bold: s?.bold ?? false,
      italic: s?.italic ?? false,
      underline: s?.underline ?? false,
      strike: s?.strike ?? false,
      checklist: s?.checklist ?? false,
      quote: s?.quote ?? false,
      colour: s?.colour ?? null,
      link: s?.link ?? false,
      canUndo: s ? h.canUndo : false,
      canRedo: s ? h.canRedo : false,
      dirty: opened.kind === "note" ? opened.session.dirty : false,
    };
    const json = JSON.stringify(next);
    if (json === told) return;
    told = json;
    host.event("host_status", next);
  };

  const unlock = (o: Extract<Opened, { kind: "locked" }>, note: UnlockedNote | null) => {
    if (opened !== o) return;
    opened = { ...o, unlocked: note, checking: false };
    changed();
  };

  const open = (args: OpenArgs) => {
    const was = opened;
    if (was.kind === "note") void was.session.close();
    seq += 1;
    status = null;
    editor.current = null;
    const theme = args.theme === "dark" ? "dark" : "light";
    document.documentElement.dataset.theme = theme;
    if (args.locked && args.guid) {
      const o: Opened = { kind: "locked", seq, args: { ...args, guid: args.guid }, unlocked: null, checking: true };
      opened = o;
      // Opened once already this session: the host still holds its password (as the desktop's
      // backend does), so it opens without asking again until Lock Now.
      notesLockApi.noteUnlock(args.guid, null).then(
        (note) => unlock(o, note),
        () => unlock(o, null),
      );
    } else {
      const session = new NoteSession(engine, {
        saved: (guid) => host.event("host_saved", { guid }),
        error: (message) => host.event("host_error", { message }),
        write: () => host.event("host_keyboard", {}),
        changed: () => {
          changed();
          tell();
        },
      }, { guid: args.guid, folderGuid: args.folderGuid ?? null });
      opened = { kind: "note", seq, args, session };
      void session.load();
    }
    changed();
    tell();
  };

  const lockNow = () => {
    notesLockApi.noteLockForget().catch(() => {});
    if (opened.kind === "locked") {
      opened = { ...opened, unlocked: null, checking: false };
      changed();
    }
  };

  const api_: SkykeepNotes = {
    open,
    command: (name, arg) => {
      if (name === "lockNow") {
        lockNow();
        return true;
      }
      if (name === "blur") {
        (document.activeElement as HTMLElement | null)?.blur?.();
        return true;
      }
      const e = editor.current;
      if (!e) return false;
      const done = runCommand(e, name, arg);
      tell();
      return done;
    },
    flush: async (token) => {
      const o = opened;
      if (o.kind === "note") await o.session.flush().catch(() => {});
      host.event("host_flushed", { token, unsaved: o.kind === "note" ? o.session.dirty : false });
    },
    refresh: () => {
      if (opened.kind === "note") void opened.session.refresh();
      // A table typed in on a phone shows its new cells without leaving the note.
      refreshTables();
    },
    attach: (path) => {
      if (opened.kind === "note") void opened.session.attach(path);
      else host.event("host_error", { message: "A file can't be added to this note." });
    },
    resolve: host.resolve,
  };

  return {
    api: api_,
    editor,
    snapshot: () => version,
    subscribe: (l: () => void) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    current: () => opened,
    setStatus: (s: EditorStatus) => {
      status = s;
      tell();
    },
    tell,
    unlocked: (o: Extract<Opened, { kind: "locked" }>, note: UnlockedNote) => unlock(o, note),
  };
}

type Page = ReturnType<typeof createPage>;

function NotesPage({ page }: { page: Page }) {
  useSyncExternalStore(page.subscribe, page.snapshot);
  const opened = page.current();
  const ref = useRef<EditorHandle | null>(null);
  const attach = useCallback(
    (e: EditorHandle | null) => {
      ref.current = e;
      page.editor.current = e;
      if (opened.kind === "note") opened.session.editor = e;
    },
    [opened, page],
  );

  if (opened.kind === "none") return <div className="m-page" />;

  if (opened.kind === "locked") {
    const { unlocked, checking, args } = opened;
    if (checking) return <div className="m-page" />;
    if (!unlocked) {
      return (
        <div className="m-page m-lock">
          <LockScreen guid={args.guid} title="" autoFocus onOpen={(note) => page.unlocked(opened, note)} />
        </div>
      );
    }
    return (
      <div className="m-page">
        <div className="note-editor is-readonly" data-editable="false">
          <NoteEditor
            key={`locked-${opened.seq}`}
            ref={attach}
            text={unlocked.body}
            runs={runsFit(unlocked.body, unlocked.runs) ? unlocked.runs : null}
            editable={false}
            label={t("notes.note")}
            onChange={() => {}}
            found={queryWords((args.found ?? []).join(" "))}
          />
          <p className="note-readonly m-readonly">{t("notes.lockedReadOnly")}</p>
        </div>
      </div>
    );
  }

  const { session, args } = opened;
  if (session.state === "loading") return <div className="m-page" aria-busy="true" />;
  if (session.state === "missing" || session.state === "failed") {
    return (
      <div className="m-page">
        <p className="m-state" role="status">
          {session.state === "missing" ? t("notes.textNotHere") : t("common.somethingWentWrong")}
        </p>
      </div>
    );
  }
  const editable = args.editable !== false;
  return (
    <div className="m-page">
      <div className="note-editor" data-editable={editable && !session.attaching ? "true" : "false"}>
        <NoteEditor
          key={`note-${opened.seq}`}
          ref={attach}
          text={session.initial.text}
          runs={session.initial.runs}
          editable={editable && !session.attaching}
          autoFocus={args.guid === null}
          label={args.guid === null ? t("notes.newNoteLabel") : t("notes.note")}
          placeholder={args.guid === null ? t("notes.newNote") : undefined}
          onChange={(text, runs) => {
            session.type(text, runs);
            page.tell();
          }}
          onStatus={page.setStatus}
          onBlur={() => void session.flush()}
          found={args.guid === null ? undefined : queryWords((args.found ?? []).join(" "))}
          writeAround
        />
        {editable && (
          // Room to write past the last thing in the note, as Notes leaves it, however tall that
          // thing is: a box of its own under the note, so a tap in it is never a tap on a picture
          // (followTaps puts the caret at the end, on a line of its own under an attachment).
          <div className="m-room" aria-hidden="true">
            <span className="m-room-hint">Tap to keep writing</span>
          </div>
        )}
      </div>
      {session.attaching && (
        // The note waits while a file goes in, so nothing typed meanwhile is lost when it
        // comes back from Apple with the file in place.
        <div className="note-adding m-adding" role="status" aria-live="polite">
          <div className="note-adding-card">
            <span className="note-adding-state">
              <span className="spinner is-small" aria-hidden="true" /> {t("notes.addingToNote")}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Taps the page hands to the host: a link (only the kinds Skykeep opens, lib/links.ts) and a
 * picture or drawing to show full screen. A link in a note being typed in is not followed on
 * the tap that places the caret in it: while the keyboard is up a tap is for editing, and the
 * link box (the format bar's link button) edits or removes it.
 */
function followTaps(page: Page) {
  let editingAtDown = false;
  const inEditor = (n: EventTarget | null) => (n as Element | null)?.closest?.(".ProseMirror[contenteditable='true']") ?? null;
  const onDown = (e: Event) => {
    const ed = inEditor(e.target);
    editingAtDown = Boolean(ed && ed.contains(document.activeElement));
  };
  // A tap on the note that no line took — under the last thing in it, over the first, in the
  // margin beside a picture — puts the caret where the finger is nearest, a line made to write
  // on past an attachment that ends (or starts) the note, and the keyboard comes up.
  const onMouseDown = (e: MouseEvent) => {
    const target = e.target as Element | null;
    const pane = target?.closest?.('.m-page .note-editor[data-editable="true"]');
    if (!pane || !target || !(target === pane || target.matches(".note-rich-host, .ProseMirror") || target.closest(".m-room"))) return;
    if (!page.editor.current?.tapAt(e.clientX, e.clientY)) return;
    e.preventDefault();
    host.event("host_keyboard", {});
  };
  const onClick = (e: MouseEvent) => {
    const a = (e.target as Element | null)?.closest?.("a[href]") as HTMLAnchorElement | null;
    if (!a) return;
    // Never the WebView's own navigation: the host refuses it anyway (BRIDGE.md).
    e.preventDefault();
    if (inEditor(a) && editingAtDown) return;
    const href = a.getAttribute("href") ?? "";
    const out = outsideHref(href) ?? (linkedNote(href) ? href : null);
    if (!out) return;
    host.event("host_open_link", { href: out });
    (document.activeElement as HTMLElement | null)?.blur?.();
  };
  const onPicture = (e: Event) => {
    const d = (e as CustomEvent<PictureShown>).detail;
    host.event("host_open_attachment", { id: d.id, kind: d.drawing ? "drawing" : "image", name: d.name });
  };
  // A right-click menu (a long press, on a phone) only where text is edited. A long press on a
  // picture, drawing, file or table in a note being written chooses it whole instead, drawn as
  // chosen, so Backspace takes it out — a single tap still opens it. A table's cells are their
  // own: a long press there is the cell's.
  const onMenu = (e: Event) => {
    if (e.defaultPrevented) return;
    const att = (e.target as Element | null)?.closest?.(".nf-attachment");
    if (att && !(e.target as Element).closest(".nf-cell-edit") && inEditor(att)) {
      e.preventDefault();
      if (page.editor.current?.choose(att)) host.event("host_keyboard", {});
      return;
    }
    const editable = (e.target as HTMLElement | null)?.closest?.("input, textarea, [contenteditable='true'], [contenteditable='plaintext-only']");
    if (!editable) e.preventDefault();
  };
  document.addEventListener("pointerdown", onDown, true);
  document.addEventListener("mousedown", onMouseDown, true);
  document.addEventListener("click", onClick, true);
  document.addEventListener("contextmenu", onMenu);
  window.addEventListener(PICTURE_EVENT, onPicture);
  return () => {
    document.removeEventListener("pointerdown", onDown, true);
    document.removeEventListener("mousedown", onMouseDown, true);
    document.removeEventListener("click", onClick, true);
    document.removeEventListener("contextmenu", onMenu);
    window.removeEventListener(PICTURE_EVENT, onPicture);
  };
}

/** The page in `container`, with `window.skykeepNotes` set; returns its undoing. */
export function mountNotesPage(container: HTMLElement): () => void {
  // English, as the rest of the Android app is.
  setLanguage("en");
  document.documentElement.dataset.app = "notes";
  const page = createPage();
  window.skykeepNotes = page.api;
  const stopTaps = followTaps(page);
  // Hidden or closed: what is typed is saved now, as the desktop does on blur and pagehide.
  const saveNow = () => {
    const o = page.current();
    if (o.kind === "note") void o.session.flush().catch(() => {});
  };
  const hidden = () => document.visibilityState === "hidden" && saveNow();
  document.addEventListener("visibilitychange", hidden);
  window.addEventListener("pagehide", saveNow);
  const root = createRoot(container);
  root.render(
    <StrictMode>
      <NotesPage page={page} />
    </StrictMode>,
  );
  // `window.skykeepNotes` answers from here on; the note is drawn as soon as it is opened.
  host.event("host_ready", {});
  window.dispatchEvent(new Event("skykeep:ready"));
  return () => {
    root.unmount();
    stopTaps();
    document.removeEventListener("visibilitychange", hidden);
    window.removeEventListener("pagehide", saveNow);
    if (window.skykeepNotes === page.api) delete window.skykeepNotes;
  };
}

const root = document.getElementById("root");
if (root) {
  mountNotesPage(root);
  // In the browser only (npm run dev:mobile-notes): a fake host with made-up notes.
  if (import.meta.env.MODE === "development" && !window.SkykeepHost) void import("./dev-host").then((m) => m.startDevHost());
}
