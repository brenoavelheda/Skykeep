/**
 * The envelope between this page and the Android app that hosts it (BRIDGE.md). The page posts
 * `{"id", "cmd", "args"}` as JSON text to `window.SkykeepHost.post`; a command (`id > 0`) is
 * answered with `window.skykeepNotes.resolve(id, ok, payloadJson)`, an event (`id = 0`) is not.
 * Every command waits on its own id, so answers may come in any order.
 */

/** What the Android app puts on the page: one method, taking JSON text. */
export type HostBridge = { post: (json: string) => void };

declare global {
  interface Window {
    SkykeepHost?: HostBridge;
  }
}

export type Host = {
  /** A command, answered by the host: its payload, or its refusal as the message it gave. */
  call: <T>(cmd: string, args?: Record<string, unknown>) => Promise<T>;
  /** An event: told, never answered. */
  event: (cmd: string, args?: Record<string, unknown>) => void;
  /** The host's answer to command `id`: `payloadJson` is the answer as JSON text, or with `ok`
   *  false the error message, JSON-encoded. */
  resolve: (id: number, ok: boolean, payloadJson: unknown) => void;
};

const NO_HOST = "The note editor is not connected to its host.";

export function createHost(bridge: () => HostBridge | undefined = () => window.SkykeepHost): Host {
  let next = 1;
  const waiting = new Map<number, { resolve: (v: unknown) => void; reject: (e: unknown) => void }>();
  const post = (id: number, cmd: string, args: Record<string, unknown>) => {
    const b = bridge();
    if (!b) return false;
    b.post(JSON.stringify({ id, cmd, args }));
    return true;
  };
  return {
    call<T>(cmd: string, args: Record<string, unknown> = {}) {
      return new Promise<T>((resolve, reject) => {
        const id = next++;
        waiting.set(id, { resolve: resolve as (v: unknown) => void, reject });
        try {
          if (post(id, cmd, args)) return;
          waiting.delete(id);
          reject(NO_HOST);
        } catch (e) {
          waiting.delete(id);
          reject(e instanceof Error ? e.message : String(e));
        }
      });
    },
    event(cmd: string, args: Record<string, unknown> = {}) {
      try {
        post(0, cmd, args);
      } catch {
        // An event nobody could take is not the page's to fail over.
      }
    },
    resolve(id: number, ok: boolean, payloadJson: unknown) {
      const w = waiting.get(id);
      if (!w) return;
      waiting.delete(id);
      let payload: unknown;
      try {
        // Text, as the bridge sends it; an object handed over as one is taken as it is.
        payload = typeof payloadJson === "string" ? (payloadJson === "" ? null : JSON.parse(payloadJson)) : (payloadJson ?? null);
      } catch {
        w.reject(`The host's answer to ${id} could not be read.`);
        return;
      }
      if (ok) w.resolve(payload);
      else w.reject(typeof payload === "string" ? payload : "Something went wrong. Try again.");
    },
  };
}

/** The page's one connection to its host. */
export const host = createHost();
