// BlockBridge YouTube Plus (content script, runs before YouTube's own scripts).
"use strict";

const page = window.wrappedJSObject;
const bg = { on: true }; // background play; the app can turn it off

// While you're not looking at the page (picture-in-picture, screen off, other app), the only
// way to pause is BlockBridge's buttons — so YouTube's own scripts aren't allowed to pause.
let hold = false;
const nativePause = HTMLMediaElement.prototype.pause; // the browser's real pause()
let lastBlockLog = 0;

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

// Smooth mode (low-end phones): tell YouTube we only play H.264. Nearly every phone decodes
// H.264 in hardware, while VP9/AV1 can be decoded on the CPU — choppy and hot on a slow phone.
// YouTube asks at startup, so the setting is remembered on the page for next time.
let smooth = false;
try { smooth = localStorage.getItem("bb-smooth") === "1"; } catch (e) {}
const heavy = /vp0?9|vp8|av01/i;
try {
  const nativeIsType = MediaSource.isTypeSupported.bind(MediaSource);
  exportFunction(function (type) {
    if (smooth && heavy.test(String(type))) return false;
    return nativeIsType(type);
  }, page.MediaSource, { defineAs: "isTypeSupported" });
  const nativeCanPlay = HTMLMediaElement.prototype.canPlayType;
  exportFunction(function (type) {
    if (smooth && heavy.test(String(type))) return "";
    return nativeCanPlay.call(this, type);
  }, page.HTMLMediaElement.prototype, { defineAs: "canPlayType" });
} catch (e) { setTimeout(() => log("ERROR smooth mode not installed: " + e), 0); }

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
// "Open App" / "Get the app" buttons and banners: BlockBridge is the app here.
const appPromo = /^(open( the)?( youtube)? app|open in( the)?( youtube)? app|get( the)?( youtube)? app|use the app|switch to the app)$/i;
function hideAppPromos(root) {
  for (const el of root.querySelectorAll('button, a, [role="button"], ytm-button-renderer, tp-yt-paper-button')) {
    if (el.dataset.bbChecked) continue;
    el.dataset.bbChecked = "1";
    const label = ((el.getAttribute("aria-label") || el.textContent || "").trim());
    if (label.length < 30 && appPromo.test(label)) {
      (el.closest("ytm-button-renderer, ytm-mealbar-promo-renderer") || el).style.setProperty("display", "none", "important");
      log("hid an '" + label + "' button");
    }
  }
}

// BlockBridge's menu button (shield), placed in YouTube's top bar next to search. If YouTube
// changes its page and the bar isn't found, a small floating shield is shown instead.
const SHIELD = '<svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">' +
  '<path fill="currentColor" d="M12 2l8 3v6c0 5-3.5 9-8 11-4.5-2-8-6-8-11V5z M10 8v8l6-4z"/></svg>';
const pageStart = Date.now();
function openMenu(e) {
  e.preventDefault(); e.stopPropagation();
  try { if (port) port.postMessage({ menu: true }); else log("ERROR menu: app not connected"); } catch (err) {}
}
function addMenuButton() {
  if (window.top !== window || !document.body) return;
  if (document.getElementById("bb-menu")) return;
  const bar = document.querySelector(
    "ytm-mobile-topbar-renderer .mobile-topbar-header-content, .mobile-topbar-header-content, ytm-mobile-topbar-renderer header, header.mobile-topbar-header");
  const b = document.createElement("button");
  b.id = "bb-menu";
  b.setAttribute("aria-label", "BlockBridge menu");
  b.innerHTML = SHIELD;
  b.addEventListener("click", openMenu, true);
  if (bar) {
    b.className = "bb-menu-inline";
    bar.appendChild(b);
  } else if (Date.now() - pageStart > 5000) {
    b.className = "bb-menu-float";
    document.body.appendChild(b);
    log("YouTube's top bar not found — showing the floating menu button");
  }
}

