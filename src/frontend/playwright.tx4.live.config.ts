import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/tx4-live",
  workers: 1, retries: 0, reporter: "list",
  use: { baseURL: "http://127.0.0.1:3101", trace: "retain-on-failure", launchOptions: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH } : {} },
  projects: [{ name: "live-desktop", use: { ...devices["Desktop Chrome"] } }],
  // Build first with BACKEND_INTERNAL_URL=http://127.0.0.1:18081.
  webServer: { command: "npm run start -- --port 3101", url: "http://127.0.0.1:3101", reuseExistingServer: false, timeout: 60_000 },
});
