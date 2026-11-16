/**
 * Writing around an attachment, as Notes on a phone lets one (the phone's note page turns this
 * on; the desktop's editor never does). A picture, a table or a file stands on a line of its own
 * and is opened by a tap, so on a touch screen there was nowhere to put the caret after one that
 * ended the note, and no line to type on below it. Here:
 *
 * - a line to write on — the spare line — is made where the note has none: under the last thing
 *   in it, over a first one, under a file just added. It is the note's only once something is
 *   written on it: until then the note is read back without it, so a note only looked at is
 *   never changed or saved;
 * - the arrow keys go from text to just before an attachment, then just after it, and back;
 * - Backspace just after an attachment removes it, as Notes does;
 * - a tap beside, over or under the lines (the margin, the room under the note) puts the caret
 *   where the finger is nearest;
 * - a caret put there for writing stays in view while the page moves under it — a picture
 *   landing at its full height above it, the keyboard coming up — until the person scrolls.
 */
import { NodeSelection, Plugin, PluginKey, Selection, TextSelection, type Command, type EditorState, type Transaction } from "prosemirror-state";
import { keymap } from "prosemirror-keymap";
import { Decoration, DecorationSet, type EditorView } from "prosemirror-view";
import { Fragment, type Node as PMNode } from "prosemirror-model";
import { schema } from "./rich";

/** Where the spare line starts (the position before it), or null with none. */
export const spareKey = new PluginKey<number | null>("spare-line");

const isAttachment = (node: PMNode | null | undefined) => node?.type === schema.nodes.attachment;
const emptyLine = (node: PMNode | null | undefined) => node?.type === schema.nodes.paragraph && node.content.size === 0;

const spareLine = new Plugin<number | null>({
  key: spareKey,
  state: {
    init: () => null,
    apply(tr, at, before, after) {
      const made = tr.getMeta(spareKey) as number | null | undefined;
      if (made !== undefined) return made;
      if (at === null || !tr.docChanged) return at;
      const moved = tr.mapping.map(at, 1);
      // Gone with what was deleted round it (Backspace joined it to the line above).
      if (!emptyLine(after.doc.nodeAt(moved)) || after.doc.resolve(moved).depth !== 0) return null;
      // Return pressed on it, or lines pasted into it: there is now more than the one line, and
      // it is a line the person made, kept as they see it.
      const own = tr.getMeta("addToHistory") !== false;
      const $from = before.selection.$from;
      const inIt = $from.depth === 1 && $from.before() === at;
      if (own && inIt && after.doc.childCount !== before.doc.childCount) return null;
      return moved;
    },
  },
});

/** The spare line's start while nothing is written on it; null with none, or once written on. */
export function spareAt(state: EditorState): number | null {
  const at = spareKey.getState(state);
  if (at === null || at === undefined) return null;
  return emptyLine(state.doc.nodeAt(at)) ? at : null;
}

/** The note as it is read back and saved: without a spare line nothing is written on. */
export function keptDoc(state: EditorState): PMNode {
  const at = spareAt(state);
  if (at === null) return state.doc;
  return withLines(state.doc, at, 1);
}

/** `doc` with an empty line put in at `at` (between two lines, `count` 0), or the line there
 *  taken out (`count` 1). */
function withLines(doc: PMNode, at: number, count: 0 | 1): PMNode {
  const lines: PMNode[] = [];
  doc.forEach((line) => lines.push(line));
  lines.splice(doc.resolve(at).index(0), count, ...(count ? [] : [schema.nodes.paragraph.create()]));
  return doc.copy(Fragment.fromArray(lines));
}

/** `pos` in the note as `keptDoc` has it. */
export function keptPos(state: EditorState, pos: number): number {
  const at = spareAt(state);
  if (at === null || pos <= at) return pos;
  const size = state.doc.nodeAt(at)!.nodeSize;
  return Math.max(at, pos - size);
}

/**
 * The note `doc`, Apple's copy taken in over `state`, with the spare line carried into it where
 * it was — when the change is not where it stands. Null: no spare line to carry, or nowhere to
 * put it. Answers the document and where the line is in it.
 */