// Page changes are checked at most every 400 ms (not on every change) — lighter on slow phones.
let scheduled = false;
new MutationObserver(() => {
  if (scheduled) return;
  scheduled = true;
  setTimeout(() => {
    scheduled = false;
    try { autoContinue(document); } catch (e) { log("ERROR auto-continue: " + e); }
    try { hideAppPromos(document); } catch (e) { log("ERROR open-app filter: " + e); }
    try { addMenuButton(); } catch (e) { log("ERROR menu button: " + e); }
    if (role === "player") scheduleRect();
  }, 400);
}).observe(document, { childList: true, subtree: true });

// Video playback errors (media errors don't bubble, so listen in the capture phase).
const mediaErrors = ["", "aborted", "network error", "can't decode", "format not supported"];
document.addEventListener("error", (e) => {
  const v = e.target;
  if (!v || v.tagName !== "VIDEO" || !v.error) return;
  log("ERROR video: " + (mediaErrors[v.error.code] || v.error.code) + (v.error.message ? " — " + v.error.message : ""));
}, true);

try {
  exportFunction(function () {
    if (hold) {
      if (Date.now() - lastBlockLog > 5000) { lastBlockLog = Date.now(); log("blocked YouTube's own pause (you're in PiP/background)"); }
      return undefined;
    }
    return nativePause.call(this);
  }, page.HTMLMediaElement.prototype, { defineAs: "pause" });
} catch (e) { /* logged once connected */ setTimeout(() => log("ERROR pause guard not installed: " + e), 0); }

// 2. Talk to the app: it tells us when picture-in-picture starts/ends and the settings.
// The app may still be starting when the page loads, so keep retrying until it answers.
let lastPause = 0, wasPlaying = false, pipSince = 0;
document.addEventListener("pause", (e) => {
  if (e.target && e.target.tagName === "VIDEO") { lastPause = Date.now(); }
  // YouTube sometimes pauses itself as the window shrinks; undo that right after PiP starts.
  if (pipSince && Date.now() - pipSince < 2500 && e.target.tagName === "VIDEO") {
    log("YouTube paused while entering picture-in-picture — resuming");
    e.target.play().catch((err) => log("ERROR resume failed: " + err));
  }
}, true);
document.addEventListener("play", (e) => { if (e.target && e.target.tagName === "VIDEO") wasPlaying = true; }, true);

// Tell the app the video's shape, so the floating window matches it.
function sendSize(v) {
  if (port && v.videoWidth && v.videoHeight) {
    try { port.postMessage({ video: [v.videoWidth, v.videoHeight] }); } catch (e) {}
  }
}
for (const t of ["loadedmetadata", "resize"]) {
  document.addEventListener(t, (e) => { if (e.target && e.target.tagName === "VIDEO") sendSize(e.target); }, true);
}

// The app runs two pages: "browse" (home, search, channels…) and "player" (the watch page in
// the player layer, which can shrink to the mini bar).
let role = "browse";

// On the browsing page, a tapped video opens in the player layer (like the YouTube app),
// instead of loading the watch page here.
window.addEventListener("click", (e) => {
  if (role !== "browse" || !port || e.defaultPrevented) return;
  const a = e.target && e.target.closest ? e.target.closest("a[href]") : null;
  if (!a) return;
  let u;
  try { u = new URL(a.getAttribute("href"), location.href); } catch (err) { return; }
  if (!/(^|\.)youtube\.com$/.test(u.hostname) || u.hostname === "music.youtube.com") return;
  if (u.pathname !== "/watch" && !u.pathname.startsWith("/live/")) return;
  e.preventDefault();
  e.stopImmediatePropagation();
  try { port.postMessage({ open: u.href }); } catch (err) {}
}, true);

