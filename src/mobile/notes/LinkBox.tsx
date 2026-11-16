import { useEffect, useRef, useState } from "react";
import { Link, Type } from "lucide-react";
import { Portal } from "../../components/Portal";
import { useI18n } from "../../lib/i18n";
import { api } from "../../lib/ipc";
import { linkAddress } from "../../screens/notes/linking";
import type { LinkAsk } from "../../screens/notes/LinkBox";

export type { LinkAsk };

/**
 * Add Link on the phone, in place of the desktop's popover (vite.mobile.config.ts answers the
 * note editor's `./LinkBox` with this one). A popover placed under the selection sat under the
 * keyboard, and the desktop's closes itself whenever the window is resized — which on a phone
 * is every time the keyboard comes up. So: a sheet docked at the foot of the page, above the
 * keyboard (the page resizes for it, BRIDGE.md), its fields labelled, and buttons a finger can
 * hit — Done, Cancel, and Remove Link on a link already there. Tapping outside it cancels.
 */
export function LinkBox({
  ask,
  onDone,
  onRemove,
  onCancel,
}: {
  ask: LinkAsk;
  onDone: (href: string, text: string) => void;
  onRemove: () => void;
  onCancel: (back: boolean) => void;
}) {
  const { t } = useI18n();
  const [address, setAddress] = useState(ask.href ?? "");
  const [text, setText] = useState("");
  const [wrong, setWrong] = useState(false);
  const field = useRef<HTMLInputElement | null>(null);

  // A link just copied is what is wanted nine times in ten: offered in the field, selected.
  useEffect(() => {
    if (ask.href) return;
    let live = true;
    api.clipboardRead().then(
      ({ text: copied }) => {
        const offered = copied?.trim() ?? "";
        if (!live || !offered || offered.length > 2000 || !linkAddress(offered)) return;
        setAddress((now) => {
          if (now) return now;
          requestAnimationFrame(() => field.current?.select());
          return offered;
        });
      },
      () => {},
    );
    return () => {
      live = false;
    };
  }, [ask.href]);

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        onCancel(true);
      }
    };
    window.addEventListener("keydown", key);
    return () => window.removeEventListener("keydown", key);
  }, [onCancel]);

  const title = ask.href ? t("notes.editLink") : t("notes.addLink");
  const done = () => {
    const href = linkAddress(address);
    if (!href) {
      setWrong(true);
      return;
    }
    onDone(href, text);
  };
  return (
    <Portal>
      <div className="m-sheet-scrim" onPointerDown={(e) => e.target === e.currentTarget && onCancel(false)}>
        <form
          className="m-sheet m-link"
          role="dialog"
          aria-modal="true"
          aria-labelledby="m-link-title"
          onSubmit={(e) => {
            e.preventDefault();
            done();
          }}
        >
          <h2 id="m-link-title" className="m-sheet-title">{title}</h2>
          <label className="m-field">
            <span className="m-field-label">{t("notes.linkTo")}</span>
            <span className="m-field-box">
              <Link size={18} strokeWidth={2} aria-hidden="true" />
              <input
                ref={field}
                autoFocus
                value={address}
                type="url"
                inputMode="url"
                enterKeyHint="done"
                spellCheck={false}
                autoCapitalize="off"
                autoComplete="off"
                placeholder={t("notes.linkPlaceholder")}
                aria-invalid={wrong}
                aria-describedby={wrong ? "m-link-wrong" : undefined}
                onChange={(e) => {
                  setAddress(e.target.value);
                  setWrong(false);
                }}
              />
            </span>
          </label>
          {ask.needsText && (
            <label className="m-field">
              <span className="m-field-label">{t("notes.linkText")}</span>
              <span className="m-field-box">
                <Type size={18} strokeWidth={2} aria-hidden="true" />
                <input value={text} enterKeyHint="done" placeholder={t("notes.linkTextPlaceholder")} onChange={(e) => setText(e.target.value)} />
              </span>
            </label>
          )}
          {wrong && (
            <p id="m-link-wrong" className="m-sheet-wrong" role="alert">
              {t("notes.linkWrong")}
            </p>
          )}
          <div className="m-sheet-actions">
            {ask.href && (
              <button type="button" className="m-button is-danger m-link-remove" onClick={onRemove}>
                {t("notes.removeLink")}
              </button>
            )}
            <button type="button" className="m-button" onClick={() => onCancel(true)}>
              {t("common.cancel")}
            </button>
            <button type="submit" className="m-button is-primary">
              {t("common.done")}
            </button>
          </div>
        </form>
      </div>
    </Portal>
  );
}
