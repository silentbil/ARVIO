import { AppShell } from "@/components/shell/AppShell";
import { AppProvider } from "@/lib/store";

export default function LoginPage() {
  return (
    <AppProvider initialView="login" cloudLoginRequired>
      <AppShell />
    </AppProvider>
  );
}
