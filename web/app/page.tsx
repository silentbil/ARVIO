import { AppShell } from "@/components/shell/AppShell";
import { AppProvider } from "@/lib/store";

// Fully static: this is a pure client-side SPA, so the homepage is a plain
// prerendered HTML file served straight from the CDN — zero serverless-function
// invocations (dynamic/revalidate rendering invoked a function per request and
// tripped Netlify usage limits → 429s). Stale bundles are handled entirely
// client-side by UpdateWatcher (polls no-store /version.json and hard-reloads).
export const dynamic = "force-static";

export default function Page() {
  return (
    <AppProvider>
      <AppShell />
    </AppProvider>
  );
}