export function carrySpare(state: EditorState, doc: PMNode): { doc: PMNode; at: number } | null {
  const at = spareAt(state);
  if (at === null) return null;
  const kept = keptDoc(state);
  const start = kept.content.findDiffStart(doc.content);
  const end = kept.content.findDiffEnd(doc.content);
  let place: number;
  if (start === null || !end) place = at;
  else if (at <= start) place = at;
  else if (at >= end.a) place = at + (end.b - end.a);
  else return null;
  if (place > doc.content.size || doc.resolve(place).depth !== 0) return null;
  return { doc: withLines(doc, place, 0), at: place };
}

/** A spare line made at `at` (between two lines, or at either end), the caret on it. One made
 *  before and still not written on goes: only looked at, it was never the note's. */
function makeSpare(state: EditorState, at: number): Transaction {
  const tr = state.tr;
  const old = spareAt(state);
  if (old !== null) tr.delete(old, old + state.doc.nodeAt(old)!.nodeSize);
  const place = tr.mapping.map(at);
  tr.insert(place, schema.nodes.paragraph.create());
  tr.setSelection(TextSelection.create(tr.doc, place + 1));
  return writing(tr.setMeta(spareKey, place).setMeta("addToHistory", false));
}

/** Whether `line` ends (or starts) with something that stands on its own: a picture, a table,
 *  a file — not a #tag or @mention, which sits in its line as words. */
function standsAlone(view: EditorView, line: PMNode, linePos: number, edge: "start" | "end"): boolean {
  const child = edge === "end" ? line.lastChild : line.firstChild;
  if (!isAttachment(child)) return false;
  const pos = edge === "end" ? linePos + line.nodeSize - 1 - child!.nodeSize : linePos + 1;
  const dom = view.nodeDOM(pos) as HTMLElement | null;
  return !dom?.classList?.contains("is-inline");
}

/**
 * The caret at the end of the note (`"end"`) or its start (`"start"`); a spare line made first
 * when the last (or first) thing in it is an attachment. Answers the transaction to dispatch.
 */
export function writeAtEdge(view: EditorView, edge: "start" | "end"): Transaction {
  const { state } = view;
  const { doc } = state;
  const line = edge === "end" ? doc.lastChild! : doc.firstChild!;
  const linePos = edge === "end" ? doc.content.size - line.nodeSize : 0;
  if (standsAlone(view, line, linePos, edge)) return makeSpare(state, edge === "end" ? doc.content.size : 0);
  const sel = edge === "end" ? Selection.atEnd(doc) : Selection.atStart(doc);
  return writing(state.tr.setSelection(sel));
}

/**
 * The caret on the line under attachment `id` (a file just added): an empty line there already
 * is used, or else a spare line is made. Null when the attachment is not in the note.
 */
export function writeAfter(view: EditorView, id: string): Transaction | null {
  const { state } = view;
  let found: number | null = null;
  state.doc.forEach((line, offset) => {
    if (found !== null) return;
    line.forEach((child) => {
      if (isAttachment(child) && child.attrs.id === id) found = offset;
    });
  });
  if (found === null) return null;
  const end = found + state.doc.nodeAt(found)!.nodeSize;
  if (emptyLine(state.doc.nodeAt(end))) return writing(state.tr.setSelection(TextSelection.create(state.doc, end + 1)));
  return makeSpare(state, end);
}

/**
 * A tap the editor's lines did not take — in the margin beside them, or over or under them —
 * as a caret: over the first line, its start; under the last, its end (writeAtEdge); level
 * with a line holding an attachment alone, just before it or just after it, by the side of the
 * line the finger is on; level with a line of text, where the finger is nearest in it.
 */
