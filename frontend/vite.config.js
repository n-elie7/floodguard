import { defineConfig } from "vite";
export default defineConfig({
  server: {
    proxy: {
      "/api": { target: "https://localhost", secure: false },
      "/ws": { target: "wss://localhost", secure: false, ws: true },
    },
  },
});
