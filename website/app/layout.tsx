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
    "Play decisive offline chess against eight on-device opponents on Android, iPhone, and iPad. Android also includes private, on-device Game Review.",
  applicationName: "Drawless Chess",
  authors: [{ name: "BB_Games" }],
  creator: "BB_Games",
  referrer: "strict-origin-when-cross-origin",
  openGraph: {
    type: "website",
    url: "/",
    siteName: "Drawless Chess",
    title: "Drawless Chess — Every game has a winner",
    description:
      "Play decisive offline chess against eight on-device opponents on Android, iPhone, and iPad. Android also includes private, on-device Game Review.",
    images: [SOCIAL_IMAGE],
  },
  twitter: {
    card: "summary_large_image",
    title: "Drawless Chess — Every game has a winner",
    description:
      "Play decisive offline chess against eight on-device opponents on Android, iPhone, and iPad. Android also includes private, on-device Game Review.",
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
        <meta
          httpEquiv="Content-Security-Policy"
          content="default-src 'self'; base-uri 'self'; object-src 'none'; form-action 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; worker-src 'self'; connect-src 'self'; font-src 'self'; manifest-src 'self'"
        />
        <link rel="icon" href="/favicon.ico" sizes="any" />
        <link rel="icon" type="image/png" sizes="32x32" href="/favicon-32x32.png" />
        <link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png" />
        <meta
          name="apple-itunes-app"
          content="app-id=6801584008, app-argument=https://apps.apple.com/app/drawless-chess/id6801584008"
        />
        <link rel="manifest" href="/site.webmanifest" />
      </head>
      <body>
        <a className="skip-link" href="#main">Skip to content</a>
        {children}
      </body>
    </html>
  );
}
