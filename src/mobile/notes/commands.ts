import type { EditorHandle } from "../../screens/notes/NoteEditor";
import { STYLE } from "../../screens/notes/rich";

/** Notes' paragraph styles a command may set (null: body text). */
const STYLES: readonly number[] = Object.values(STYLE);

/**
 * A format command from the phone's own format bar, run on the editor as the desktop's Aa
 * panel and keys run it (BRIDGE.md, `command`). The page's own commands (`blur`, `lockNow`)
 * are main.tsx's. False for a name or argument this editor does not know.
 */
export function runCommand(editor: EditorHandle, name: string, arg?: unknown): boolean {
  switch (name) {
    case "bold":
    case "italic":
    case "underline":
    case "strike":
      editor.toggleMark(name);
      return true;
    case "style":
      if (arg !== null && arg !== undefined && !(typeof arg === "number" && STYLES.includes(arg))) return false;
      editor.setStyle(typeof arg === "number" ? arg : null);
      return true;
    case "checklist":
      editor.toggleChecklist();
      return true;
    case "indent":
      editor.indent(1);
      return true;
    case "outdent":
      editor.indent(-1);
      return true;
    case "moveUp":
      editor.moveLines(-1);
      return true;
    case "moveDown":
      editor.moveLines(1);
      return true;
    case "quote":
      editor.toggleQuote();
      return true;
    case "colour":
      if (arg !== null && arg !== undefined && !(typeof arg === "number" && Number.isInteger(arg) && arg >= 1 && arg <= 5)) return false;
      editor.setColour(typeof arg === "number" ? arg : null);
      return true;
    case "link":
      editor.openLink();
      return true;
    case "undo":
      editor.undo();
      return true;
    case "redo":
      editor.redo();
      return true;
    case "focus":
      editor.focus();
      return true;
    default:
      return false;
  }
}