// On the player page: where the video is (in screen pixels), for swipe-down and the shrink.
let rectTimer = 0;
function sendRect() {
  if (role !== "player" || !port) return;
  const box = document.querySelector("#player-container-id, .player-container, ytm-player") || document.querySelector("video");
  if (!box) return;
  const r = box.getBoundingClientRect();
  if (r.height < 20) return;
  const d = window.devicePixelRatio || 1;
  try { port.postMessage({ vrect: [Math.round(r.top * d), Math.round(r.height * d)] }); } catch (err) {}
}
function scheduleRect() {
  if (rectTimer) return;
  rectTimer = setTimeout(() => { rectTimer = 0; try { sendRect(); } catch (e) {} }, 150);
}
window.addEventListener("scroll", scheduleRect, { passive: true });
window.addEventListener("resize", scheduleRect);
document.addEventListener("loadedmetadata", scheduleRect, true);

// Autoplay a video opened in the player layer (no tap happened on this page, so YouTube's
// mobile site would otherwise wait for one). Tries for up to 12 s while the player loads.
let autoplayUntil = 0;
function tryAutoplay() {
  if (Date.now() > autoplayUntil) return;
  const v = document.querySelector("video");
  if (v && !v.paused && v.currentTime > 0) {
    autoplayUntil = 0;
    try { port && port.postMessage({ autoplayed: true }); } catch (e) {}
    return;
  }
  if (v && v.readyState >= 1) {
    v.play().catch(() => {
      // YouTube's own big play button, if the element refused
      const b = document.querySelector(".ytp-large-play-button, .player-controls-middle button, button[aria-label='Play']");
      if (b) b.click();
    });
  }
  setTimeout(tryAutoplay, 400);
}
document.addEventListener("loadedmetadata", () => { if (autoplayUntil) tryAutoplay(); }, true);

function onAppMessage(m) {
  if (!m) return;
  if (m.autoplay && role === "player" && !autoplayUntil) {
    // only the page of that video (not the previous one, still unloading)
    const id = String(m.autoplay);
    const here = new URL(location.href);
    const mine = id === "true" || here.searchParams.get("v") === id || here.pathname.endsWith("/" + id);
    if (mine) { autoplayUntil = Date.now() + 12000; tryAutoplay(); }
  }
  if ("role" in m) { role = m.role; if (role === "player") scheduleRect(); }
  if ("background" in m) bg.on = !!m.background;
  if ("smooth" in m) {
    const was = smooth;
    smooth = !!m.smooth;
    try { localStorage.setItem("bb-smooth", smooth ? "1" : "0"); } catch (e) {}
    if (was !== smooth) log("smooth mode " + (smooth ? "on (H.264 video)" : "off") + " — applies from the next video");
  }
  if ("pip" in m) {
    const root = document.documentElement;
    const was = root.classList.contains("bb-pip");
    root.classList.toggle("bb-pip", !!m.pip);
    pipSince = m.pip ? Date.now() : 0;
    const v = document.querySelector("video");
    if (m.pip && v) {
      sendSize(v);
      // Paused in the moment before the message arrived? Keep it playing.
      if (v.paused && wasPlaying && Date.now() - lastPause < 3000) {
        log("resuming video paused by the switch to picture-in-picture");
        v.play().catch(() => {});
      }
    }
    if (was !== !!m.pip) log(m.pip ? "picture-in-picture: showing the video only" + (v ? "" : " (ERROR no video on page)") : "picture-in-picture: normal page");
  }
  if ("hold" in m) hold = !!m.hold;
  if (m.pause) {
    hold = false;
    const v = document.querySelector("video");
    if (v) nativePause.call(v);
  }
  if (m.play) {
    const v = document.querySelector("video");
    if (v) v.play().catch((err) => log("ERROR play refused: " + err));
  }
}

let tries = 0;
function connect() {
  try {
    const p = browser.runtime.connectNative("blockbridge");
    p.onMessage.addListener(onAppMessage);
    p.onDisconnect.addListener(() => {
      if (port === p) port = null;
      if (tries++ < 30) setTimeout(connect, Math.min(500 * tries, 5000));
    });
    port = p;
    early.splice(0).forEach((m) => p.postMessage({ log: m }));
    const v = document.querySelector("video");
    if (v) sendSize(v);
  } catch (e) {
    port = null;
    if (tries++ < 30) setTimeout(connect, Math.min(500 * tries, 5000));
  }
}
connect();
