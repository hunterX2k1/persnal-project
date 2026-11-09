// BlockBridge YouTube Plus (content script, runs before YouTube's own scripts).
"use strict";

const page = window.wrappedJSObject;
const bg = { on: true }; // background play; the app can turn it off

// 1. Background play. YouTube pauses when the page reports itself hidden (screen off or
// another app on top), so while background play is on the page always looks visible.
function visible(name, value) {
  const real = () => (name.toLowerCase().includes("hidden") ? document.hidden : document.visibilityState);
  try {
    Object.defineProperty(page.document, name, {
      configurable: true,
      get: exportFunction(function () { return bg.on ? value : real(); }, window),
    });
  } catch (e) {
    try { Object.defineProperty(page.document, name, { configurable: true, value }); } catch (e2) {}
  }
}
visible("hidden", false);
visible("visibilityState", "visible");
visible("webkitHidden", false);
visible("webkitVisibilityState", "visible");
for (const type of ["visibilitychange", "webkitvisibilitychange"]) {
  window.addEventListener(type, (e) => { if (bg.on) e.stopImmediatePropagation(); }, true);
  document.addEventListener(type, (e) => { if (bg.on) e.stopImmediatePropagation(); }, true);
}
// Only the window losing focus, not text boxes (those must still work).
window.addEventListener("blur", (e) => {
  if (bg.on && (e.target === window || e.target === document)) e.stopImmediatePropagation();
}, true);

// YouTube measures "last activity" to show "Video paused. Continue watching?". Keep it fresh.
setInterval(() => { try { page._lact = Date.now(); } catch (e) {} }, 60 * 1000);

// …and if the prompt still appears, answer "Yes" for you.
function autoContinue(root) {
  const dialog = root.querySelector && root.querySelector(
    "yt-confirm-dialog-renderer, ytm-confirm-dialog-renderer, tp-yt-paper-dialog yt-confirm-dialog-renderer");
  if (!dialog) return;
  const text = (dialog.textContent || "").toLowerCase();
  if (!/continue watching|still watching|video paused/.test(text)) return;
  const btn = dialog.querySelector("#confirm-button button, #confirm-button, button[aria-label]:last-of-type, .dialog-buttons button:last-child");
  if (btn) btn.click();
  const v = document.querySelector("video");
  if (v && v.paused) v.play().catch(() => {});
}
new MutationObserver(() => autoContinue(document)).observe(document, { childList: true, subtree: true });

// 2. Talk to the app: it tells us when picture-in-picture starts/ends and the settings.
let port = null;
try {
  port = browser.runtime.connectNative("blockbridge");
  port.onMessage.addListener((m) => {
    if (!m) return;
    if ("background" in m) bg.on = !!m.background;
    if ("pip" in m) document.documentElement.classList.toggle("bb-pip", !!m.pip);
    if (m.play) { const v = document.querySelector("video"); if (v) v.play().catch(() => {}); }
  });
  port.postMessage({ hello: location.href });
} catch (e) { /* app not listening: background play still works */ }
