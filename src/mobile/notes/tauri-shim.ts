/**
 * `@tauri-apps/api/core` and `@tauri-apps/api/event` in the phone's note page (aliased in
 * vite.mobile.config.ts). Every command the desktop's notes code makes through src/lib/ipc.ts
 * goes to the Android host under its own name and with its own arguments; media addresses
 * become the ones the host serves. Two things only are the page's own:
 *   - opening an attachment is the host's to do, so it is told as an event (what was tapped,
 *     its kind and its name) rather than asked as a command;
 *   - no desktop event ever comes, so `listen` waits for nothing.
 */
import { host } from "./host";

/** The origin the host serves the page and its media from (androidx.webkit's asset loader). */
export const APP_ORIGIN = "https://appassets.androidplatform.net";

type Described = { kind: string; filename: string | null; title: string | null };
/** What each attachment is, as the host last said: what a tap on one tells the host. */
const described = new Map<string, Described>();

export async function invoke<T>(cmd: string, args: Record<string, unknown> = {}): Promise<T> {
  if (cmd === "note_attachment_open") {
    const id = String(args.id ?? "");
    const info = described.get(id);
    host.event("host_open_attachment", { id, kind: info?.kind ?? "file", name: info?.filename ?? info?.title ?? "" });
    return undefined as T;
  }
  const answer = await host.call<T>(cmd, args);
  if (cmd === "note_attachment_info" && answer && typeof answer === "object") {
    const a = answer as unknown as Described;
    described.set(String(args.id ?? ""), { kind: a.kind, filename: a.filename ?? null, title: a.title ?? null });
  }
  return answer;
}

/** What an attachment is, as the host said: for a picture tapped (main.tsx). */
export const describedAs = (id: string): Described | undefined => described.get(id);

/**
 * A file the desktop serves under its own scheme, as the host serves it here. A note's media
 * (`note/<id>/<part>`) is `/note-media/<id>/<part>`; nothing else in the notes code asks.
 * On the page's own origin, so in the browser (npm run dev:mobile-notes) the dev server
 * answers it from the fixtures.
 */
export function convertFileSrc(path: string, _protocol = "asset"): string {
  const origin = import.meta.env.MODE === "development" ? "" : APP_ORIGIN;
  const m = /^note\/(.+)\/([a-z-]+)$/.exec(path);
  if (m) return `${origin}/note-media/${encodeURIComponent(m[1])}/${m[2]}`;
  return `${origin}/${path.split("/").map(encodeURIComponent).join("/")}`;
}

export type UnlistenFn = () => void;
export async function listen<T>(_event: string, _handler: (e: { payload: T }) => void): Promise<UnlistenFn> {
  return () => {};
}
export async function emit(_event: string, _payload?: unknown): Promise<void> {}