export function tapAt(view: EditorView, x: number, y: number): Transaction | null {
  const lines = [...view.dom.children].filter((c): c is HTMLElement => c instanceof HTMLElement && c.tagName === "P");
  if (!lines.length) return null;
  if (y < lines[0].getBoundingClientRect().top) return writeAtEdge(view, "start");
  if (y >= lines[lines.length - 1].getBoundingClientRect().bottom) return writeAtEdge(view, "end");
  const line = lines.find((l) => {
    const r = l.getBoundingClientRect();
    return y >= r.top && y < r.bottom;
  });
  if (!line) return null;
  const start = view.posAtDOM(line, 0);
  const node = view.state.doc.resolve(start).parent;
  const box = line.getBoundingClientRect();
  if (node.childCount > 0 && [...Array(node.childCount).keys()].every((i) => isAttachment(node.child(i)))) {
    const pos = x < (box.left + box.right) / 2 ? start : start + node.content.size;
    return writing(view.state.tr.setSelection(TextSelection.create(view.state.doc, pos)));
  }
  const near = view.posAtCoords({ left: Math.min(Math.max(x, box.left + 1), box.right - 1), top: y });
  if (!near) return null;
  return writing(view.state.tr.setSelection(TextSelection.near(view.state.doc.resolve(near.pos))));
}

/**
 * An attachment chosen whole by a long press: drawn as chosen (`data-chosen`), the caret just
 * after it. Not the editor's node selection: on a phone the keyboard sees no text in a
 * selection of a picture, and its delete key only moved the caret into it; with the caret after
 * the picture, delete takes the picture out (removeBefore), as it does for one typed past. The
 * choice goes with the next change or move of the caret.
 */
const chosenKey = new PluginKey<number | null>("chosen-attachment");
const chosen = new Plugin<number | null>({
  key: chosenKey,
  state: {
    init: () => null,
    apply(tr, at, before) {
      const made = tr.getMeta(chosenKey) as number | null | undefined;
      if (made !== undefined) return made;
      if (at === null || tr.docChanged || !tr.selection.eq(before.selection)) return null;
      return at;
    },
  },
  props: {
    decorations(state) {
      const at = chosenKey.getState(state);
      const node = at === null || at === undefined ? null : state.doc.nodeAt(at);
      if (at === null || at === undefined || !isAttachment(node)) return DecorationSet.empty;
      return DecorationSet.create(state.doc, [Decoration.node(at, at + node!.nodeSize, { "data-chosen": "true" })]);
    },
  },
});

/** Attachment `dom` chosen whole, as a long press chooses one. Null when it is not the note's. */
export function chooseAttachment(view: EditorView, dom: Element): Transaction | null {
  let pos: number;
  try {
    pos = view.posAtDOM(dom, 0) - 1;
  } catch {
    return null;
  }
  // posAtDOM inside an atom answers the position after it is opened; the node itself is one
  // place back — or, on some lines, the position it gives is the node's own.
  for (const at of [pos, pos + 1]) {
    const node = at >= 0 ? view.state.doc.nodeAt(at) : null;
    if (isAttachment(node) && view.nodeDOM(at) === dom) {
      const tr = view.state.tr.setSelection(TextSelection.create(view.state.doc, at + node!.nodeSize));
      return writing(tr.setMeta(chosenKey, at));
    }
  }
  return null;
}

/**
 * Whether the caret was put for writing (a tap under the note, a file just added, a long press)
 * and is kept in view while the page moves under it. A picture comes in at its full height
 * only once the note is drawn, and the keyboard comes up after the caret is put: either can
 * leave the caret under the keyboard or below the screen, the person looking at the middle of
 * a picture. The editor's own scrolling follows typing; this follows the page. It lets go when
 * the person touches or scrolls the page themselves.
 */
const followKey = new PluginKey<boolean>("follow-caret");

/** `tr`, a caret put for writing: scrolled into view now, and kept there (followKey). */
function writing(tr: Transaction): Transaction {
  return tr.setMeta(followKey, true).scrollIntoView();
}

