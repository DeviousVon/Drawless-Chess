import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../app/globals.css";
import { SiteFooter, SiteHeader } from "../app/site-chrome";
import { PlayGame } from "../app/play/play-game";

function PlayApplication() {
  return (
    <StrictMode>
      <a className="skip-link" href="#main">Skip to content</a>
      <SiteHeader />
      <main id="main" className="play-page">
        <PlayGame />
      </main>
      <SiteFooter />
    </StrictMode>
  );
}

const root = document.getElementById("play-root");
if (!root) throw new Error("The play application root is missing.");
createRoot(root).render(<PlayApplication />);
