// BlockBridge YouTube Plus (content script, runs before YouTube's own scripts).
"use strict";

const page = window.wrappedJSObject;
const bg = { on: true }; // background play; the app can turn it off

// Messages for BlockBridge's debug log (sent once the app is connected).
let port = null;
const early = [];
function log(msg) {
  msg = String(msg).slice(0, 300);
  try { if (port) port.postMessage({ log: msg }); else if (early.length < 20) early.push(msg); } catch (e) {}
}

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
    log("background play: getter for " + name + " failed (" + e + "), using fixed value");
    try { Object.defineProperty(page.document, name, { configurable: true, value }); }
    catch (e2) { log("ERROR background play: can't override " + name + ": " + e2); }
  }
}
visible("hidden", false);
visible("visibilityState", "visible");
visible("webkitHidden", false);
visible("webkitVisibilityState", "visible");
let hiddenNoted = false;
function blockHide(e) {
  if (!bg.on) return;
  e.stopImmediatePropagation();
  if (!hiddenNoted && document.hidden) { hiddenNoted = true; log("page went to background; kept it 'visible' so playback continues"); }
  if (!document.hidden) hiddenNoted = false;
}
for (const type of ["visibilitychange", "webkitvisibilitychange"]) {
  window.addEventListener(type, blockHide, true);
  document.addEventListener(type, blockHide, true);
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
  if (btn) { btn.click(); log("answered 'Continue watching?' automatically"); }
  else log("ERROR saw 'Continue watching?' but found no button");
  const v = document.querySelector("video");
  if (v && v.paused) v.play().catch(() => {});
}
new MutationObserver(() => { try { autoContinue(document); } catch (e) { log("ERROR auto-continue: " + e); } })
  .observe(document, { childList: true, subtree: true });

// Video playback errors (media errors don't bubble, so listen in the capture phase).
const mediaErrors = ["", "aborted", "network error", "can't decode", "format not supported"];
document.addEventListener("error", (e) => {
  const v = e.target;
  if (!v || v.tagName !== "VIDEO" || !v.error) return;
  log("ERROR video: " + (mediaErrors[v.error.code] || v.error.code) + (v.error.message ? " — " + v.error.message : ""));
}, true);

// 2. Talk to the app: it tells us when picture-in-picture starts/ends and the settings.
try {
  port = browser.runtime.connectNative("blockbridge");
  port.onMessage.addListener((m) => {
    if (!m) return;
    if ("background" in m) bg.on = !!m.background;
    if ("pip" in m) {
      const was = document.documentElement.classList.contains("bb-pip");
      document.documentElement.classList.toggle("bb-pip", !!m.pip);
      if (was !== !!m.pip) log(m.pip ? "picture-in-picture: showing the video only" + (document.querySelector("video") ? "" : " (ERROR no video on page)") : "picture-in-picture: normal page");
    }
    if (m.play) { const v = document.querySelector("video"); if (v) v.play().catch(() => {}); }
  });
  early.splice(0).forEach((m) => port.postMessage({ log: m }));
} catch (e) { port = null; /* app not listening: background play still works */ }
