/// <reference types="vitest/config" />
import { fileURLToPath } from "node:url";
import { defineConfig, type Plugin } from "vite";
import react from "@vitejs/plugin-react";
import { devPicture } from "./src/mobile/notes/fixtures";

/**
 * The Android app's note page: the desktop's note editor (src/screens/notes) mounted full-page,
 * built into the APK's assets and served there from https://appassets.androidplatform.net
 * (src/mobile/notes/BRIDGE.md). The desktop's sources are not forked: the two Tauri modules
 * they reach are this bundle's shim, which carries every command to the Android host, and the
 * one file that must behave differently on a touch screen (the Add Link box) is the bundle's own.
 */
const here = (p: string) => fileURLToPath(new URL(p, import.meta.url));
const shim = here("./src/mobile/notes/tauri-shim.ts");

/** The note editor's `./LinkBox`, answered with the phone's: a sheet above the keyboard. */
function phoneLinkBox(): Plugin {
  const mobile = here("./src/mobile/notes/LinkBox.tsx");
  return {
    name: "skykeep-phone-link-box",
    enforce: "pre",
    resolveId(source, importer) {
      if (source === "./LinkBox" && importer && /[\\/]src[\\/]screens[\\/]notes[\\/]/.test(importer)) return mobile;
      return null;
    },
  };
}

/** The page may load only what it ships with and the note media its host serves: no network. */
function lockedDown(): Plugin {
  const policy = [
    "default-src 'none'",
    "script-src 'self'",
    // React and ProseMirror set style attributes; the stylesheet's masks are data: SVGs.
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob:",
    "font-src 'self'",
    "connect-src 'none'",
    "base-uri 'none'",
    "form-action 'none'",
    "frame-src 'none'",
  ].join("; ");
  return {
    name: "skykeep-phone-csp",
    apply: "build",
    transformIndexHtml: (html) => html.replace('<meta charset="UTF-8" />', `<meta charset="UTF-8" />\n    <meta http-equiv="Content-Security-Policy" content="${policy}" />`),
  };
}

/** In the browser (dev only), note media from the fake host's fixtures. */
function devMedia(): Plugin {
  return {
    name: "skykeep-dev-note-media",
    apply: "serve",
    configureServer(server) {
      server.middlewares.use("/note-media", (req, res) => {
        const [id, part] = (req.url ?? "").replace(/^\//, "").split("/").map(decodeURIComponent);
        const svg = devPicture(id, part);
        if (!svg) {
          res.statusCode = 404;
          res.end();
          return;
        }
        res.setHeader("Content-Type", "image/svg+xml");
        res.end(svg);
      });
    },
  };
}

export default defineConfig({
  root: here("./src/mobile/notes"),
  base: "./",
  // Beside the rest of the repository's, not inside the page's own folder.
  cacheDir: here("./node_modules/.vite-mobile-notes"),
  plugins: [phoneLinkBox(), react(), lockedDown(), devMedia()],
  resolve: {
    alias: {
      "@tauri-apps/api/core": shim,
      "@tauri-apps/api/event": shim,
    },
  },
  clearScreen: false,
  server: { port: 5274, strictPort: true },
  build: {
    outDir: here("./android/app/src/main/assets/notes-editor"),
    emptyOutDir: true,
    target: "chrome100",
    sourcemap: false,
    // One file, read from the APK, never fetched: its size is not a page load's.
    chunkSizeWarningLimit: 900,
  },
  test: {
    environment: "jsdom",
    include: ["**/*.test.{ts,tsx}"],
    setupFiles: ["./test-setup.ts"],
  },
});
