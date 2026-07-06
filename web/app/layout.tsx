import type { Metadata, Viewport } from "next";
import { UpdateWatcher } from "@/components/shell/UpdateWatcher";
import "./globals.css";

export const metadata: Metadata = {
  title: "ARVIO",
  description: "ARVIO media hub for web, iPad, desktop, and TV browsers",
  manifest: "/manifest.webmanifest",
  icons: {
    icon: [
      { url: "/favicon-32.png", sizes: "32x32", type: "image/png" },
      { url: "/arvio-icon-192.png", sizes: "192x192", type: "image/png" },
      { url: "/arvio-icon-512.png", sizes: "512x512", type: "image/png" }
    ],
    apple: [{ url: "/apple-touch-icon.png", sizes: "180x180", type: "image/png" }]
  },
  appleWebApp: {
    capable: true,
    title: "ARVIO",
    statusBarStyle: "black-translucent"
  }
};

export const viewport: Viewport = {
  themeColor: "#000000",
  colorScheme: "dark",
  width: "device-width",
  initialScale: 1,
  maximumScale: 1,
  // Cover the iOS status-bar area — without this the page top bleeds through
  // above fullscreen surfaces like the player.
  viewportFit: "cover"
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <UpdateWatcher />
        {children}
      </body>
    </html>
  );
}
