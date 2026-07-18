import type { Metadata, Viewport } from "next";
import "./globals.css";
import { SOCIAL_IMAGE } from "./site-chrome";

export const metadata: Metadata = {
  metadataBase: new URL("https://drawlesschess.com"),
  title: {
    default: "Drawless Chess",
    template: "%s · Drawless Chess",
  },
  description:
    "Offline chess with decisive no-draw rules, seven illustrated opponents, and no ads.",
  applicationName: "Drawless Chess",
  authors: [{ name: "BB_Games" }],
  creator: "BB_Games",
  openGraph: {
    type: "website",
    url: "/",
    siteName: "Drawless Chess",
    title: "Drawless Chess — Every game has a winner",
    description:
      "Offline chess with decisive no-draw rules, seven illustrated opponents, and no ads.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Drawless Chess — Every game has a winner",
    description: "Offline chess. Decisive by design.",
    images: ["/og.png"],
  },
};

export const viewport: Viewport = {
  colorScheme: "dark",
  themeColor: "#0b1216",
  width: "device-width",
  initialScale: 1,
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <head>
        <link rel="icon" href="/favicon.ico" sizes="any" />
        <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png" />
        <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
        <link rel="manifest" href="/site.webmanifest" />
      </head>
      <body>
        <a className="skip-link" href="#main">Skip to content</a>
        {children}
      </body>
    </html>
  );
}