const follow = new Plugin<boolean>({
  key: followKey,
  state: {
    init: () => false,
    apply: (tr, on) => (tr.getMeta(followKey) as boolean | undefined) ?? on,
  },
  props: {
    // The caret brought into view with its whole line and a little of what is under it, not
    // its glyph's height flush with the keyboard's edge.
    scrollMargin: { top: 16, bottom: 32, left: 5, right: 5 },
  },
  view(view) {
    const keep = () => {
      if (followKey.getState(view.state) && view.hasFocus()) view.dispatch(view.state.tr.scrollIntoView());
    };
    const letGo = () => {
      if (followKey.getState(view.state)) view.dispatch(view.state.tr.setMeta(followKey, false));
    };
    const win = view.dom.ownerDocument.defaultView;
    const doc = view.dom.ownerDocument;
    // The note growing or shrinking (a picture drawn at its size), and the page itself (the
    // keyboard up or down: the WebView is resized above it).
    const grown = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(keep);
    grown?.observe(view.dom);
    win?.addEventListener("resize", keep);
    win?.visualViewport?.addEventListener("resize", keep);
    // A finger on the page or a wheel: the person scrolls, or taps somewhere new (a tap that
    // puts the caret for writing comes after its touch, and follows again).
    const hands = ["pointerdown", "touchstart", "wheel"] as const;
    for (const h of hands) doc.addEventListener(h, letGo, { capture: true, passive: true });
    return {
      destroy() {
        grown?.disconnect();
        win?.removeEventListener("resize", keep);
        win?.visualViewport?.removeEventListener("resize", keep);
        for (const h of hands) doc.removeEventListener(h, letGo, { capture: true });
      },
    };
  },
});

/** Arrow Right and Left beside an attachment: to just before it, then just after, never into it. */
function across(dir: 1 | -1): Command {
  return (state, dispatch) => {
    const sel = state.selection;
    let to: number | null = null;
    if (sel instanceof NodeSelection && isAttachment(sel.node)) {
      to = dir > 0 ? sel.to : sel.from;
    } else if (sel instanceof TextSelection && sel.empty) {
      const $h = sel.$head;
      const beside = dir > 0 ? $h.nodeAfter : $h.nodeBefore;
      if (isAttachment(beside)) to = $h.pos + dir * beside!.nodeSize;
      else if ($h.depth === 1 && $h.parentOffset === (dir > 0 ? $h.parent.content.size : 0)) {
        // At the end of a line, and the next line starts with an attachment — or this one ends
        // with one — onto that line's start (and the same going back): never lost round it.
        const next = dir > 0 ? state.doc.nodeAt($h.after()) : state.doc.resolve($h.before()).nodeBefore;
        const edge = dir > 0 ? next?.firstChild : next?.lastChild;
        const own = dir > 0 ? $h.parent.lastChild : $h.parent.firstChild;
        if (next && (isAttachment(edge) || isAttachment(own))) to = dir > 0 ? $h.after() + 1 : $h.before() - 1;
      }
    }
    if (to === null) return false;
    dispatch?.(state.tr.setSelection(TextSelection.create(state.doc, to)).scrollIntoView());
    return true;
  };
}

/** Delete with an attachment chosen removes it. */
const removeChosen: Command = (state, dispatch) => {
  const at = chosenKey.getState(state);
  const node = at === null || at === undefined ? null : state.doc.nodeAt(at);
  if (at === null || at === undefined || !isAttachment(node)) return false;
  dispatch?.(state.tr.delete(at, at + node!.nodeSize).scrollIntoView());
  return true;
};

/** Backspace just after an attachment removes it, as Notes does. */
const removeBefore: Command = (state, dispatch) => {
  const sel = state.selection;
  if (!(sel instanceof TextSelection) || !sel.empty) return false;
  const before = sel.$head.nodeBefore;
  if (!isAttachment(before)) return false;
  dispatch?.(state.tr.delete(sel.head - before!.nodeSize, sel.head).scrollIntoView());
  return true;
};

/** The editor's plugins for writing around attachments, ahead of its own keys. */
export const aroundPlugins = [spareLine, chosen, follow, keymap({ ArrowRight: across(1), ArrowLeft: across(-1), Backspace: removeBefore, Delete: removeChosen })];
