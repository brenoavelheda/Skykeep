# The note page and its Android host

The Android app's note screen hosts one `WebView` showing this page: the desktop's note
editor (`src/screens/notes/NoteEditor.tsx`) full-page, built by `npm run build:mobile-notes`
into `android/app/src/main/assets/notes-editor/` (generated, never committed). The page owns
the save session (`session.ts`, the desktop's rules); the host carries each call to the engine
and draws everything around the note (top bar, format bar, picture viewer).

This file is the contract. Where it differs from the plan's section B, the difference is
marked **(differs)**.

## Where the page lives

- Page: `https://appassets.androidplatform.net/assets/notes-editor/index.html`, served by
  `androidx.webkit`'s `WebViewAssetLoader` (its assets are relative: `./assets/…`).
- Note media: the page asks for
  `https://appassets.androidplatform.net/note-media/<encodeURIComponent(id)>/<part>`, `part`
  one of `file`, `inline`, `preview`, `drawing`, `drawing-dark`, `thumb`. The host answers with
  the bytes (`note_media(id, part)`), turning HEIC into JPEG for display (falling back to
  `preview`), or with **404** when there is none: the page then draws the file's card in the
  picture's place.
- The page carries a Content-Security-Policy (`default-src 'none'`, scripts and styles from the
  page itself, images from the page's origin, `data:` and `blob:`, no `connect-src`). The host
  still refuses every request to any other origin and cancels every navigation away from the
  page: the page never navigates on its own (links go through `host_open_link`).
- The page is in English and follows the theme the host gives in `open`. It reads the text
  size from the WebView (`WebSettings.textZoom` follows the person's font scale).
- `<meta name="viewport" … interactive-widget=resizes-content>`: the page shrinks above the
  keyboard, so the caret and the Add Link sheet stay in view. The host must let the WebView
  resize for the IME (`adjustResize` / insets).

## The envelope

Page to host: `window.SkykeepHost.post(json)` (a `@JavascriptInterface`), `json` being

```json
{"id": 7, "cmd": "note_save", "args": {"guid": "…", "body": "…", "base": "…", "runs": [], "baseRuns": []}}
```

- `id > 0`: a command; the host must answer it, exactly once.
- `id = 0`: an event; never answered.

Host to page: `window.skykeepNotes.resolve(id, ok, payloadJson)` (through
`evaluateJavascript`).

- `ok = true`: `payloadJson` is the answer as JSON **text** (`"null"` for none).
- `ok = false`: `payloadJson` is the error message as a JSON string, e.g. `"\"You're offline.\""`.
  The message is shown to the person (the lock screen matches "password" in it to say the
  password was wrong), so it should read as the desktop's do.

An answer to an unknown or already-answered id is ignored; an answer that is not JSON rejects
that command. Answers may come in any order.

## Commands (page to host)

The desktop's Tauri command names and argument names (`src/lib/ipc.ts`). Runs are always the
desktop's `Run` serde shape (`quince_icloud::ckdocument::Run`), lengths in UTF-16 units.

| cmd | args | answer |
|---|---|---|
| `note_body` | `{guid}` | the note's text, or `null` when the library holds none yet |
| `note_runs` | `{guid}` | `Run[]` (`[]` when none stored). A refusal opens the note plain |
| `note_save` | `{guid, body, base, runs, baseRuns}` | the text as kept (merged with another device's edit when there was one). `runs: null` saves the text alone, Apple's formatting carried |
| `note_create` | `{folderGuid, body}` | the new note's guid. Sent once, only after something is typed |
| `note_table` | `{id}` | `string[][]` or `null` |
| `note_table_set_cell` | `{id, row, column, text}` | `string[][]`, the table after |
| `note_attachment_info` | `{id}` | the desktop's `NoteAttachment` (`id, uti, kind, filename, title, width, height, size, hasPreview`) |
| `note_unlock` | `{guid, password}` | `{body, runs}`. `password: null` means "the one that opened it earlier this process" |
| `note_lock_hint` | `{guid}` | the hint, or `null` |
| `note_lock_forget` | `{}` | `null` |
| `note_attach_file` | `{guid, path, atOffset, base}` | the desktop's `NoteAttached` (`attachment, media, uti, offset, body`) |
| `clipboard_read` | `{}` | `{files: [], text}`: the Add Link sheet offers a copied address |

`note_attachment_open` is never sent: opening an attachment is the host's, and comes as the
`host_open_attachment` event **(differs: not a command)**. Table rows and columns are never
added or removed (`TABLE_LINE_WRITES = false`).

## Events (page to host)

| cmd | args | when |
|---|---|---|
| `host_ready` | `{}` | once, as soon as `window.skykeepNotes` answers (before any note is drawn) |
| `host_status` | `{style, bold, italic, underline, strike, checklist, quote, colour, link, canUndo, canRedo, dirty}` | when any of it changes: what the format bar lights. `style`: Notes' paragraph style number or `null` (body); `colour` 1–5 or `null`; `dirty`: typing not yet with the engine |
| `host_saved` | `{guid}` | after each save, create or file added lands. After a create it carries the new note's guid: the host's note is that one from then on |
| `host_flushed` | `{token, unsaved}` | answers `flush(token)` once what was pending has gone to the engine. `unsaved: true` when a save failed and the edit still waits (offline) **(differs: `unsaved` added)** |
| `host_open_link` | `{href}` | a link tapped while reading (see below) |
| `host_open_attachment` | `{id, kind, name}` | a picture, drawing, document or file tapped. `kind` is the attachment's (`image`, `drawing`, `document`, `video`, `audio`, `file`); `name` its file name or title, possibly `""` |
| `host_error` | `{message}` | a save, create, read or file add failed, in words to show |
| `host_keyboard` | `{}` | the page put the caret where the person writes next — a tap under, over or beside the lines, a long press choosing an attachment, a file just added — and focused the editor: the host gives the WebView the focus and brings the keyboard up **(differs: not in the plan)** |

Links: only addresses Skykeep opens go out (`http`, `https`, `mailto`, `tel`, `geo`, checked
by `src/lib/links.ts`, `href` normalised), plus `applenotes:note/<id>` links to another note,
passed as written. In a note being typed in (the keyboard up), a tap on a link places the caret
there rather than following it; the format bar's link button then edits or removes it
**(differs: the plan did not say)**.

## Methods (host to page), on `window.skykeepNotes`

- `open({guid, folderGuid, editable, locked, found, theme})` — shows a note, replacing any
  open one (whose typing is flushed first).
  - `guid: null`: a new note in `folderGuid`, made with `note_create` only once something is
    typed; the editor takes the focus.
  - `editable: false`: read-only (Recently Deleted, a note Skykeep cannot write); table cells
    are not editable either.
  - `locked: true`: the page first tries `note_unlock` with `password: null` (opened before
    this process) and otherwise shows the password form, the hint on request. An opened locked
    note is read-only.
  - `found`: the searched words; marked in the note (`queryWords`, as the desktop marks them).
  - `theme`: `"light"` or `"dark"`.
  - A note whose text is not held yet (`note_body` null) or cannot be read says so and opens
    on the next `refresh()`; it is never shown as an empty note to type into.
- `command(name, arg)` — returns `true` when done, `false` for a name or argument it does not
  know (or no note open). Names: `bold`, `italic`, `underline`, `strike`, `style` (arg: 0, 1,
  2, 3, 4, 100, 101, 102, 103 or `null`), `checklist`, `indent`, `outdent`, `moveUp`,
  `moveDown`, `quote`, `colour` (arg 1–5 or `null`), `link` (opens the Add Link sheet), `undo`,
  `redo`, `focus`, `blur`, `lockNow` (forgets the passwords, `note_lock_forget`, and shows the
  password form again). Format commands focus the editor, as the desktop's do; the host's
  buttons must not take the focus themselves, so the keyboard stays up. A mark or colour
  chosen with nothing selected holds for everything typed next — through a save landing, a
  status report, a re-read taken in, or the keyboard replacing the letter it typed — until it
  is chosen off or the caret leaves the line.
- `flush(token)` — sends what is typed now (the editor's last keys, the note made if it is new,
  the save) and then posts `host_flushed {token, unsaved}`. Asked before leaving the note, on
  pause/background, before rotation teardown. The page also saves by itself on
  `visibilitychange` (hidden) and `pagehide`.
- `refresh()` — the library changed (after a sync): the note is read again and taken in only
  when nothing is being typed and no save is on its way; tables are read again; a save that
  failed earlier is sent again.
- `attach(path)` — the page flushes, calls `note_attach_file` at its caret with the text on
  screen as `base`, then reads the note again and shows it with the file in place; the note
  cannot be typed in meanwhile. A caret the person placed is honoured; with none (the note
  only opened, a checklist circle tapped) the file goes in at the end of the note, on a line
  of its own. On a new note with nothing typed it posts `host_error` instead. Once the file is
  in, the caret goes on a new line right under it (an empty line there already, or one made to
  write on) and the page posts `host_keyboard`.
- `resolve(id, ok, payloadJson)` — answers a command (above).

## Writing around an attachment (src/screens/notes/around.ts)

- Under a note being written there is always room to tap, however tall its last thing is: a box
  of its own under the lines (`.m-room`), a third of the pane tall and never under 144px, so
  scrolled to the end a tap there is never a tap on a picture. Under a note that ends in an
  attachment it says "Tap to keep writing", in the placeholder's grey; under text, nothing.
- A tap that no line takes — in the room under the note, over its first line, in the margin
  beside a line — puts the caret where the finger is nearest. Under a picture, table, drawing or
  file that ends the note (or over one that starts it), an empty line is made to write on.
- A line made to write on is the note's only once something is written on it: the note is read
  back, saved and attached at without it, so a note only looked at is never changed or saved. A
  re-read keeps it where it was.
- A caret put for writing (a tap under the note, a file just added, a long press) is kept in
  view while the page moves under it — a picture drawn at its full height above it, the
  keyboard coming up — until the person touches or scrolls the page.
- The arrow keys go from text to just before an attachment, then just after it; Backspace just
  after one removes it. A single tap on one opens it (`host_open_attachment`); a long press
  chooses it whole, drawn with the accent ring (`data-chosen`), the caret just after it, for
  Backspace or Delete to remove — not the editor's node selection, in which a phone keyboard
  sees no text and its delete key only moved the caret. A table's cells, checklist circles and
  links are as before.

## The save session (what the host can rely on)

- At most one `note_save` (or `note_create`) is out at a time, in the order typed.
- Each save carries `base`/`baseRuns`: what Apple last confirmed (the text first read, then each
  answer). The engine merges against it.
- `runs` is `null` when the note's runs did not describe its text when read (stale, unreadable
  or malformed): the text alone is saved.
- A failed save stays queued and goes with the next pause in typing, `flush`, or `refresh`.

## Developing it

`npm run dev:mobile-notes` serves the page at `http://localhost:5274/` with a fake host
(`dev-host.ts`, made-up notes from `fixtures.ts`): `?note=formatted|media|locked|new|readonly`,
`&theme=dark`, `&found=word`. Its messages are logged to the console. `npm test` runs the
page's tests (Vitest, jsdom).
