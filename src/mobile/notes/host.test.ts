import { describe, expect, it } from "vitest";
import { createHost } from "./host";

function bridge() {
  const sent: { id: number; cmd: string; args: Record<string, unknown> }[] = [];
  return { sent, post: (json: string) => void sent.push(JSON.parse(json)) };
}

describe("host", () => {
  it("invoke becomes the envelope and resolve settles it", async () => {
    const b = bridge();
    const host = createHost(() => b);
    const first = host.call<string>("note_body", { guid: "N1" });
    const second = host.call<string[][]>("note_table", { id: "T1" });
    expect(b.sent).toEqual([
      { id: 1, cmd: "note_body", args: { guid: "N1" } },
      { id: 2, cmd: "note_table", args: { id: "T1" } },
    ]);
    // Answered out of order, each by its own id; the payload is JSON text.
    host.resolve(2, true, JSON.stringify([["a", "b"]]));
    host.resolve(1, true, JSON.stringify("Shopping\nMilk"));
    await expect(second).resolves.toEqual([["a", "b"]]);
    await expect(first).resolves.toBe("Shopping\nMilk");
  });

  it("a refusal rejects with the host's message, and an answer is taken once", async () => {
    const b = bridge();
    const host = createHost(() => b);
    const asked = host.call("note_save", { guid: "N1" });
    host.resolve(1, false, JSON.stringify("Apple refused the note"));
    await expect(asked).rejects.toBe("Apple refused the note");
    // A second answer to the same id, or one never asked, changes nothing and throws nothing.
    expect(() => host.resolve(1, true, "null")).not.toThrow();
    expect(() => host.resolve(99, true, "null")).not.toThrow();
  });

  it("events carry id 0 and expect no answer", () => {
    const b = bridge();
    const host = createHost(() => b);
    host.event("host_ready", {});
    host.event("host_saved", { guid: "N1" });
    expect(b.sent).toEqual([
      { id: 0, cmd: "host_ready", args: {} },
      { id: 0, cmd: "host_saved", args: { guid: "N1" } },
    ]);
  });

  it("with no host, a command fails rather than waiting for ever", async () => {
    const host = createHost(() => undefined);
    await expect(host.call("note_body", { guid: "N1" })).rejects.toMatch(/host/i);
    expect(() => host.event("host_ready", {})).not.toThrow();
  });

  it("an answer that is not JSON rejects the command", async () => {
    const b = bridge();
    const host = createHost(() => b);
    const asked = host.call("note_body", { guid: "N1" });
    host.resolve(1, true, "{not json");
    await expect(asked).rejects.toMatch(/answer/i);
  });
});
