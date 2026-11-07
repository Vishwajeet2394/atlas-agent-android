"use strict";
/* ============================================================ Atlas — phone agent UI
   Talks to Java through window.Native. Long jobs answer through atlasCb(id, ok, payload). */

// ---------------- bridge ----------------
const N = window.Native || mockNative();
const CB = {}; let cbn = 0;
window.atlasCb = (id, ok, payload) => { const c = CB[id]; if (!c) return; delete CB[id]; ok ? c.res(payload) : c.rej(new Error(payload || "Failed")); };
function call(fn, ...args) { return new Promise((res, rej) => { const id = "c" + (++cbn); CB[id] = { res, rej }; try { N[fn](...args, id); } catch (e) { delete CB[id]; rej(e); } }); }
const J = (s, d) => { try { return JSON.parse(s); } catch (e) { return d; } };
function ai(req) { return call("ai", JSON.stringify(req)); }
async function aiJson(req) { const t = await ai({ ...req, json: true }); return parseJson(t); }
function parseJson(t) {
  t = String(t || "").trim();
  if (t.startsWith("```")) t = t.replace(/^```[a-z]*\n?/i, "").replace(/```$/, "").trim();
  try { return JSON.parse(t); } catch (e) { }
  const a = t.indexOf("{"), b = t.lastIndexOf("}");
  if (a >= 0 && b > a) { try { return JSON.parse(t.slice(a, b + 1)); } catch (e) { } }
  return { say: t, actions: [] };
}

// ---------------- helpers ----------------
const $ = s => document.querySelector(s);
const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const PREF = () => J(N.prefs(), {});
function setPref(k, v) { N.setPref(k, String(v)); }
function size(b) { b = +b || 0; if (b < 1024) return b + " B"; const u = ["KB", "MB", "GB", "TB"]; let i = -1; do { b /= 1024; i++; } while (b >= 1024 && i < 3); return (b < 10 ? b.toFixed(1) : Math.round(b)) + " " + u[i]; }
function ago(t) {
  const d = (Date.now() - t) / 1000; if (d < 60) return "just now"; if (d < 3600) return Math.floor(d / 60) + " min ago"; if (d < 86400) return Math.floor(d / 3600) + " h ago";
  const dt = new Date(t), now = new Date(); const opts = { day: "numeric", month: "short" }; if (dt.getFullYear() !== now.getFullYear()) opts.year = "numeric"; return dt.toLocaleDateString("en-IN", opts);
}
function b64url(s) { return btoa(unescape(encodeURIComponent(s))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
const thumb = p => "/t/" + b64url(p) + ".jpg";
const big = p => "/v/" + b64url(p) + ".jpg";
const hasThumb = k => k === "image" || k === "video" || k === "pdf";
let toastT; function toast(m) { const t = $("#toast"); t.textContent = m; t.classList.add("in"); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove("in"), 2600); }
window.toast = toast;
const tick = (ms = 8) => { try { N.haptic(ms); } catch (e) { } };
function md(s) {
  let h = esc(s || "");
  h = h.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/(^|[\s(])\*(?!\s)(.+?)\*(?=[\s).,!?]|$)/g, "$1<i>$2</i>").replace(/`([^`]+)`/g, "<code>$1</code>");
  const lines = h.split(/\n/); let out = "", inList = false;
  for (const l of lines) {
    const m = l.match(/^\s*(?:[-•*]|\d+[.)])\s+(.*)/);
    if (m) { if (!inList) { out += "<ul>"; inList = true; } out += "<li>" + m[1] + "</li>"; continue; }
    if (inList) { out += "</ul>"; inList = false; }
    if (l.trim()) out += "<p>" + l + "</p>";
  }
  if (inList) out += "</ul>";
  return out;
}
const uid = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
const store = { get(k, d) { try { const v = localStorage.getItem("atlas." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } }, set(k, v) { try { localStorage.setItem("atlas." + k, JSON.stringify(v)); } catch (e) { } } };

// ---------------- icons ----------------
const IC = {
  spark: '<path d="M12 3c.8 4.6 2.6 6.4 7.2 7.2C14.6 11 12.8 12.8 12 17.4 11.2 12.8 9.4 11 4.8 10.2 9.4 9.4 11.2 7.6 12 3z"/><path d="M19 15.5c.3 1.6.9 2.2 2.5 2.5-1.6.3-2.2.9-2.5 2.5-.3-1.6-.9-2.2-2.5-2.5 1.6-.3 2.2-.9 2.5-2.5z"/>',
  chat: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-5A8 8 0 1 1 21 12z"/>',
  folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>',
  bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 9 19.4a1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  send: '<path d="M5 12h13M12 5l7 7-7 7"/>',
  mic: '<rect x="9" y="2" width="6" height="12" rx="3"/><path d="M5 10a7 7 0 0 0 14 0M12 17v5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  car: '<path d="M5 17h14M5 17a2 2 0 1 1-4 0v-4l2.5-6A2 2 0 0 1 5.4 6h13.2a2 2 0 0 1 1.9 1l2.5 6v4a2 2 0 1 1-4 0"/><circle cx="7" cy="17" r="2"/><circle cx="17" cy="17" r="2"/><path d="M3 12h18"/>',
  food: '<path d="M3 2v7a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2V2M6 2v20M21 15V2a5 5 0 0 0-5 5v6a2 2 0 0 0 2 2h3zm0 0v7"/>',
  bag: '<path d="M6 7h12l1 14H5z"/><path d="M9 10V6a3 3 0 0 1 6 0v4"/>',
  cart: '<circle cx="9" cy="20" r="1.5"/><circle cx="18" cy="20" r="1.5"/><path d="M2 3h3l2.7 12.4a2 2 0 0 0 2 1.6h7.6a2 2 0 0 0 2-1.5L21 8H6"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  image: '<rect x="3" y="3" width="18" height="18" rx="3"/><circle cx="9" cy="9" r="2"/><path d="m21 15-5-5L5 21"/>',
  pdf: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h2.5a1.5 1.5 0 0 1 0 3H8v-5M14 18v-5h2.5M14 15.5h2"/>',
  doc: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h8M8 9h2"/>',
  video: '<rect x="2" y="5" width="15" height="14" rx="3"/><path d="m17 10 5-3v10l-5-3"/>',
  audio: '<path d="M9 18V5l12-2v13"/><circle cx="6" cy="18" r="3"/><circle cx="18" cy="16" r="3"/>',
  apk: '<rect x="5" y="2" width="14" height="20" rx="3"/><path d="M11 18h2"/>',
  zip: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M10 4h2M10 7h2M10 10h2M10 13h2v3h-2z"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/>',
  down: '<path d="M12 3v12M7 10l5 5 5-5M5 21h14"/>',
  shot: '<rect x="5" y="2" width="14" height="20" rx="3"/><path d="M9 7h6M9 11h6"/>',
  wa: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-5A8 8 0 1 1 21 12z"/><path d="M9 9.5c0 3 2.5 5.5 5.5 5.5l1-1.5-2-1-1 .8a3.5 3.5 0 0 1-1.8-1.8l.8-1-1-2z"/>',
  cam: '<path d="M3 8a2 2 0 0 1 2-2h2l2-2h6l2 2h2a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><circle cx="12" cy="13" r="4"/>',
  big: '<path d="M4 20V10M10 20V4M16 20v-8M22 20H2"/>',
  wand: '<path d="m15 4 5 5L9 20H4v-5zM13 6l5 5"/><path d="M4 4l1 2 2 1-2 1-1 2-1-2-2-1 2-1z"/>',
  copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z"/><circle cx="12" cy="12" r="3"/>',
  tree: '<path d="M3 3h6v6H3zM15 15h6v6h-6zM15 3h6v6h-6zM6 9v6a2 2 0 0 0 2 2h7M9 6h6"/>',
  trash: '<path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/>',
  share: '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><path d="m8.6 13.5 6.8 4M15.4 6.5l-6.8 4"/>',
  open: '<path d="M14 3h7v7M10 14 21 3M21 14v5a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5"/>',
  pen: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  move: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><path d="M10 13h6M13 10l3 3-3 3"/>',
  back: '<path d="M15 18l-6-6 6-6"/>',
  check: '<path d="M20 6 9 17l-5-5"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  key: '<circle cx="8" cy="15" r="4"/><path d="m10.8 12.2 9.2-9.2M17 6l3 3M15 8l2 2"/>',
  shield: '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="m9 12 2 2 4-4"/>',
  hand: '<path d="M18 11V6a2 2 0 0 0-4 0v5M14 10V4a2 2 0 0 0-4 0v6M10 10.5V6a2 2 0 0 0-4 0v8a8 8 0 0 0 16 0v-3a2 2 0 0 0-4 0"/>',
  undo: '<path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-15-6.7L3 13"/>',
  sort: '<path d="M3 6h18M6 12h12M10 18h4"/>',
  palette: '<circle cx="13.5" cy="6.5" r="1.5"/><circle cx="17.5" cy="10.5" r="1.5"/><circle cx="8.5" cy="7.5" r="1.5"/><circle cx="6.5" cy="12.5" r="1.5"/><path d="M12 2a10 10 0 1 0 0 20c1.1 0 2-.9 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1.1.9-2 2-2h2.3a5.5 5.5 0 0 0 5.5-5.5C22.8 5.8 18 2 12 2z"/>',
  history: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5M12 7v5l3 3"/>',
  dup: '<rect x="8" y="8" width="13" height="13" rx="3"/><path d="M4 16V6a2 2 0 0 1 2-2h10"/>',
  bin: '<path d="M3 6h18M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/>',
};
const ic = (n, c = "") => `<svg class="i ${c}" viewBox="0 0 24 24" aria-hidden="true">${IC[n] || IC.file}</svg>`;
const KIND = {
  image: ["Images", "image", "#5b8def"], pdf: ["PDFs", "pdf", "#e5534b"], doc: ["Documents", "doc", "#3b9c7a"], video: ["Videos", "video", "#a35bd6"],
  audio: ["Audio", "audio", "#e09b2d"], apk: ["APKs", "apk", "#4caf50"], archive: ["Archives", "zip", "#8d6e63"], other: ["Other", "file", "#78909c"],
  downloads: ["Downloads", "down", "#2b9bb0"], screenshots: ["Screenshots", "shot", "#d6578a"], whatsapp: ["WhatsApp", "wa", "#25a366"], camera: ["Camera", "cam", "#c77d2e"],
  large: ["Large files", "big", "#7c6cd8"], analyzed: ["Described by AI", "spark", "#6a7fdb"],
};
const kIcon = k => (KIND[k] || KIND.other)[1];

// ---------------- Material 3 colour from the wallpaper ----------------
const M3 = {
  hex2rgb(h) { h = h.replace("#", ""); if (h.length === 3) h = h.split("").map(c => c + c).join(""); return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16) / 255); },
  lin(c) { return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; }, unlin(c) { return c <= 0.0031308 ? 12.92 * c : 1.055 * c ** (1 / 2.4) - 0.055; },
  toOklab([r, g, b]) { r = this.lin(r); g = this.lin(g); b = this.lin(b); const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b), m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b), s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b); return [0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s, 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s, 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s]; },
  fromOklab([L, a, b]) { const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3, m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3, s = (L - 0.0894841775 * a - 1.2914855480 * b) ** 3; return [4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s, -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s, -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s].map(c => this.unlin(c)); },
  inGamut(rgb) { return rgb.every(c => c >= -0.0005 && c <= 1.0005); },
  hex(rgb) { return "#" + rgb.map(c => Math.round(Math.min(1, Math.max(0, c)) * 255).toString(16).padStart(2, "0")).join(""); },
  lch(h) { const [L, a, b] = this.toOklab(this.hex2rgb(h)); return [L, Math.hypot(a, b), (Math.atan2(b, a) * 180 / Math.PI + 360) % 360]; },
  tone(hue, chroma, t) { if (t >= 100) return "#ffffff"; if (t <= 0) return "#000000"; const L = Math.min(0.995, Math.max(0.02, (t / 100) ** 0.92)); let c = chroma * Math.min(1, Math.sin(Math.PI * Math.min(t, 98) / 100) * 1.25), rgb; for (let i = 0; i < 24; i++) { const hr = hue * Math.PI / 180; rgb = this.fromOklab([L, c * Math.cos(hr), c * Math.sin(hr)]); if (this.inGamut(rgb)) break; c *= 0.9; } return this.hex(rgb); },
  fromSeed(seed) { const [, c, h] = this.lch(seed); const C = Math.max(0.09, Math.min(0.16, c)); const P = (hh, cc) => t => this.tone(hh, cc, t); return { a1: P(h, C), a2: P(h, C * 0.33), a3: P((h + 60) % 360, C * 0.55), n1: P(h, 0.008), n2: P(h, 0.02) }; },
  fromSystem(sys) { const KEYS = [[100, "0"], [99, "10"], [95, "50"], [90, "100"], [80, "200"], [70, "300"], [60, "400"], [50, "500"], [40, "600"], [30, "700"], [20, "800"], [10, "900"], [0, "1000"]]; const P = pal => t => { for (let i = 0; i < KEYS.length - 1; i++) { const [t1, k1] = KEYS[i], [t0, k0] = KEYS[i + 1]; if (t <= t1 && t >= t0) { const a = this.toOklab(this.hex2rgb(pal[k0])), b = this.toOklab(this.hex2rgb(pal[k1])), f = (t - t0) / (t1 - t0); return this.hex(this.fromOklab(a.map((v, j) => v + (b[j] - v) * f))); } } return pal["500"]; }; return { a1: P(sys.a1), a2: P(sys.a2), a3: P(sys.a3), n1: P(sys.n1), n2: P(sys.n2) }; },
};
const SEEDS = { wallpaper: ["Wallpaper", null], indigo: ["Indigo", "#4f5fa8"], sage: ["Sage", "#4f7d5c"], ocean: ["Ocean", "#3f6f9a"], lavender: ["Lavender", "#7a62b0"], peach: ["Peach", "#b8683f"], rose: ["Rose", "#b04f6f"], teal: ["Teal", "#2f7f7a"] };
let sysPal; try { const s = N.dynamicColors && N.dynamicColors(); sysPal = s ? JSON.parse(s) : null; } catch (e) { sysPal = null; }
function applyTheme() {
  const p = PREF(); const mode = p.theme || "system", seed = p.seed || "wallpaper";
  const dark = mode === "dark" || (mode === "system" && matchMedia("(prefers-color-scheme: dark)").matches);
  const pal = seed === "wallpaper" && sysPal ? M3.fromSystem(sysPal) : M3.fromSeed((SEEDS[seed] && SEEDS[seed][1]) || SEEDS.indigo[1]);
  const d = dark, r = document.documentElement, set = (k, v) => r.style.setProperty(k, v);
  set("--primary", pal.a1(d ? 80 : 40)); set("--on-primary", pal.a1(d ? 20 : 100)); set("--primary-c", pal.a1(d ? 30 : 90)); set("--on-primary-c", pal.a1(d ? 90 : 10));
  set("--secondary", pal.a2(d ? 80 : 40)); set("--secondary-c", pal.a2(d ? 30 : 90)); set("--on-secondary-c", pal.a2(d ? 90 : 10));
  set("--tertiary", pal.a3(d ? 80 : 40)); set("--tertiary-c", pal.a3(d ? 30 : 90)); set("--on-tertiary-c", pal.a3(d ? 90 : 10));
  set("--surface", pal.n1(d ? 6 : 98)); set("--sc-lowest", pal.n1(d ? 4 : 100)); set("--sc-low", pal.n1(d ? 10 : 96)); set("--sc", pal.n1(d ? 12 : 94)); set("--sc-high", pal.n1(d ? 17 : 92)); set("--sc-highest", pal.n1(d ? 22 : 90));
  set("--on-surface", pal.n1(d ? 90 : 10)); set("--on-surface-v", pal.n2(d ? 80 : 30)); set("--outline", pal.n2(d ? 60 : 50)); set("--outline-v", pal.n2(d ? 30 : 80));
  set("--error", d ? "#ffb4ab" : "#ba1a1a"); set("--error-c", d ? "#93000a" : "#ffdad6"); set("--on-error-c", d ? "#ffdad6" : "#410002"); set("--ok", d ? "#7ed6a0" : "#2e7d4f");
  r.dataset.dark = d ? "1" : "0";
  try { N.setBars(pal.n1(d ? 6 : 98), d); } catch (e) { }
}
matchMedia("(prefers-color-scheme: dark)").addEventListener("change", applyTheme);

// ---------------- state ----------------
const S = {
  tab: store.get("tab", "assistant"),
  chat: store.get("chat", []),
  pend: [],            // files attached to the next message
  files: { mode: "home", kind: null, q: "", sort: "", folder: null, items: [], total: 0, sel: new Set(), dir: null },
  run: null,           // {msgId} of the task card the agent is working on
  undo: store.get("undo", []),
  analyzing: false,
};
const TABS = ["assistant", "files", "tasks", "settings"];
const DOCK = { assistant: ["Assistant", "spark"], files: ["Files", "folder"], tasks: ["Tasks", "bolt"], settings: ["Settings", "gear"] };
function saveChat() { S.chat = S.chat.slice(-80); store.set("chat", S.chat.map(m => ({ ...m, typing: false }))); }

// ---------------- dock ----------------
let DOCK_T = null;
function dockBtn(k) { return `<button data-a="tab" data-tab="${k}" aria-label="${DOCK[k][0]}">${ic(DOCK[k][1])}</button>`; }
function dockW(k) { const m = document.querySelector("#tabbar .dmeasure"); if (!m) return 120; m.textContent = DOCK[k][0]; return Math.ceil(18 + 22 + 8 + m.getBoundingClientRect().width + 20); }
function renderDock() {
  const t = S.tab; DOCK_T = t; const d = $("#tabbar"); d.dataset.t = t;
  d.innerHTML = `<span class="dmeasure" aria-hidden="true"></span><button class="dcur" data-a="tab" data-tab="${t}" aria-current="page"><span class="dci">${ic(DOCK[t][1])}</span><span class="dlbl">${DOCK[t][0]}</span></button><div class="dpill">${TABS.filter(k => k !== t).map(dockBtn).join("")}</div>`;
  dockSize();
}
function dockSize() { const d = $("#tabbar"), c = d.querySelector(".dcur"), p = d.querySelector(".dpill"); if (!c || !p) return; c.style.width = dockW(DOCK_T) + "px"; const wide = Math.max(...TABS.map(dockW)); d.style.width = (wide + 8 + p.getBoundingClientRect().width) + "px"; }
if (document.fonts && document.fonts.ready) document.fonts.ready.then(dockSize);
function dockTo(t) {
  const d = $("#tabbar"), cur = d.querySelector(".dcur"), pill = d.querySelector(".dpill");
  if (!cur || !pill) { renderDock(); return; } if (t === DOCK_T) return;
  const before = {}; pill.querySelectorAll("button").forEach(b => before[b.dataset.tab] = b.getBoundingClientRect().left);
  const ci = cur.querySelector(".dci"), lb = cur.querySelector(".dlbl"), ciL = ci.getBoundingClientRect().left, prev = DOCK_T; DOCK_T = t; d.dataset.t = t;
  const from = before[t];
  ci.innerHTML = ic(DOCK[t][1]); lb.textContent = DOCK[t][0]; cur.dataset.tab = t; cur.style.width = dockW(t) + "px";
  pill.innerHTML = TABS.filter(k => k !== t).map(dockBtn).join("");
  ci.classList.remove("slide"); lb.classList.remove("pop"); void ci.offsetWidth;
  if (from != null) ci.style.setProperty("--dx", (from + 12 - ciL) + "px");
  ci.classList.add("slide"); lb.classList.add("pop");
  pill.querySelectorAll("button").forEach(b => { const k = b.dataset.tab, x = b.getBoundingClientRect().left; if (k === prev) { b.style.setProperty("--dx", (ciL - 12 - x) + "px"); b.classList.add("enter"); } else if (before[k] != null && Math.abs(before[k] - x) > 1) { b.style.setProperty("--dx", (before[k] - x) + "px"); b.classList.add("glide"); } });
}
function go(tab) { if (S.tab === tab) { if (tab === "files" && S.files.mode !== "home") { S.files.mode = "home"; render(); } return; } S.tab = tab; store.set("tab", tab); dockTo(tab); render(); scrollTo(0, 0); }

// ---------------- render ----------------
function render() {
  const v = $("#view"); v.classList.remove("view"); void v.offsetWidth; v.classList.add("view");
  $("#composer").classList.toggle("hidden", S.tab !== "assistant");
  if (S.tab === "assistant") v.innerHTML = viewAssistant();
  else if (S.tab === "files") v.innerHTML = viewFiles();
  else if (S.tab === "tasks") v.innerHTML = viewTasks();
  else v.innerHTML = viewSettings();
  afterRender();
  canBack();
}
function afterRender() {
  document.querySelectorAll("img[data-lazy]").forEach(img => { img.onload = () => img.classList.add("in"); img.onerror = () => { const t = img.closest(".tile,.ico"); if (t) { img.remove(); } }; });
  if (S.tab === "assistant") { const m = $(".msgs"); if (m) requestAnimationFrame(() => scrollTo(0, document.body.scrollHeight)); }
}

// ================================================================ ASSISTANT
function setupBanners() {
  const p = PREF(), out = [];
  if (!N.aiReady()) out.push(`<div class="card"><div class="row nowrap"><div class="spark" style="background:var(--tertiary-c);color:var(--on-tertiary-c)">${ic("key")}</div><div class="grow"><h3>Add an AI key</h3><div class="t2">Gemini (free key from aistudio.google.com) or Claude — Atlas uses it to read files and drive apps.</div></div></div><div class="row end" style="margin-top:12px"><button class="btn primary sm" data-a="tab" data-tab="settings">Set up</button></div></div>`);
  if (!N.hasFiles()) out.push(`<div class="card"><div class="row nowrap"><div class="spark" style="background:var(--secondary-c);color:var(--on-secondary-c)">${ic("folder")}</div><div class="grow"><h3>Allow access to your files</h3><div class="t2">So Atlas can search, describe and organise everything on your phone. Nothing leaves the phone unless you ask the AI about a file.</div></div></div><div class="row end" style="margin-top:12px"><button class="btn primary sm" data-a="askFiles">Allow</button></div></div>`);
  if (!N.agentOn()) out.push(`<div class="card"><div class="row nowrap"><div class="spark" style="background:var(--primary-c);color:var(--on-primary-c)">${ic("hand")}</div><div class="grow"><h3>Switch on the phone agent</h3><div class="t2">Needed to book cabs, order food and send mail inside other apps. Atlas always asks before it books, pays or sends.</div></div></div><div class="row end" style="margin-top:12px"><button class="btn ghost sm" data-a="agentHelp">How?</button><button class="btn primary sm" data-a="openA11y">Turn on</button></div></div>`);
  return out.join("");
}
const SUGGEST = [
  ["car", "#5b8def", "Book a cab", "Uber, Ola or Rapido", "Book an Uber from my current location to "],
  ["food", "#e5534b", "Order food", "Swiggy or Zomato", "Order a chicken biryani on Swiggy from a well-rated place nearby"],
  ["cart", "#3b9c7a", "Groceries", "Instamart, Blinkit, Zepto", "Order on Instamart: 1 L milk, 6 eggs, 1 kg tomatoes, bread"],
  ["bag", "#e07b39", "Shop online", "Amazon, Flipkart, Myntra…", "Order on Amazon: "],
  ["mail", "#a35bd6", "Draft an email", "Write and send with Gmail", "Draft an email to "],
  ["search", "#e09b2d", "Find a file", "Search names and contents", "Find my PAN card photo"],
  ["wand", "#2b9bb0", "Tidy Downloads", "Sort into folders", "Organise my Download folder by type"],
];
function viewAssistant() {
  const greet = (() => { const h = new Date().getHours(); return h < 12 ? "Good morning" : h < 17 ? "Good afternoon" : "Good evening"; })();
  const name = (PREF().user_name || "").split(" ")[0];
  let h = `<div class="top"><div class="grow"><h1>Atlas</h1><div class="sub">Your phone, on autopilot</div></div>${S.chat.length ? `<button class="iconbtn" data-a="newChat" aria-label="New chat">${ic("plus")}</button>` : ""}</div>`;
  if (!S.chat.length) {
    h += `<div class="hero"><div class="spark">${ic("spark")}</div><div class="big">${greet}${name ? ", " + esc(name) : ""}.</div><div class="t2">Ask about any photo or PDF, find and tidy files, or give me errands — I'll open the apps and do the tapping. You approve before anything is booked, paid or sent.</div></div>`;
    h += setupBanners();
    h += `<div class="suggest">${SUGGEST.map((s, i) => `<button class="sg" data-a="suggest" data-i="${i}" style="--c:${s[1]}"><span class="ic">${ic(s[0])}</span><b>${s[2]}</b><span>${s[3]}</span></button>`).join("")}</div>`;
    h += `<div class="hint" style="padding:0 6px">Tip: chain errands in one message — “book an Ola to the station and order dinner from Zomato for 8 pm”.</div>`;
  } else {
    h += setupBanners();
    h += `<div class="msgs">${S.chat.map(m => msgHtml(m, true)).join("")}</div>`;
  }
  return h;
}
function msgHtml(m, quiet) {
  const cls = quiet ? ' style="animation:none"' : "";
  if (m.role === "user") {
    const att = (m.files || []).length ? `<div class="att">${m.files.map(p => { const k = kindOfPath(p); return hasThumb(k) ? `<img src="${thumb(p)}" alt="">` : `<span class="fchip">${esc(p.split("/").pop())}</span>`; }).join("")}</div>` : "";
    return `<div class="msg user" data-id="${m.id}"${cls}>${att}${md(m.text)}</div>`;
  }
  let h = "";
  if (m.typing && !m.text) h += `<div class="msg ai" data-id="${m.id}"${cls}><span class="typing"><i></i><i></i><i></i></span></div>`;
  else if (m.text) h += `<div class="msg ai${m.error ? " err" : ""}" data-id="${m.id}"${cls}>${md(m.text)}</div>`;
  (m.cards || []).forEach((c, i) => { h += `<div class="msg ai wide" data-card="${m.id}:${i}"${cls}>${cardHtml(m, c, i)}</div>`; });
  return h;
}
function kindOfPath(p) { const x = (p.split(".").pop() || "").toLowerCase(); if (/^(jpe?g|png|webp|heic|heif|gif|bmp)$/.test(x)) return "image"; if (x === "pdf") return "pdf"; if (/^(mp4|mkv|mov|webm|3gp|avi)$/.test(x)) return "video"; return "doc"; }
function updateMsg(m) {
  const box = $(".msgs"); if (S.tab !== "assistant") return; if (!box) { render(); return; }
  box.querySelectorAll(`[data-id="${m.id}"],[data-card^="${m.id}:"]`).forEach((el, i) => { if (i) el.remove(); });
  const first = box.querySelector(`[data-id="${m.id}"],[data-card^="${m.id}:"]`);
  const tmp = document.createElement("div"); tmp.innerHTML = msgHtml(m, !!first);
  if (first) first.replaceWith(...tmp.children); else box.append(...tmp.children);
  afterRender();
}

function fileRow(f, on = false) {
  const k = f.k, th = hasThumb(k) ? `<img data-lazy src="${thumb(f.p)}" alt="" loading="lazy">` : "";
  return `<div class="li${on ? " on" : ""}" data-a="file" data-p="${esc(f.p)}"><span class="sel">${ic("check", "s")}</span><span class="ico" style="background:color-mix(in srgb,${(KIND[k] || KIND.other)[2]} 22%,var(--sc-low));color:${(KIND[k] || KIND.other)[2]}">${ic(kIcon(k))}${th}</span><div class="grow"><div class="tt">${esc(f.n)}</div><div class="t2">${esc(f.sum ? f.sum : f.d)} · ${size(f.s)} · ${ago(f.m)}</div></div></div>`;
}
function cardHtml(m, c, i) {
  const ref = `data-m="${m.id}" data-c="${i}"`;
  if (c.kind === "files") {
    const items = c.items || [];
    const imgs = items.filter(f => hasThumb(f.k)).length > items.length * 0.6 && items.length > 1;
    let body = !items.length ? `<div class="t2">Nothing matched. ${N.hasFiles() ? "Try other words, or let AI describe your images & PDFs (Files → Describe with AI) so I can search inside them." : "Allow file access first."}</div>`
      : imgs ? `<div class="grid">${items.slice(0, 9).map(f => `<button class="tile" data-a="file" data-p="${esc(f.p)}"><img data-lazy src="${thumb(f.p)}" alt="${esc(f.n)}" loading="lazy">${f.k === "video" ? `<span class="vd">${ic("video", "s")}</span>` : ""}</button>`).join("")}</div>`
        : items.slice(0, 6).map(f => fileRow(f)).join("");
    return `<div class="rc"><div class="hd"><span class="ic">${ic("search", "s")}</span><b>${esc(c.title || "Files")}</b><span class="badge">${c.total || items.length}</span></div>${body}${c.total > 6 ? `<div class="row end" style="margin-top:8px"><button class="btn ghost sm" data-a="seeAll" ${ref}>See all</button></div>` : ""}</div>`;
  }
  if (c.kind === "email") {
    const sent = c.state === "sent";
    return `<div class="rc"><div class="hd"><span class="ic">${ic("mail", "s")}</span><b>Email draft</b>${sent ? `<span class="badge ok">${ic("check", "s")} Handed to ${esc(c.app || "Gmail")}</span>` : ""}</div>
      <label class="f">To</label><input data-f="to" ${ref} value="${esc(c.to)}" placeholder="name@example.com" inputmode="email">
      ${c.cc ? `<label class="f">Cc</label><input data-f="cc" ${ref} value="${esc(c.cc)}">` : ""}
      <label class="f">Subject</label><input data-f="subject" ${ref} value="${esc(c.subject)}">
      <label class="f">Message</label><textarea data-f="body" ${ref} rows="8">${esc(c.body)}</textarea>
      ${(c.attach || []).length ? `<label class="f">Attachments</label>${c.attach.map(p => `<div class="li" style="padding:6px"><span class="ico">${ic(kIcon(kindOfPath(p)))}</span><div class="grow tt">${esc(p.split("/").pop())}</div><button class="iconbtn plain" data-a="unattach" ${ref} data-p="${esc(p)}" aria-label="Remove">${ic("x", "s")}</button></div>`).join("")}` : ""}
      <div class="row end" style="margin-top:12px"><button class="btn ghost sm" data-a="emailAttach" ${ref}>${ic("plus", "s")} Attach</button><button class="btn sm" data-a="emailRewrite" ${ref}>${ic("wand", "s")} Rewrite</button><button class="btn sm" data-a="emailOpen" ${ref}>Open in app</button><button class="btn primary sm" data-a="emailSend" ${ref}>${ic("send", "s")} Send</button></div></div>`;
  }
  if (c.kind === "tasks") {
    const running = S.run && S.run.msgId === m.id && N.agentBusy();
    return `<div class="rc"><div class="hd"><span class="ic">${ic("bolt", "s")}</span><b>${c.tasks.length > 1 ? c.tasks.length + " errands" : "Errand"}</b>${running ? `<button class="btn danger sm" data-a="stopAgent">${ic("stop", "s")} Stop</button>` : c.state === "ready" ? "" : `<span class="badge">${esc(c.state || "")}</span>`}</div>
      ${c.tasks.map((t, j) => `<div class="card tight" style="margin:8px 0 0"><div class="row nowrap"><span class="badge pri">${esc(appName(t.app))}</span><div class="grow t2" style="color:var(--on-surface)">${esc(t.goal)}</div>${statusBadge(t.status)}</div>${(t.steps || []).length ? `<ul class="steps">${t.steps.slice(-30).map((s, k, a) => `<li class="${s.c || ""}${k === a.length - 1 && t.status === "running" ? " last" : ""}">${esc(s.t)}</li>`).join("")}</ul>` : ""}</div>`).join("")}
      ${c.state === "ready" ? `<div class="row end" style="margin-top:12px"><button class="btn ghost sm" data-a="taskEdit" ${ref}>Edit</button><button class="btn primary sm" data-a="taskStart" ${ref}>${ic("bolt", "s")} Start</button></div>` : ""}
      ${c.state === "ended" ? `<div class="row end" style="margin-top:10px"><button class="btn sm" data-a="taskStart" ${ref}>${ic("undo", "s")} Run again</button></div>` : ""}</div>`;
  }
  if (c.kind === "organize") {
    const groups = c.groups || [];
    return `<div class="rc"><div class="hd"><span class="ic">${ic("wand", "s")}</span><b>Plan for ${esc(c.rel)}</b><span class="badge">${c.count} files</span></div>
      ${groups.length ? groups.map(g => `<div class="li" style="padding:8px 4px"><span class="ico">${ic("folder")}</span><div class="grow"><div class="tt">${esc(g.name)}</div><div class="t2">${g.files.length} file${g.files.length > 1 ? "s" : ""} · ${esc(g.files.slice(0, 3).map(f => f.n).join(", "))}${g.files.length > 3 ? "…" : ""}</div></div></div>`).join("") : `<div class="t2">This folder is already tidy.</div>`}
      ${c.state === "done" ? `<div class="row" style="margin-top:10px"><span class="badge ok">${ic("check", "s")} Moved ${c.moved} files</span><span class="grow"></span><button class="btn sm" data-a="undoOrg" ${ref}>${ic("undo", "s")} Undo</button></div>`
        : c.state === "undone" ? `<div class="row" style="margin-top:10px"><span class="badge">Undone</span></div>`
          : groups.length ? `<div class="row end" style="margin-top:12px"><button class="btn primary sm" data-a="applyOrg" ${ref}>${ic("check", "s")} Move files</button></div>` : ""}</div>`;
  }
  if (c.kind === "setup") return `<div class="rc">${c.html}</div>`;
  return "";
}
function statusBadge(s) {
  if (s === "done") return `<span class="badge ok">${ic("check", "s")}</span>`;
  if (s === "fail" || s === "cancel") return `<span class="badge warn">${ic("x", "s")}</span>`;
  if (s === "running") return `<span class="badge pri">…</span>`;
  return "";
}
function appName(a) { a = String(a || "").toLowerCase(); const m = { uber: "Uber", ola: "Ola", rapido: "Rapido", swiggy: "Swiggy", instamart: "Instamart", zomato: "Zomato", blinkit: "Blinkit", zepto: "Zepto", gmail: "Gmail", whatsapp: "WhatsApp", amazon: "Amazon", flipkart: "Flipkart", myntra: "Myntra", meesho: "Meesho", ajio: "AJIO", nykaa: "Nykaa", jiomart: "JioMart", bigbasket: "BigBasket", bookmyshow: "BookMyShow", irctc: "IRCTC", redbus: "redBus", makemytrip: "MakeMyTrip" }; return m[a] || (a ? a[0].toUpperCase() + a.slice(1) : "Phone"); }

// ---------- sending ----------
function addMsg(m) { m.id = m.id || uid(); S.chat.push(m); saveChat(); if (S.tab === "assistant") { if (!$(".msgs")) render(); else updateMsg(m); } return m; }
async function send(text) {
  text = (text || "").trim(); const files = S.pend.slice();
  if (!text && !files.length) return;
  if (!N.aiReady()) { toast("Add an AI key in Settings first"); go("settings"); return; }
  $("#q").value = ""; autosize(); S.pend = []; renderPend(); tick();
  addMsg({ role: "user", text: text || "What's in this?", files });
  const reply = addMsg({ role: "ai", text: "", typing: true, cards: [] });
  try {
    const r = await route(text || "Tell me what this file contains.", files);
    reply.typing = false; reply.text = r.say || "";
    updateMsg(reply); saveChat();
    const acts = Array.isArray(r.actions) ? r.actions : [];
    const tasks = acts.filter(a => a && a.type === "phone_task");
    for (const a of acts) { if (a && a.type !== "phone_task") await runAction(a, reply, text); }
    if (tasks.length) await queueTasks(reply, tasks.map(t => ({ app: t.app || "", goal: t.goal || text })));
    if (!reply.text && !(reply.cards || []).length) { reply.text = "Done."; updateMsg(reply); }
    saveChat();
  } catch (e) {
    reply.typing = false; reply.error = true; reply.text = "⚠️ " + e.message; updateMsg(reply); saveChat();
  }
}
function history(n = 10) {
  const out = [];
  for (const m of S.chat.slice(-n - 2, -2)) {
    if (m.role === "user") out.push({ role: "user", parts: [{ text: m.text + ((m.files || []).length ? " [attached: " + m.files.map(p => p.split("/").pop()).join(", ") + "]" : "") }] });
    else if (m.text || (m.cards || []).length) {
      let t = m.text || ""; for (const c of m.cards || []) {
        if (c.kind === "files") t += `\n[showed ${c.total} files for "${c.q}": ${(c.items || []).slice(0, 5).map(f => f.n + " (" + f.p + ")").join("; ")}]`;
        if (c.kind === "tasks") t += `\n[errands: ${c.tasks.map(x => appName(x.app) + ": " + x.goal + " → " + (x.status || "not started") + (x.summary ? " (" + x.summary + ")" : "")).join("; ")}]`;
        if (c.kind === "email") t += `\n[email draft to ${c.to}: ${c.subject}]`;
      }
      out.push({ role: "assistant", parts: [{ text: t || "(ok)" }] });
    }
  }
  // providers want the conversation to start with the user
  while (out.length && out[0].role !== "user") out.shift();
  return out;
}
let appsCache = null;
function installedApps() { if (!appsCache) appsCache = J(N.apps(), []); return appsCache; }
async function route(text, files) {
  const p = PREF();
  const apps = installedApps().map(a => a.key + " (" + a.label + ")").join(", ") || "unknown";
  let all = []; try { all = J(N.allApps(), []); } catch (e) { }
  const sys = `You are Atlas, an AI assistant built into the user's Android phone in India. Today is ${new Date().toLocaleString("en-IN", { weekday: "long", day: "numeric", month: "long", year: "numeric", hour: "numeric", minute: "2-digit" })}.
${p.user_name ? "The user's name is " + p.user_name + "." : ""}${p.user_email ? " Their email is " + p.user_email + "." : ""}${p.user_home ? " Home address: " + p.user_home + "." : ""}${p.user_work ? " Work address: " + p.user_work + "." : ""}
You can: search and read every file on the phone (images, PDFs, documents), organise folders, draft and send emails, and operate apps for the user through a phone agent (it taps and types inside the real apps and always asks the user before booking, paying, ordering or sending).
Known apps installed: ${apps}.
Every app on the phone (the agent can use any of them by name): ${all.join(", ").slice(0, 3000)}.
Reply with ONE JSON object: {"say": "short, warm reply in plain text (light markdown ok)", "actions": [ ... ]}.
Action types:
- {"type":"search_files","query":"keywords from file names or contents","kind":"image|pdf|doc|video|audio|apk|archive|screenshots|downloads|whatsapp|camera|large|any","sort":"score|recent|size"} — list matching files.
- {"type":"ask_files","query":"keywords to find the right file(s)","kind":"...","question":"what to find out from inside them"} — when the user wants information that is inside their files (an amount on a bill, a date in a ticket, a number on an ID card).
- {"type":"organize","folder":"path relative to storage root, e.g. Download or DCIM/Camera","mode":"type|ai"} — "type" sorts into Images/PDFs/Documents…; "ai" groups by topic.
- {"type":"duplicates"} — find duplicate files.
- {"type":"email","to":"","cc":"","subject":"","body":"the complete email text, signed off with the user's name if known","attach_query":"optional keywords of files to attach","attach_kind":"pdf|image|any"}
- {"type":"phone_task","app":"uber|ola|rapido|swiggy|instamart|zomato|blinkit|zepto|amazon|flipkart|myntra|meesho|ajio|nykaa|jiomart|bigbasket|bookmyshow|irctc|redbus|makemytrip|gmail|whatsapp|<any app name from the list>","goal":"a complete, self-contained instruction for the agent with every detail the user gave: pickup/drop, ride type, dish/restaurant, every item with quantity/size/colour/brand/budget, delivery time, notes"}
- {"type":"open_app","app":"name"}
Rules: Use several actions for several requests; errands run one after another in the given order. Don't invent addresses or recipients — if a cab request has no destination or an email has no recipient you can infer, ask in "say" and return no action for it (you may still draft the email with "to" empty). "home"/"office" are fine if the user says them (the app's saved places). Pick the app the user named; otherwise the best installed one (cabs: uber → ola → rapido; food: swiggy → zomato; groceries: instamart → blinkit → zepto; shopping: amazon → flipkart; fashion: myntra → ajio → meesho). For shopping, if the product is vague (no model, size or budget) ask one short question first unless the user said "any"/"cheapest"/"best rated". If files are attached to this message, answer about them directly in "say" with the details the user wants (amounts, dates, names, what the image shows) and return no actions unless asked. Keep "say" brief when actions do the work (e.g. "On it — opening Uber."). If it's just conversation, actions = [].`;
  const parts = [{ text }];
  for (const f of files) parts.push({ file: f, as: "auto", pages: 6 });
  const msgs = [...history(), { role: "user", parts }];
  return aiJson({ system: sys, maxTokens: 3000, messages: msgs });
}

// ---------- actions ----------
async function findFiles(q, kind, sort, limit = 30) {
  let r = J(N.search(q || "", kind || "any", sort || "score", 0, limit, ""), { total: 0, items: [] });
  if (!r.total && q && q.trim().split(/\s+/).length > 1) {
    // fall back to any single word, best first
    const seen = new Map();
    for (const w of q.trim().split(/\s+/).filter(w => w.length > 2)) {
      const rr = J(N.search(w, kind || "any", "score", 0, 20, ""), { items: [] });
      rr.items.forEach((f, i) => { const s = (seen.get(f.p) || { f, s: 0 }); s.s += 20 - i; seen.set(f.p, s); });
    }
    const items = [...seen.values()].sort((a, b) => b.s - a.s).map(x => x.f);
    r = { total: items.length, items: items.slice(0, limit) };
  }
  return r;
}
async function runAction(a, reply, userText) {
  const push = c => { reply.cards = reply.cards || []; reply.cards.push(c); updateMsg(reply); saveChat(); return c; };
  if (a.type === "search_files") {
    if (!N.hasFiles()) { push(setupCard("files")); return; }
    const r = await findFiles(a.query, a.kind, a.sort);
    push({ kind: "files", title: a.query ? "“" + a.query + "”" : (KIND[a.kind] || ["Files"])[0], q: a.query || "", fk: a.kind || "any", sort: a.sort || "", total: r.total, items: r.items.slice(0, 9) });
  } else if (a.type === "ask_files") {
    if (!N.hasFiles()) { push(setupCard("files")); return; }
    const r = await findFiles(a.query, a.kind, "score", 12);
    const pick = r.items.filter(f => ["image", "pdf", "doc"].includes(f.k)).slice(0, 3);
    if (!pick.length) { reply.text = (reply.text ? reply.text + "\n\n" : "") + "I couldn't find a matching file. If it's a photo or scan, let me describe your files first (Files → Describe with AI) — then I can search by what's inside."; updateMsg(reply); return; }
    const c = push({ kind: "files", title: "Read " + pick.length + " file" + (pick.length > 1 ? "s" : ""), q: a.query, fk: a.kind || "any", total: pick.length, items: pick });
    reply.typing = true; updateMsg(reply);
    const parts = [{ text: `Question: ${a.question || userText}\nAnswer from the attached files. Say which file the answer came from. Be specific (amounts, dates, numbers). If the answer isn't in them, say so.` }];
    pick.forEach(f => parts.push({ file: f.p, as: "auto", pages: 5 }));
    const ans = await ai({ system: "You read the user's own files on their phone and answer questions about them precisely and briefly.", maxTokens: 1500, messages: [{ role: "user", parts }] });
    reply.typing = false; reply.text = (reply.text ? reply.text + "\n\n" : "") + ans; updateMsg(reply);
  } else if (a.type === "organize") {
    if (!N.hasFiles()) { push(setupCard("files")); return; }
    const c = await planOrganize(a.folder || "Download", a.mode || "type");
    push(c);
  } else if (a.type === "duplicates") {
    S.files.mode = "dupes"; go("files");
  } else if (a.type === "email") {
    let attach = [];
    if (a.attach_query && N.hasFiles()) { const r = await findFiles(a.attach_query, a.attach_kind || "any", "score", 3); attach = r.items.slice(0, 1).map(f => f.p); }
    push({ kind: "email", to: a.to || "", cc: a.cc || "", subject: a.subject || "", body: a.body || "", attach });
  } else if (a.type === "open_app") {
    if (!N.launch(a.app || "")) toast((a.app || "That app") + " isn't installed");
  }
}
function setupCard(what) {
  if (what === "files") return { kind: "setup", html: `<div class="hd"><span class="ic">${ic("folder", "s")}</span><b>Allow file access</b></div><div class="t2">Atlas needs “All files access” to search your phone.</div><div class="row end" style="margin-top:10px"><button class="btn primary sm" data-a="askFiles">Allow</button></div>` };
  return { kind: "setup", html: `<div class="hd"><span class="ic">${ic("hand", "s")}</span><b>Switch on the phone agent</b></div><div class="t2">Settings → Accessibility → Installed apps → Atlas agent → On. If it's greyed out: App info → ⋮ → Allow restricted settings first.</div><div class="row end" style="margin-top:10px"><button class="btn ghost sm" data-a="openAppInfo">App info</button><button class="btn primary sm" data-a="openA11y">Open settings</button></div>` };
}

// ---------- errands ----------
async function queueTasks(reply, tasks) {
  const c = { kind: "tasks", tasks: tasks.map(t => ({ ...t, status: "", steps: [] })), state: "ready" };
  reply.cards = reply.cards || []; reply.cards.push(c); updateMsg(reply); saveChat();
  if (PREF().autostart !== "0") startTasks(reply, c);
}
function startTasks(m, c) {
  if (!N.agentOn()) { m.cards.push(setupCard("agent")); updateMsg(m); saveChat(); toast("Switch on the Atlas agent first"); return; }
  const r = N.runTasks(JSON.stringify(c.tasks.map(t => ({ app: t.app, goal: t.goal }))));
  if (r === "busy") { toast("Atlas is already working on something — stop it first"); return; }
  if (r === "nokey") { toast("Add an AI key in Settings"); return; }
  if (r !== "ok") { toast("Couldn't start the agent"); return; }
  c.state = "running"; c.tasks.forEach(t => { t.status = ""; t.steps = []; });
  S.run = { msgId: m.id }; updateMsg(m); saveChat();
}
window.atlasAgentEvent = ev => {
  const m = S.run && S.chat.find(x => x.id === S.run.msgId); const c = m && (m.cards || []).find(x => x.kind === "tasks");
  if (c) {
    const t = ev.task != null ? c.tasks[ev.task] : null;
    const add = (tt, cl) => { if (t) { t.steps = t.steps || []; t.steps.push({ t: tt, c: cl }); } };
    switch (ev.type) {
      case "task": if (t) t.status = "running"; break;
      case "step": add(ev.text); break;
      case "ask": add("❓ " + ev.text, "ask"); break;
      case "confirm": { const rt = c.tasks.find(x => x.status === "running"); if (rt) rt.steps.push({ t: "Waiting for your OK: " + ev.text, c: "ask" }); break; }
      case "confirmed": { const rt = c.tasks.find(x => x.status === "running"); if (rt) rt.steps.push({ t: "You confirmed", c: "ok" }); break; }
      case "cancelled": { const rt = c.tasks.find(x => x.status === "running"); if (rt) { rt.steps.push({ t: ev.text, c: "bad" }); rt.status = "cancel"; } break; }
      case "done": if (t) { t.status = "done"; t.summary = ev.text; add(ev.text, "ok"); } break;
      case "fail": if (t) { t.status = "fail"; t.summary = ev.text; add(ev.text, "bad"); } break;
      case "error": if (t) add(ev.text, "bad"); break;
      case "idle": c.state = "ended"; c.tasks.forEach(x => { if (x.status === "running" || !x.status) x.status = ev.text === "Stopped" ? "cancel" : x.status || ""; }); S.run = null; break;
    }
    saveChat(); updateMsg(m);
  }
  if (S.tab === "tasks") { const box = $("#log"); if (box) box.innerHTML = logHtml(); }
};

// ---------- organise ----------
const TYPE_DIR = { image: "Images", pdf: "PDFs", doc: "Documents", video: "Videos", audio: "Audio", apk: "APKs", archive: "Archives", other: "Other" };
async function planOrganize(folder, mode) {
  const root = N.root();
  const abs = folder.startsWith("/") ? folder : root + "/" + folder.replace(/^\/+/, "");
  const d = J(N.listDir(abs), { files: [] });
  const files = d.files || [];
  let groups = [];
  if (mode === "ai" && files.length) {
    const list = files.slice(0, 500).map((f, i) => `${i}\t${f.n}\t${f.k}\t${size(f.s)}\t${new Date(f.m).toISOString().slice(0, 10)}${f.sum ? "\t" + f.sum.slice(0, 120) : ""}`).join("\n");
    const r = await aiJson({ system: "You organise a phone folder into a few clear sub-folders by topic or purpose (e.g. Bank statements, Tickets & bookings, Bills, ID documents, Work, Wallpapers, Memes, Installers). Use 3–10 folder names in Title Case. Leave files you are unsure about out.", maxTokens: 4000, messages: [{ role: "user", parts: [{ text: `Folder: ${d.rel}\nFiles (index, name, type, size, date, summary):\n${list}\n\nReply JSON: {"folders":[{"name":"Folder name","files":[indexes]}]}` }] }] });
    for (const g of r.folders || []) { const fs = (g.files || []).map(i => files[i]).filter(Boolean); if (fs.length && g.name) groups.push({ name: String(g.name).replace(/[\\/:*?"<>|]/g, "-").slice(0, 60), files: fs }); }
  } else {
    const by = {}; for (const f of files) (by[TYPE_DIR[f.k] || "Other"] = by[TYPE_DIR[f.k] || "Other"] || []).push(f);
    groups = Object.entries(by).map(([name, fs]) => ({ name, files: fs })).sort((a, b) => b.files.length - a.files.length);
  }
  return { kind: "organize", dir: d.p || abs, rel: d.rel || folder, mode, count: files.length, groups: groups.map(g => ({ name: g.name, files: g.files.map(f => ({ p: f.p, n: f.n })) })) };
}
function applyOrganize(c) {
  const moves = []; let fail = 0;
  for (const g of c.groups) for (const f of g.files) { const r = J(N.move(f.p, c.dir + "/" + g.name), {}); if (r.ok) moves.push([r.path, c.dir]); else fail++; }
  c.state = "done"; c.moved = moves.length; c.undo = moves;
  toast("Moved " + moves.length + " files" + (fail ? " · " + fail + " failed" : ""));
}
function undoOrganize(c) { let n = 0; for (const [p, dir] of (c.undo || []).slice().reverse()) { const r = J(N.move(p, dir), {}); if (r.ok) n++; } c.state = "undone"; toast("Put back " + n + " files"); }

// ================================================================ FILES
function viewFiles() {
  const F = S.files;
  if (!N.hasFiles()) return `<div class="top"><h1>Files</h1></div><div class="hero"><div class="spark">${ic("folder")}</div><div class="big">See everything on your phone</div><div class="t2">Atlas needs “All files access” to list, search, describe and tidy your files. Files stay on the phone; only a file you ask the AI about is sent to your AI provider.</div><div class="row" style="margin-top:14px"><button class="btn primary" data-a="askFiles">Allow access</button></div></div>`;
  if (F.mode === "list") return viewFileList();
  if (F.mode === "browse") return viewBrowse();
  if (F.mode === "dupes") return viewDupes();
  const st = J(N.stats(), { kinds: {} }), K = st.kinds || {};
  const used = st.cap ? (st.cap - st.free) / st.cap : 0, R = 36, C = 2 * Math.PI * R;
  const todo = J(N.search("", "todo", "recent", 0, 1, ""), { total: 0 }).total;
  const cats = ["image", "pdf", "doc", "video", "audio", "apk", "archive", "downloads", "screenshots", "whatsapp", "camera", "large"];
  const count = k => { if (K[k]) return K[k].n + " · " + size(K[k].b); if (["downloads", "screenshots", "whatsapp", "camera", "large"].includes(k)) { const r = J(N.search("", k, "recent", 0, 0, ""), { total: 0, bytes: 0 }); return r.total + " · " + size(r.bytes); } return "0"; };
  const order = ["image", "video", "audio", "doc", "pdf", "apk", "archive", "other"];
  let h = `<div class="top"><div class="grow"><h1>Files</h1><div class="sub">${st.scanning ? "Indexing… " + (st.count || 0).toLocaleString("en-IN") + " found" : (st.files || 0).toLocaleString("en-IN") + " files" + (st.analyzed ? " · " + st.analyzed + " described" : "")}</div></div><button class="iconbtn" data-a="rescan" aria-label="Rescan">${ic("undo")}</button></div>
  <label class="search">${ic("search")}<input id="fq" type="search" placeholder="Search names and what's inside" value="${esc(F.q)}" enterkeyhint="search"><button class="iconbtn plain" data-a="fsearch" aria-label="Search">${ic("send")}</button></label>
  <div class="hero"><div class="store"><svg class="ring" viewBox="0 0 84 84"><circle class="bg" cx="42" cy="42" r="${R}"/><circle class="fg" cx="42" cy="42" r="${R}" stroke-dasharray="${C}" stroke-dashoffset="${C * (1 - used)}" transform="rotate(-90 42 42)"/><text x="42" y="47" text-anchor="middle" font-size="17" font-weight="700" fill="currentColor">${Math.round(used * 100)}%</text></svg>
  <div class="grow"><div class="k" style="color:inherit;opacity:.75">STORAGE</div><div style="font-size:22px;font-weight:700">${size(st.cap - st.free)} <span style="font-size:14px;font-weight:500;opacity:.75">of ${size(st.cap)}</span></div><div class="t2">${size(st.free)} free</div></div></div>
  <div class="bars">${order.filter(k => K[k]).map(k => `<i style="width:${(K[k].b / Math.max(1, st.cap) * 100).toFixed(2)}%;background:${KIND[k][2]}" title="${KIND[k][0]}"></i>`).join("")}</div></div>
  <div class="cats">${cats.map(k => `<button class="cat" data-a="cat" data-k="${k}" style="--c:${KIND[k][2]}"><span class="ic">${ic(KIND[k][1])}</span><b>${KIND[k][0]}</b><span>${count(k)}</span></button>`).join("")}</div>
  <h2>Tools</h2>
  <div class="tools">
    <button class="tool" data-a="analyze"><span class="ic">${ic("spark")}</span><div><b>${S.analyzing ? "Describing…" : "Describe with AI"}</b><span>${S.analyzing ? "Tap to stop" : todo + " images & PDFs to go"}</span></div></button>
    <button class="tool" data-a="orgPick"><span class="ic">${ic("wand")}</span><div><b>Smart organise</b><span>Sort a folder</span></div></button>
    <button class="tool" data-a="dupes"><span class="ic">${ic("dup")}</span><div><b>Duplicates</b><span>Free up space</span></div></button>
    <button class="tool" data-a="browse"><span class="ic">${ic("tree")}</span><div><b>Browse folders</b><span>Internal storage</span></div></button>
    <button class="tool" data-a="bin"><span class="ic">${ic("bin")}</span><div><b>Atlas Bin</b><span>Deleted files</span></div></button>
    <button class="tool" data-a="cat" data-k="analyzed"><span class="ic">${ic("eye")}</span><div><b>Described</b><span>${st.analyzed || 0} files</span></div></button>
  </div>
  <h2>Recent</h2><div class="card tight">${J(N.search("", "any", "recent", 0, 8, ""), { items: [] }).items.map(f => fileRow(f)).join("") || `<div class="empty">${st.scanning ? "Indexing your phone…" : "No files yet"}</div>`}</div>`;
  return h;
}
function loadList(append) {
  const F = S.files, off = append ? F.items.length : 0;
  const r = J(N.search(F.q, F.kind || "any", F.sort, off, 90, F.folder || ""), { total: 0, items: [] });
  F.total = r.total; F.bytes = r.bytes; F.items = append ? F.items.concat(r.items) : r.items;
}
function viewFileList() {
  const F = S.files, isGrid = ["image", "video", "screenshots", "camera"].includes(F.kind) && !F.q, sel = F.sel.size > 0;
  const title = F.q ? "“" + F.q + "”" : F.folder ? F.folder.split("/").pop() : (KIND[F.kind] || ["Files"])[0];
  let h = `<div class="top"><button class="iconbtn" data-a="fhome" aria-label="Back">${ic("back")}</button><div class="grow"><h1 style="font-size:28px;line-height:34px">${esc(title)}</h1><div class="sub">${F.total.toLocaleString("en-IN")} files · ${size(F.bytes)}</div></div></div>`;
  if (F.q) h += `<label class="search">${ic("search")}<input id="fq" type="search" value="${esc(F.q)}" enterkeyhint="search"><button class="iconbtn plain" data-a="fsearch" aria-label="Search">${ic("send")}</button></label>`;
  h += `<div class="chips">${[["", "Best"], ["recent", "Newest"], ["old", "Oldest"], ["size", "Largest"], ["name", "Name"]].map(([k, l]) => `<button class="chip" data-a="fsort" data-k="${k}" aria-pressed="${F.sort === k}">${l}</button>`).join("")}${F.kind === "analyzed" ? "" : `<button class="chip" data-a="askAbout">${ic("spark", "s")} Ask about these</button>`}</div>`;
  if (!F.items.length) h += `<div class="empty">${ic("search")}<div>No files here.</div>${F.q ? `<div class="t2" style="margin-top:6px">Search looks at names, folders and AI descriptions.</div>` : ""}</div>`;
  else if (isGrid) h += `<div class="grid ${sel ? "selecting" : ""}">${F.items.map(f => `<button class="tile ${F.sel.has(f.p) ? "on" : ""}" data-a="file" data-p="${esc(f.p)}"><img data-lazy src="${thumb(f.p)}" alt="${esc(f.n)}" loading="lazy"><span class="sel">${ic("check", "s")}</span>${f.k === "video" ? `<span class="vd">${ic("video", "s")}</span>` : ""}</button>`).join("")}</div>`;
  else h += `<div class="card tight ${sel ? "selecting" : ""}">${F.items.map(f => fileRow(f, F.sel.has(f.p))).join("")}</div>`;
  if (F.items.length < F.total) h += `<div class="more"><button class="btn" data-a="fmore">Show more</button></div>`;
  if (sel) h += selBar();
  return h;
}
function selBar() {
  return `<div class="selbar"><b>${S.files.sel.size} selected</b><button class="iconbtn" data-a="selAsk" aria-label="Ask AI">${ic("spark")}</button><button class="iconbtn" data-a="selShare" aria-label="Share">${ic("share")}</button><button class="iconbtn" data-a="selMove" aria-label="Move">${ic("move")}</button><button class="iconbtn" data-a="selDel" aria-label="Delete">${ic("trash")}</button><button class="iconbtn" data-a="selClear" aria-label="Clear">${ic("x")}</button></div>`;
}
function viewBrowse() {
  const F = S.files, d = J(N.listDir(F.dir || ""), { dirs: [], files: [] });
  F.dirData = d;
  const parts = (d.rel === "Phone" ? [] : d.rel.split("/"));
  let h = `<div class="top"><button class="iconbtn" data-a="${d.up ? "bup" : "fhome"}" aria-label="Back">${ic("back")}</button><div class="grow"><h1 style="font-size:28px;line-height:34px">${esc(parts.length ? parts[parts.length - 1] : "Internal storage")}</h1><div class="sub">${d.dirs.length} folders · ${d.files.length} files</div></div><button class="iconbtn" data-a="orgHere" aria-label="Organise this folder">${ic("wand")}</button></div>`;
  h += `<div class="crumbs"><button data-a="bdir" data-p="">Phone</button>${parts.map((p, i) => `›<button data-a="bdir" data-p="${esc(N.root() + "/" + parts.slice(0, i + 1).join("/"))}">${esc(p)}</button>`).join("")}</div>`;
  h += `<div class="card tight ${S.files.sel.size ? "selecting" : ""}">${d.dirs.map(x => `<div class="li" data-a="bdir" data-p="${esc(x.p)}"><span class="ico">${ic("folder")}</span><div class="grow"><div class="tt">${esc(x.n)}</div><div class="t2">${x.c} items</div></div></div>`).join("")}${d.files.map(f => fileRow(f, S.files.sel.has(f.p))).join("")}${!d.dirs.length && !d.files.length ? `<div class="empty">Empty folder</div>` : ""}</div>`;
  if (S.files.sel.size) h += selBar();
  return h;
}
function viewDupes() {
  const F = S.files;
  let h = `<div class="top"><button class="iconbtn" data-a="fhome" aria-label="Back">${ic("back")}</button><div class="grow"><h1 style="font-size:28px;line-height:34px">Duplicates</h1><div class="sub">${F.dupes ? F.dupes.count + " groups · " + size(F.dupes.wasted) + " can be freed" : "Checking files…"}</div></div></div>`;
  if (!F.dupes) { h += `<div class="card"><span class="typing"><i></i><i></i><i></i></span> Comparing files with the same size…</div>`; if (!F.dupesLoading) { F.dupesLoading = true; call("duplicates").then(r => { F.dupes = J(r, { groups: [] }); F.dupesLoading = false; if (S.tab === "files" && F.mode === "dupes") render(); }).catch(e => { F.dupesLoading = false; toast(e.message); }); } return h; }
  if (!F.dupes.groups.length) return h + `<div class="empty">${ic("check")}<div>No duplicates found.</div></div>`;
  h += `<div class="row end" style="margin-bottom:10px"><button class="btn tonal sm" data-a="dupKeep">Keep oldest of each, delete the rest</button></div>`;
  h += F.dupes.groups.map((g, gi) => `<div class="card tight"><div class="k" style="padding:4px 6px">${g.length} copies · ${size(g[0].s)} each</div>${g.map(f => fileRow(f)).join("")}</div>`).join("");
  return h;
}

// ---------- file sheet ----------
let sheetEl = null;
function openSheet(html, onClose) {
  closeSheet(true);
  const scr = document.createElement("div"); scr.className = "scrim"; const sh = document.createElement("div"); sh.className = "sheet"; sh.innerHTML = `<div class="grab"></div>` + html;
  document.body.append(scr, sh); document.body.classList.add("sheeting");
  requestAnimationFrame(() => { scr.classList.add("in"); sh.classList.add("in"); });
  scr.onclick = () => closeSheet();
  sheetEl = { scr, sh, onClose }; canBack();
  sh.querySelectorAll("img[data-lazy]").forEach(img => img.onload = () => img.classList.add("in"));
  return sh;
}
function closeSheet(now) {
  if (!sheetEl) return; const { scr, sh, onClose } = sheetEl; sheetEl = null;
  document.body.classList.remove("sheeting");
  if (now) { scr.remove(); sh.remove(); } else { scr.classList.remove("in"); sh.classList.remove("in"); setTimeout(() => { scr.remove(); sh.remove(); }, 450); }
  if (onClose) onClose(); canBack();
}
function fileSheet(path) {
  const f = J(N.info(path), {}); if (f.missing) { toast("That file has been moved or deleted"); return; }
  const aiInfo = f.ai;
  const prev = hasThumb(f.k) ? `<div class="preview"><img data-lazy src="${big(f.p)}" alt=""></div>` : `<div class="preview" style="min-height:140px;color:${(KIND[f.k] || KIND.other)[2]}">${ic(kIcon(f.k), "l")}</div>`;
  const sh = openSheet(`${prev}<h3>${esc(f.n)}</h3><div class="t2">${esc(f.d)} · ${size(f.s)} · ${new Date(f.m).toLocaleString("en-IN", { dateStyle: "medium", timeStyle: "short" })}${f.pages ? " · " + f.pages + " pages" : ""}</div>
    <div class="aibox" id="aibox">${aiInfo ? aiBox(aiInfo) : ["image", "pdf", "doc"].includes(f.k) ? `<div class="row nowrap"><div class="grow t2">Ask the AI what this ${f.k === "image" ? "picture shows" : "document says"}.</div><button class="btn tonal sm" data-a="describe" data-p="${esc(f.p)}">${ic("spark", "s")} What's in this?</button></div>` : `<div class="t2">Atlas can read images, PDFs and text documents.</div>`}</div>
    ${["image", "pdf", "doc"].includes(f.k) ? `<label class="search" style="margin:0"><input id="askq" placeholder="Ask about this file…" enterkeyhint="send"><button class="iconbtn plain" data-a="askFile" data-p="${esc(f.p)}" aria-label="Ask">${ic("send")}</button></label><div id="askans"></div>` : ""}
    <div class="acts"><button data-a="fopen" data-p="${esc(f.p)}"><i>${ic("open")}</i>Open</button><button data-a="fshare" data-p="${esc(f.p)}"><i>${ic("share")}</i>Share</button><button data-a="frename" data-p="${esc(f.p)}"><i>${ic("pen")}</i>Rename</button><button data-a="fmove" data-p="${esc(f.p)}"><i>${ic("move")}</i>Move</button><button class="bad" data-a="fdel" data-p="${esc(f.p)}"><i>${ic("trash")}</i>Delete</button></div>
    <div class="row" style="margin-top:6px"><button class="btn ghost sm" data-a="toChat" data-p="${esc(f.p)}">${ic("chat", "s")} Use in chat</button></div>`);
  const q = sh.querySelector("#askq"); if (q) q.addEventListener("keydown", e => { if (e.key === "Enter") { e.preventDefault(); askFile(f.p, q.value); } });
}
function aiBox(a) {
  return `<div class="k">${ic("spark", "s")} ${esc((a.category || "AI summary").toUpperCase())}</div><div style="margin-top:6px">${md(a.summary || "")}</div>${a.text ? `<div class="t2" style="margin-top:8px;white-space:pre-wrap;max-height:160px;overflow:auto">${esc(a.text)}</div>` : ""}${(a.tags || []).length ? `<div class="tags">${a.tags.map(t => `<span class="badge">${esc(t)}</span>`).join("")}</div>` : ""}<div class="row end" style="margin-top:6px"><button class="btn ghost sm" data-a="copySum">${ic("copy", "s")} Copy</button></div>`;
}
const DESCRIBE = `Describe this file from the user's phone for a personal search index and for the user to read.
Reply JSON: {"category":"one of: photo, screenshot, receipt, bill, invoice, bank statement, ticket, ID document, certificate, form, letter, notes, chart, meme, wallpaper, document, other",
"summary":"2–4 plain sentences: what it is and the key facts (who/what, amounts with ₹, dates, places, reference numbers that are not sensitive)",
"text":"the important visible text, max 600 chars (omit full ID/account/card numbers — show only last 4 digits)",
"tags":["5-10 short lowercase search keywords incl. synonyms, e.g. 'electricity','msedcl','bill','october 2026'"]}`;
async function describe(path, quiet) {
  const t = await ai({ json: true, maxTokens: 1200, system: "You catalogue the user's own files accurately and concisely.", messages: [{ role: "user", parts: [{ file: path, as: "pages", pages: 2, max: 1100 }, { text: DESCRIBE }] }] });
  const j = parseJson(t); if (!j.summary) j.summary = j.say || String(t).slice(0, 400);
  if (!Array.isArray(j.tags)) j.tags = [];
  N.putContent(path, JSON.stringify({ summary: j.summary, category: j.category || "", text: j.text || "", tags: j.tags }));
  return j;
}
async function askFile(path, q) {
  q = (q || "").trim(); if (!q) return; const box = $("#askans"); if (!box) return;
  box.innerHTML = `<div class="aibox"><span class="typing"><i></i><i></i><i></i></span></div>`;
  try { const a = await ai({ system: "Answer questions about the user's own file precisely. Use ₹ for rupees.", maxTokens: 1500, messages: [{ role: "user", parts: [{ file: path, as: "auto", pages: 8 }, { text: q }] }] }); box.innerHTML = `<div class="aibox">${md(a)}</div>`; }
  catch (e) { box.innerHTML = `<div class="aibox" style="color:var(--error)">${esc(e.message)}</div>`; }
}

// ---------- background describing ----------
async function analyzeAll() {
  if (S.analyzing) { S.analyzing = false; toast("Stopping after the current file"); return; }
  if (!N.aiReady()) { toast("Add an AI key in Settings first"); return; }
  const r = J(N.search("", "todo", "recent", 0, 400, ""), { items: [] });
  if (!r.items.length) { toast("Everything is already described"); return; }
  const limit = +(PREF().analyze_batch || 60);
  S.analyzing = true; if (S.tab === "files") render();
  let done = 0, fail = 0;
  for (const f of r.items.slice(0, limit)) {
    if (!S.analyzing) break;
    try { await describe(f.p, true); done++; } catch (e) { fail++; if (/rate|quota|429/i.test(e.message)) { toast("Rate limited — pausing 30 s"); await new Promise(r => setTimeout(r, 30000)); } if (fail > 5 && !done) { toast(e.message); break; } }
    if (done % 5 === 0 && S.tab === "files" && S.files.mode === "home") render();
  }
  S.analyzing = false; toast("Described " + done + " files" + (fail ? " · " + fail + " skipped" : "")); if (S.tab === "files") render();
}

// ---------- pickers ----------
function pickFolder(title, onPick, startDir) {
  let dir = startDir || "";
  const draw = () => {
    const d = J(N.listDir(dir), { dirs: [], files: [] });
    sh.innerHTML = `<div class="grab"></div><h3>${esc(title)}</h3><div class="crumbs" style="margin:6px 0 10px"><button data-pk="">Phone</button>${d.rel !== "Phone" ? d.rel.split("/").map((p, i, a) => `›<button data-pk="${esc(N.root() + "/" + a.slice(0, i + 1).join("/"))}">${esc(p)}</button>`).join("") : ""}</div>
      <div class="card tight" style="max-height:46vh;overflow:auto">${d.dirs.map(x => `<div class="li" data-pk="${esc(x.p)}"><span class="ico">${ic("folder")}</span><div class="grow tt">${esc(x.n)}</div></div>`).join("") || `<div class="empty">No sub-folders</div>`}</div>
      <div class="row nowrap" style="margin-top:10px"><input id="nf" placeholder="New folder name"><button class="btn sm" id="nfb">Create</button></div>
      <div class="row end" style="margin-top:12px"><button class="btn ghost" id="pkc">Cancel</button><button class="btn primary" id="pko">Choose this folder</button></div>`;
    sh.querySelectorAll("[data-pk]").forEach(b => b.onclick = () => { dir = b.dataset.pk; draw(); });
    sh.querySelector("#pkc").onclick = () => closeSheet();
    sh.querySelector("#pko").onclick = () => { const p = d.p; closeSheet(); onPick(p); };
    sh.querySelector("#nfb").onclick = () => { const n = sh.querySelector("#nf").value.trim(); if (!n) return; const r = J(N.mkdir(d.p + "/" + n.replace(/[\\/]/g, "-")), {}); if (r.ok) { dir = r.path; draw(); } else toast(r.error || "Couldn't create"); };
  };
  const sh = openSheet(""); draw();
}
function pickFiles(title, onPick, kind) {
  let q = "", k = kind || "any", chosen = new Set();
  const draw = () => {
    const r = J(N.search(q, k, q ? "score" : "recent", 0, 60, ""), { items: [] });
    sh.innerHTML = `<div class="grab"></div><h3>${esc(title)}</h3><label class="search" style="margin:8px 0"><input id="pq" type="search" placeholder="Search files" value="${esc(q)}"></label>
      <div class="chips">${["any", "image", "pdf", "doc", "screenshots", "downloads"].map(x => `<button class="chip" data-k="${x}" aria-pressed="${k === x}">${x === "any" ? "All" : KIND[x][0]}</button>`).join("")}</div>
      <div class="card tight selecting" style="max-height:48vh;overflow:auto">${r.items.map(f => fileRow(f, chosen.has(f.p))).join("") || `<div class="empty">No files</div>`}</div>
      <div class="row end" style="margin-top:12px"><button class="btn ghost" id="pc">Cancel</button><button class="btn primary" id="po" ${chosen.size ? "" : "disabled"}>Attach ${chosen.size || ""}</button></div>`;
    sh.querySelectorAll("img[data-lazy]").forEach(img => img.onload = () => img.classList.add("in"));
    const inp = sh.querySelector("#pq"); inp.onkeydown = e => { if (e.key === "Enter") { q = inp.value; draw(); } };
    sh.querySelectorAll(".chip[data-k]").forEach(b => b.onclick = () => { k = b.dataset.k; draw(); });
    sh.querySelectorAll(".li[data-p]").forEach(li => li.onclick = e => { e.stopPropagation(); const p = li.dataset.p; chosen.has(p) ? chosen.delete(p) : chosen.add(p); draw(); });
    sh.querySelector("#pc").onclick = () => closeSheet();
    sh.querySelector("#po").onclick = () => { const l = [...chosen]; closeSheet(); onPick(l); };
  };
  const sh = openSheet(""); draw();
}
function prompt2(title, value, onOk, ok = "Save") {
  const sh = openSheet(`<h3>${esc(title)}</h3><input id="p2" value="${esc(value)}" style="margin:12px 0"><div class="row end"><button class="btn ghost" id="p2c">Cancel</button><button class="btn primary" id="p2o">${esc(ok)}</button></div>`);
  const i = sh.querySelector("#p2"); setTimeout(() => { i.focus(); const dot = value.lastIndexOf("."); i.setSelectionRange(0, dot > 0 ? dot : value.length); }, 350);
  sh.querySelector("#p2c").onclick = () => closeSheet(); sh.querySelector("#p2o").onclick = () => { const v = i.value; closeSheet(); onOk(v); };
}
function confirm2(title, body, ok, onOk, danger) {
  const sh = openSheet(`<h3>${esc(title)}</h3><div class="t2" style="margin:8px 0 16px">${body}</div><div class="row end"><button class="btn ghost" id="c2c">Cancel</button><button class="btn ${danger ? "danger" : "primary"}" id="c2o">${esc(ok)}</button></div>`);
  sh.querySelector("#c2c").onclick = () => closeSheet(); sh.querySelector("#c2o").onclick = () => { closeSheet(); onOk(); };
}

// ================================================================ TASKS
const TEMPL = {
  ride: { t: "Book a ride", ic: "car", c: "#5b8def", apps: ["uber", "ola", "rapido"], fields: [["from", "Pickup", "Current location"], ["to", "Drop", "e.g. Nashik Road station"], ["type", "Ride type (optional)", "Auto, Mini, Sedan…"]], goal: v => `Book a ${v.type || "ride (cheapest sensible option)"} from ${v.from || "my current location"} to ${v.to}.` },
  food: { t: "Order food", ic: "food", c: "#e5534b", apps: ["swiggy", "zomato"], fields: [["what", "What to order", "e.g. 2 paneer butter masala, 4 butter naan"], ["where", "From (optional)", "Restaurant name"], ["note", "Notes (optional)", "less spicy, deliver at 8 pm"]], goal: v => `Order ${v.what}${v.where ? " from " + v.where : " from a well-rated restaurant nearby"} to my default address.${v.note ? " Notes: " + v.note + "." : ""}` },
  grocery: { t: "Groceries", ic: "cart", c: "#3b9c7a", apps: ["instamart", "blinkit", "zepto"], fields: [["list", "Shopping list (one per line)", "1 L Amul milk\n6 eggs\n1 kg onions", true]], goal: v => `Add these groceries to the cart and check out to my default address:\n${v.list}` },
  shop: { t: "Shop online", ic: "bag", c: "#e07b39", apps: ["amazon", "flipkart", "myntra", "meesho", "ajio", "nykaa", "jiomart"], fields: [["item", "What to buy", "e.g. boAt Airdopes 141, black"], ["qty", "Quantity / size (optional)", "1, size M…"], ["budget", "Budget (optional)", "under ₹1,500"]], goal: v => `Buy ${v.qty ? v.qty + " × " : ""}${v.item}${v.budget ? ", budget " + v.budget : ""}. Pick a well-rated listing that matches, add it to the cart and check out to my default address with my default payment.` },
  more: { t: "Any app", ic: "bolt", c: "#2b9bb0", apps: [], fields: [["app", "App name", "e.g. BookMyShow, IRCTC, Paytm"], ["goal", "What should Atlas do there?", "e.g. book 2 tickets for the 7 pm show of …", true]], goal: v => v.goal },
  mail: { t: "Write an email", ic: "mail", c: "#a35bd6", apps: [], fields: [["to", "To", "name@example.com"], ["about", "What it's about", "e.g. ask for leave on Monday, polite", true]], goal: null },
};
function viewTasks() {
  let h = `<div class="top"><div class="grow"><h1>Tasks</h1><div class="sub">${N.agentOn() ? "Agent is on" : "Agent is off"}${N.agentBusy() ? " · working now" : ""}</div></div>${N.agentBusy() ? `<button class="btn danger sm" data-a="stopAgent">${ic("stop", "s")} Stop</button>` : ""}</div>`;
  if (!N.agentOn()) h += `<div class="card">${setupCard("agent").html}</div>`;
  h += `<div class="suggest">${Object.entries(TEMPL).map(([k, t]) => `<button class="sg" data-a="templ" data-k="${k}" style="--c:${t.c}"><span class="ic">${ic(t.ic)}</span><b>${t.t}</b><span>${t.apps.length ? t.apps.slice(0, 3).map(appName).join(" · ") + (t.apps.length > 3 ? "…" : "") : k === "more" ? "Any installed app" : "Gmail"}</span></button>`).join("")}</div>`;
  h += `<div class="card"><div class="row nowrap"><span class="spark" style="width:38px;height:38px;border-radius:13px">${ic("shield", "s")}</span><div class="grow t2">The agent stops and shows <b>Confirm / Cancel</b> on top of the app before anything is booked, ordered, paid or sent. It never types passwords, OTPs or UPI PINs — it asks you to.</div></div></div>`;
  h += `<h2>Activity</h2><div class="card tight" id="log">${logHtml()}</div>`;
  return h;
}
function logHtml() {
  const L = J(N.agentLog(), []).slice(-60).reverse();
  if (!L.length) return `<div class="empty">${ic("history")}<div>Nothing yet. Start an errand from the Assistant or above.</div></div>`;
  const icon = { task: "bolt", step: "hand", ask: "chat", confirm: "shield", confirmed: "check", cancelled: "x", done: "check", fail: "x", error: "x", idle: "stop", ready: "check" };
  return L.map(e => `<div class="li" style="padding:8px 6px"><span class="ico" style="width:34px;height:34px;border-radius:12px;${e.type === "done" || e.type === "confirmed" ? "background:color-mix(in srgb,var(--ok) 20%,var(--sc-low));color:var(--ok)" : e.type === "fail" || e.type === "error" || e.type === "cancelled" ? "background:var(--error-c);color:var(--on-error-c)" : ""}">${ic(icon[e.type] || "hand", "s")}</span><div class="grow"><div style="font-size:14px;${e.type === "task" ? "font-weight:650" : ""}">${esc(e.text)}</div><div class="t2" style="font-size:12px">${ago(e.t)}</div></div></div>`).join("");
}
function templSheet(k, preset) {
  const t = TEMPL[k]; let app = (preset && preset.app) || t.apps.find(a => installedApps().some(x => x.key === a)) || t.apps[0];
  const sh = openSheet(`<h3>${t.t}</h3>${t.apps.length ? `<div class="chips" style="margin-top:10px">${t.apps.map(a => `<button class="chip" data-app="${a}" aria-pressed="${a === app}">${appName(a)}${installedApps().some(x => x.key === a) ? "" : " · not installed"}</button>`).join("")}</div>` : ""}
    ${t.fields.map(([id, l, ph, multi]) => `<label class="f">${l}</label>${multi ? `<textarea id="tf_${id}" rows="5" placeholder="${esc(ph)}"></textarea>` : `<input id="tf_${id}" placeholder="${esc(ph)}">`}`).join("")}
    <div class="row end" style="margin-top:16px"><button class="btn ghost" id="tc">Cancel</button><button class="btn primary" id="tg">${ic(k === "mail" ? "wand" : "bolt", "s")} ${k === "mail" ? "Draft it" : "Start"}</button></div>`);
  sh.querySelectorAll("[data-app]").forEach(b => b.onclick = () => { app = b.dataset.app; sh.querySelectorAll("[data-app]").forEach(x => x.setAttribute("aria-pressed", x.dataset.app === app)); });
  sh.querySelector("#tc").onclick = () => closeSheet();
  sh.querySelector("#tg").onclick = () => {
    const v = {}; t.fields.forEach(([id]) => v[id] = sh.querySelector("#tf_" + id).value.trim());
    if (k === "ride" && !v.to) { toast("Where to?"); return; }
    if (k === "food" && !v.what) { toast("What should I order?"); return; }
    if (k === "grocery" && !v.list) { toast("Add a few items"); return; }
    if (k === "shop" && !v.item) { toast("What should I buy?"); return; }
    if (k === "more") { if (!v.app || !v.goal) { toast("Add the app and what to do"); return; } app = v.app; }
    closeSheet(); go("assistant");
    if (k === "mail") { send(`Draft an email to ${v.to || "(ask me)"}: ${v.about}`); return; }
    const goal = t.goal(v);
    addMsg({ role: "user", text: `${t.t} on ${appName(app)} — ${goal}` });
    const m = addMsg({ role: "ai", text: "On it — opening " + appName(app) + ". I'll ask you before the final step.", cards: [] });
    queueTasks(m, [{ app, goal }]);
  };
}

// ================================================================ SETTINGS
function viewSettings() {
  const p = PREF(), prov = p.provider || "gemini";
  const perm = (ok, title, sub, act, btn) => `<div class="li"><span class="ico" style="${ok ? "background:color-mix(in srgb,var(--ok) 20%,var(--sc-low));color:var(--ok)" : "background:var(--error-c);color:var(--on-error-c)"}">${ic(ok ? "check" : "x")}</span><div class="grow"><div class="tt">${title}</div><div class="t2" style="white-space:normal">${sub}</div></div>${ok ? "" : `<button class="btn tonal sm" data-a="${act}">${btn}</button>`}</div>`;
  return `<div class="top"><div class="grow"><h1>Settings</h1><div class="sub">Atlas ${esc(N.version())}</div></div></div>
  <h2>AI provider</h2><div class="card">
    <div class="seg"><button data-a="prov" data-k="gemini" aria-pressed="${prov === "gemini"}">Google Gemini</button><button data-a="prov" data-k="claude" aria-pressed="${prov === "claude"}">Claude</button></div>
    <label class="f">Gemini API key ${prov === "gemini" ? "· in use" : ""}</label><input type="password" id="gk" data-pref="gemini_key" value="${esc(p.gemini_key || "")}" placeholder="From aistudio.google.com → Get API key" autocomplete="off">
    <label class="f">Gemini model</label><input id="gm" data-pref="gemini_model" value="${esc(p.gemini_model || "gemini-3.6-flash")}">
    <label class="f">Claude API key ${prov === "claude" ? "· in use" : ""}</label><input type="password" id="ck" data-pref="claude_key" value="${esc(p.claude_key || "")}" placeholder="From console.anthropic.com → API keys" autocomplete="off">
    <label class="f">Claude model</label><input id="cm" data-pref="claude_model" value="${esc(p.claude_model || "claude-sonnet-5-5")}">
    <div class="row end" style="margin-top:12px"><span class="grow t2" id="testres"></span><button class="btn ghost sm" data-a="keyHelp">How to get a key</button><button class="btn tonal sm" data-a="testKey">Test</button></div>
  </div>
  <h2>Permissions</h2><div class="card tight">
    ${perm(N.hasFiles(), "All files access", "Search, describe and organise files", "askFiles", "Allow")}
    ${perm(N.agentOn(), "Phone agent (accessibility)", "Tap and type in Uber, Swiggy, Gmail… On Android 13+, if it's greyed out: App info → ⋮ → Allow restricted settings.", "openA11y", "Turn on")}
    ${N.agentOn() ? "" : `<div class="row end" style="padding:0 6px 8px"><button class="btn ghost sm" data-a="openAppInfo">Open App info</button></div>`}
  </div>
  <h2>About you</h2><div class="card">
    <div class="t2">Used for email sign-offs and when you say “home” or “office”. Stays on this phone and goes to the AI only with your requests.</div>
    <label class="f">Your name</label><input data-pref="user_name" value="${esc(p.user_name || "")}" placeholder="Vishwajeet …">
    <label class="f">Your email</label><input data-pref="user_email" value="${esc(p.user_email || "")}" inputmode="email">
    <label class="f">Home</label><input data-pref="user_home" value="${esc(p.user_home || "")}" placeholder="Area, Nashik">
    <label class="f">Work</label><input data-pref="user_work" value="${esc(p.user_work || "")}">
  </div>
  <h2>Agent</h2><div class="card tight">
    <div class="li"><div class="grow"><div class="tt">Start errands right away</div><div class="t2" style="white-space:normal">Off: show the plan and wait for Start</div></div><label class="switch"><input type="checkbox" data-a="tog" data-k="autostart" ${p.autostart !== "0" ? "checked" : ""}><span></span></label></div>
    <div class="li"><div class="grow"><div class="tt">Send screenshots to the AI</div><div class="t2" style="white-space:normal">More accurate in apps with unlabeled buttons</div></div><label class="switch"><input type="checkbox" data-a="togb" data-k="screenshots" ${p.screenshots !== false ? "checked" : ""}><span></span></label></div>
    <div class="li"><div class="grow"><div class="tt">Step limit per errand</div></div><select data-a="steps" style="width:auto">${[25, 45, 70].map(n => `<option ${+(p.max_steps || 45) === n ? "selected" : ""}>${n}</option>`).join("")}</select></div>
  </div>
  <h2>Look</h2><div class="card">
    <div class="seg">${["system", "light", "dark"].map(m => `<button data-a="theme" data-k="${m}" aria-pressed="${(p.theme || "system") === m}">${m[0].toUpperCase() + m.slice(1)}</button>`).join("")}</div>
    <div class="chips" style="margin-top:12px">${Object.entries(SEEDS).filter(([k]) => k !== "wallpaper" || sysPal).map(([k, [l, c]]) => `<button class="chip" data-a="seed" data-k="${k}" aria-pressed="${(p.seed || "wallpaper") === k}">${c ? `<span style="width:14px;height:14px;border-radius:50%;background:${c}"></span>` : ic("palette", "s")}${l}</button>`).join("")}</div>
  </div>
  <h2>Data</h2><div class="card tight">
    <div class="li" data-a="clearChat"><span class="ico">${ic("chat")}</span><div class="grow"><div class="tt">Clear chat history</div></div></div>
    <div class="li" data-a="clearAi"><span class="ico">${ic("spark")}</span><div class="grow"><div class="tt">Forget AI descriptions of files</div></div></div>
  </div>
  <div class="hint" style="padding:6px 8px 0">Privacy: your files are indexed only on this phone. A file's contents (or the app screen while an errand runs) are sent to the AI provider you picked only when you ask. Keys are stored in the app's private storage.</div>`;
}

// ================================================================ events
document.addEventListener("click", async e => {
  const el = e.target.closest("[data-a]"); if (!el) return;
  const a = el.dataset.a, F = S.files;
  const card = () => { const m = S.chat.find(x => x.id === el.dataset.m); return [m, m && m.cards[+el.dataset.c]]; };
  switch (a) {
    case "tab": tick(); go(el.dataset.tab); break;
    case "suggest": { const s = SUGGEST[+el.dataset.i]; const q = $("#q"); q.value = s[4]; autosize(); q.focus(); q.setSelectionRange(q.value.length, q.value.length); break; }
    case "newChat": S.chat = []; saveChat(); render(); break;
    case "askFiles": N.askFiles(); break;
    case "openA11y": N.openAccessibility(); break;
    case "openAppInfo": N.openAppInfo(); break;
    case "agentHelp": openSheet(`<h3>Turning on the phone agent</h3><div style="margin:10px 0">${md("1. Tap **Turn on** — Android's Accessibility settings open.\n2. Go to **Installed apps → Atlas agent** and switch it on.\n3. If the switch is greyed out (“Restricted setting”), go back, tap **App info**, open the **⋮** menu (top right) and choose **Allow restricted settings**, then try again.\n\nAtlas only reads the screen while an errand you started is running, and asks before booking, paying or sending.")}</div><div class="row end"><button class="btn ghost" data-a="openAppInfo">App info</button><button class="btn primary" data-a="openA11y">Turn on</button></div>`); break;
    // chat cards
    case "seeAll": { const [m, c] = card(); if (!c) break; S.files = { ...S.files, mode: "list", kind: c.fk, q: c.q, sort: c.sort || "", folder: null, sel: new Set() }; loadList(); go("files"); render(); break; }
    case "emailSend": case "emailOpen": {
      const [m, c] = card(); if (!c) break; readEmailFields(m.id, c);
      const send = a === "emailSend";
      if (send && !c.to) { toast("Who should it go to?"); break; }
      const r = N.email(c.to, c.cc || "", c.subject, c.body, JSON.stringify(c.attach || []), "gmail", send);
      if (r.startsWith("error")) { toast("Couldn't open a mail app"); break; }
      c.state = "sent"; c.app = "Gmail"; saveChat(); updateMsg(m);
      if (send && r !== "agent") toast(N.agentOn() ? "Opened Gmail — tap Send" : "Opened Gmail — tap Send (turn on the agent to send automatically)");
      if (r === "agent") { const tc = { kind: "tasks", tasks: [{ app: "gmail", goal: "Send the email to " + c.to, status: "running", steps: [] }], state: "running" }; m.cards.push(tc); S.run = { msgId: m.id }; saveChat(); updateMsg(m); }
      break;
    }
    case "emailAttach": { const [m, c] = card(); if (!c) break; readEmailFields(m.id, c); pickFiles("Attach files", l => { c.attach = [...new Set([...(c.attach || []), ...l])]; saveChat(); updateMsg(m); }); break; }
    case "unattach": { const [m, c] = card(); if (!c) break; readEmailFields(m.id, c); c.attach = (c.attach || []).filter(p => p !== el.dataset.p); saveChat(); updateMsg(m); break; }
    case "emailRewrite": {
      const [m, c] = card(); if (!c) break; readEmailFields(m.id, c);
      prompt2("How should I change it?", "Make it more formal", async v => {
        if (!v.trim()) return; toast("Rewriting…");
        try { const r = await aiJson({ system: "You edit emails. Keep facts. Reply JSON {\"subject\":\"\",\"body\":\"\"}.", maxTokens: 2000, messages: [{ role: "user", parts: [{ text: `Instruction: ${v}\n\nSubject: ${c.subject}\n\n${c.body}` }] }] }); if (r.body) { c.subject = r.subject || c.subject; c.body = r.body; saveChat(); updateMsg(m); } }
        catch (err) { toast(err.message); }
      }, "Rewrite");
      break;
    }
    case "taskStart": { const [m, c] = card(); if (c) startTasks(m, c); break; }
    case "taskEdit": {
      const [m, c] = card(); if (!c) break;
      const sh = openSheet(`<h3>Edit errand${c.tasks.length > 1 ? "s" : ""}</h3>${c.tasks.map((t, i) => `<label class="f">${appName(t.app)}</label><textarea id="te${i}" rows="4">${esc(t.goal)}</textarea>`).join("")}<div class="row end" style="margin-top:14px"><button class="btn ghost" id="tec">Cancel</button><button class="btn primary" id="teo">Save</button></div>`);
      sh.querySelector("#tec").onclick = () => closeSheet();
      sh.querySelector("#teo").onclick = () => { c.tasks.forEach((t, i) => t.goal = sh.querySelector("#te" + i).value.trim() || t.goal); closeSheet(); saveChat(); updateMsg(m); };
      break;
    }
    case "stopAgent": N.stopAgent(); toast("Stopping…"); break;
    case "applyOrg": { const [m, c] = card(); if (!c) break; applyOrganize(c); saveChat(); updateMsg(m); break; }
    case "undoOrg": { const [m, c] = card(); if (!c) break; undoOrganize(c); saveChat(); updateMsg(m); break; }
    // files
    case "rescan": toast("Rescanning…"); call("scan").then(() => { appsCache = null; if (S.tab === "files") render(); toast("Index updated"); }); setTimeout(() => S.tab === "files" && render(), 400); break;
    case "fsearch": { const q = ($("#fq") || {}).value || ""; if (!q.trim()) break; S.files = { ...F, mode: "list", q: q.trim(), kind: "any", sort: "", folder: null, sel: new Set() }; loadList(); render(); break; }
    case "cat": S.files = { ...F, mode: "list", kind: el.dataset.k, q: "", sort: "", folder: null, sel: new Set() }; loadList(); render(); scrollTo(0, 0); break;
    case "fhome": S.files = { ...F, mode: "home", sel: new Set(), dupes: null }; render(); break;
    case "fsort": F.sort = el.dataset.k; loadList(); render(); break;
    case "fmore": loadList(true); render(); break;
    case "file": {
      const p = el.dataset.p;
      if (el.closest(".selecting") && (F.mode === "list" || F.mode === "browse")) { F.sel.has(p) ? F.sel.delete(p) : F.sel.add(p); tick(); render(); break; }
      fileSheet(p); break;
    }
    case "browse": S.files = { ...F, mode: "browse", dir: "", sel: new Set() }; render(); break;
    case "bin": S.files = { ...F, mode: "browse", dir: N.root() + "/Atlas Bin", sel: new Set() }; N.mkdir(N.root() + "/Atlas Bin"); render(); break;
    case "bdir": F.dir = el.dataset.p; F.sel = new Set(); render(); scrollTo(0, 0); break;
    case "bup": F.dir = (F.dirData && F.dirData.up) || ""; F.sel = new Set(); render(); break;
    case "dupes": S.files = { ...F, mode: "dupes", dupes: null }; render(); break;
    case "dupKeep": {
      const gs = (F.dupes && F.dupes.groups) || []; const del = gs.flatMap(g => g.slice(1).map(f => f.p));
      confirm2("Delete " + del.length + " copies?", `Keeps the oldest copy of each file and moves the others (${size(F.dupes.wasted)}) to Atlas Bin.`, "Delete copies", () => { let n = 0; del.forEach(p => { if (J(N.trash(p), {}).ok) n++; }); toast("Moved " + n + " copies to Atlas Bin"); F.dupes = null; render(); }, true);
      break;
    }
    case "analyze": analyzeAll(); break;
    case "orgPick": pickFolder("Which folder should I organise?", dir => orgSheet(dir), N.root() + "/Download"); break;
    case "orgHere": orgSheet(F.dir || N.root()); break;
    case "askAbout": { const items = F.items.slice(0, 5).map(f => f.p); S.pend = items.filter(p => ["image", "pdf", "doc"].includes(kindOfPath(p))).slice(0, 4); go("assistant"); renderPend(); $("#q").focus(); break; }
    case "selClear": F.sel = new Set(); render(); break;
    case "selShare": N.share(JSON.stringify([...F.sel])); break;
    case "selAsk": S.pend = [...F.sel].slice(0, 5); F.sel = new Set(); go("assistant"); renderPend(); $("#q").focus(); break;
    case "selMove": pickFolder("Move " + F.sel.size + " files to…", dir => { let n = 0; [...F.sel].forEach(p => { if (J(N.move(p, dir), {}).ok) n++; }); toast("Moved " + n + " files"); F.sel = new Set(); if (F.mode === "list") loadList(); render(); }); break;
    case "selDel": confirm2("Delete " + F.sel.size + " files?", "They go to the Atlas Bin folder, so you can get them back.", "Delete", () => { let n = 0; [...F.sel].forEach(p => { if (J(N.trash(p), {}).ok) n++; }); toast("Moved " + n + " to Atlas Bin"); F.sel = new Set(); if (F.mode === "list") loadList(); render(); }, true); break;
    // file sheet
    case "describe": {
      const p = el.dataset.p, box = $("#aibox"); box.innerHTML = `<span class="typing"><i></i><i></i><i></i></span> <span class="t2">Reading…</span>`;
      try { const j = await describe(p); box.innerHTML = aiBox(j); } catch (err) { box.innerHTML = `<div style="color:var(--error)">${esc(err.message)}</div>`; }
      break;
    }
    case "askFile": askFile(el.dataset.p, ($("#askq") || {}).value); break;
    case "copySum": { const t = $("#aibox").innerText.replace(/\s*Copy\s*$/, ""); try { await navigator.clipboard.writeText(t); toast("Copied"); } catch (err) { toast("Couldn't copy"); } break; }
    case "fopen": N.open(el.dataset.p); break;
    case "fshare": N.share(JSON.stringify([el.dataset.p])); break;
    case "frename": { const p = el.dataset.p; prompt2("Rename", p.split("/").pop(), v => { const r = J(N.rename(p, v), {}); if (r.ok) { toast("Renamed"); refreshFiles(); fileSheet(r.path); } else toast(r.error); }, "Rename"); break; }
    case "fmove": { const p = el.dataset.p; closeSheet(true); pickFolder("Move to…", dir => { const r = J(N.move(p, dir), {}); toast(r.ok ? "Moved" : r.error); refreshFiles(); }, p.split("/").slice(0, -1).join("/")); break; }
    case "fdel": { const p = el.dataset.p; const r = J(N.trash(p), {}); if (r.ok) { closeSheet(); toast(r.path ? "Moved to Atlas Bin" : "Deleted for good"); refreshFiles(); } else toast(r.error); break; }
    case "toChat": S.pend = [el.dataset.p]; closeSheet(true); go("assistant"); renderPend(); $("#q").focus(); break;
    // tasks
    case "templ": templSheet(el.dataset.k); break;
    // settings
    case "prov": setPref("provider", el.dataset.k); render(); break;
    case "keyHelp": openSheet(`<h3>Get a free Gemini key</h3><div style="margin:10px 0">${md("1. Open **aistudio.google.com** and sign in with your Google account.\n2. Tap **Get API key** → **Create API key** (pick or create a project if asked).\n3. Copy the key (it starts with **AIza…**).\n4. Come back here, choose **Google Gemini**, paste it in **Gemini API key**, then tap **Test**.\n\nThe free tier has per-minute limits — if errands pause with “rate limit”, wait a minute. Keep the key private.")}</div><div class="row end"><a class="btn primary" href="https://aistudio.google.com/app/apikey">Open AI Studio</a></div>`); break;
    case "testKey": {
      const out = $("#testres"); saveInputs(); out.textContent = "Testing…";
      try { const r = await ai({ maxTokens: 50, messages: [{ role: "user", parts: [{ text: "Reply with just: OK" }] }] }); out.textContent = "✓ Works (" + (PREF().provider || "gemini") + ")"; out.style.color = "var(--ok)"; }
      catch (err) { out.textContent = err.message; out.style.color = "var(--error)"; }
      break;
    }
    case "theme": setPref("theme", el.dataset.k); applyTheme(); render(); break;
    case "seed": setPref("seed", el.dataset.k); applyTheme(); render(); break;
    case "clearChat": confirm2("Clear chat history?", "Your errands' activity log stays.", "Clear", () => { S.chat = []; saveChat(); toast("Cleared"); }, true); break;
    case "clearAi": confirm2("Forget all AI descriptions?", "Search will only use file names until files are described again.", "Forget", () => { N.clearContent(); toast("Done"); }, true); break;
  }
});
document.addEventListener("change", e => {
  const el = e.target;
  if (el.dataset.a === "tog") setPref(el.dataset.k, el.checked ? "1" : "0");
  if (el.dataset.a === "togb") N.setBool(el.dataset.k, el.checked);
  if (el.dataset.a === "steps") N.setInt("max_steps", +el.value);
  if (el.dataset.pref) setPref(el.dataset.pref, el.value.trim());
  if (el.dataset.f) { const m = S.chat.find(x => x.id === el.dataset.m); const c = m && m.cards[+el.dataset.c]; if (c) { c[el.dataset.f] = el.value; saveChat(); } }
});
document.addEventListener("keydown", e => { if (e.key === "Enter" && e.target.id === "fq") { e.preventDefault(); document.querySelector('[data-a="fsearch"]').click(); } });
function saveInputs() { document.querySelectorAll("[data-pref]").forEach(i => setPref(i.dataset.pref, i.value.trim())); }
function readEmailFields(mid, c) { document.querySelectorAll(`[data-f][data-m="${mid}"]`).forEach(i => c[i.dataset.f] = i.value); saveChat(); }
function refreshFiles() { if (S.tab !== "files") return; if (S.files.mode === "list") loadList(); render(); }
function orgSheet(dir) {
  const rel = dir.replace(N.root(), "").replace(/^\//, "") || "Phone";
  const sh = openSheet(`<h3>Organise “${esc(rel)}”</h3><div class="t2" style="margin:6px 0 14px">Only files directly in this folder are moved; sub-folders stay as they are. You'll see the plan first, and can undo.</div>
    <div class="li" data-o="type"><span class="ico">${ic("sort")}</span><div class="grow"><div class="tt">By type</div><div class="t2" style="white-space:normal">Images, PDFs, Documents, Videos, APKs… Instant, offline.</div></div></div>
    <div class="li" data-o="ai"><span class="ico">${ic("spark")}</span><div class="grow"><div class="tt">By topic with AI</div><div class="t2" style="white-space:normal">Bills, Tickets, Bank statements, IDs… Sends file names (and descriptions) to the AI.</div></div></div>`);
  sh.querySelectorAll("[data-o]").forEach(li => li.onclick = async () => {
    closeSheet(); go("assistant");
    addMsg({ role: "user", text: `Organise ${rel} ${li.dataset.o === "ai" ? "by topic" : "by type"}` });
    const m = addMsg({ role: "ai", text: "", typing: true, cards: [] });
    try { const c = await planOrganize(dir, li.dataset.o); m.typing = false; m.text = c.groups.length ? "Here's my plan — nothing moves until you tap **Move files**." : ""; m.cards.push(c); }
    catch (err) { m.typing = false; m.text = "⚠️ " + err.message; }
    updateMsg(m); saveChat();
  });
}

// ---------- composer ----------
function autosize() { const q = $("#q"); q.style.height = "auto"; q.style.height = Math.min(140, q.scrollHeight) + "px"; }
function renderPend() { $("#pend").innerHTML = S.pend.map((p, i) => `<span>${ic(kIcon(kindOfPath(p)), "s")} ${esc(p.split("/").pop().slice(0, 28))}<button data-rm="${i}" aria-label="Remove">${ic("x", "s")}</button></span>`).join(""); $("#pend").querySelectorAll("[data-rm]").forEach(b => b.onclick = () => { S.pend.splice(+b.dataset.rm, 1); renderPend(); }); }
$("#attach").innerHTML = ic("plus"); $("#mic").innerHTML = ic("mic"); $("#send").innerHTML = ic("send");
$("#q").addEventListener("input", autosize);
$("#q").addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey && !matchMedia("(pointer:coarse)").matches) { e.preventDefault(); send($("#q").value); } });
$("#send").onclick = () => send($("#q").value);
$("#attach").onclick = () => { if (!N.hasFiles()) { N.askFiles(); return; } pickFiles("Attach from your phone", l => { S.pend = [...new Set([...S.pend, ...l])].slice(0, 6); renderPend(); }); };
$("#mic").onclick = () => { call("voice").then(t => { $("#q").value = t; autosize(); send(t); }).catch(() => { }); };

// ---------- back, resume, share ----------
function canBack() { try { N.setCanGoBack(!!sheetEl || (S.tab === "files" && S.files.mode !== "home") || S.tab !== "assistant"); } catch (e) { } }
window.atlasBack = () => {
  if (sheetEl) { closeSheet(); return true; }
  if (S.tab === "files" && S.files.sel.size) { S.files.sel = new Set(); render(); return true; }
  if (S.tab === "files" && S.files.mode === "browse" && S.files.dirData && S.files.dirData.up) { S.files.dir = S.files.dirData.up; render(); return true; }
  if (S.tab === "files" && S.files.mode !== "home") { S.files.mode = "home"; render(); return true; }
  if (S.tab !== "assistant") { go("assistant"); return true; }
  return false;
};
window.atlasResume = () => { appsCache = null; if (!sheetEl && S.tab !== "assistant") render(); else if (S.tab === "assistant" && !S.chat.length) render(); else if (S.tab === "assistant") { const m = S.run && S.chat.find(x => x.id === S.run.msgId); if (m) updateMsg(m); } if (N.hasFiles() && !J(N.stats(), {}).files && !J(N.stats(), {}).scanning) call("scan").then(() => S.tab === "files" && render()); };
window.atlasIndexed = () => { if (S.tab === "files" && !sheetEl) render(); };
window.atlasShared = o => { go("assistant"); if (o.path) { S.pend = [o.path]; renderPend(); } if (o.text) { $("#q").value = o.text; autosize(); } $("#q").focus(); };
document.addEventListener("pointerdown", e => { // long-press to start selecting in file lists
  const el = e.target.closest('.view [data-a="file"]'); if (!el || S.tab !== "files" || !["list", "browse"].includes(S.files.mode)) return;
  const x0 = e.clientX, y0 = e.clientY;
  const t = setTimeout(() => { S.files.sel.add(el.dataset.p); tick(20); lpPath = el.dataset.p; render(); }, 480);
  const end = () => { clearTimeout(t); removeEventListener("pointerup", end); removeEventListener("pointercancel", end); removeEventListener("pointermove", mv); };
  const mv = ev => { if (Math.hypot(ev.clientX - x0, ev.clientY - y0) > 10) end(); };
  addEventListener("pointerup", end); addEventListener("pointercancel", end); addEventListener("pointermove", mv, { passive: true });
}, { passive: true });
let lpPath = null;
document.addEventListener("click", e => { if (lpPath) { const el = e.target.closest('[data-a="file"]'); lpPath = null; if (el) e.stopImmediatePropagation(); } }, true);

// ---------- start ----------
applyTheme(); renderDock(); render();
setInterval(() => { if (S.tab === "files" && S.files.mode === "home" && !sheetEl && J(N.stats(), {}).scanning) render(); }, 2500);

// ================================================================ browser preview (no Android)
function mockNative() {
  const P = {}; const now = Date.now();
  const files = [["Download/Electricity_Bill_Oct2026.pdf", "pdf", 240000], ["Download/Boarding_Pass_6E512.pdf", "pdf", 120000], ["DCIM/Camera/IMG_20261101_1012.jpg", "image", 3200000], ["Pictures/Screenshots/Screenshot_Uber.png", "image", 800000], ["Download/Atlas.apk", "apk", 22000000], ["Documents/Loan_Sanction_Letter.docx", "doc", 90000], ["Movies/Trip.mp4", "video", 900000000], ["WhatsApp/Media/WhatsApp Images/IMG-WA0001.jpg", "image", 210000]]
    .map(([p, k, s], i) => ({ p: "/storage/emulated/0/" + p, n: p.split("/").pop(), d: p.split("/").slice(0, -1).join("/"), k, s, m: now - i * 86400000 * 3, x: p.split(".").pop() }));
  return {
    prefs: () => JSON.stringify(P), setPref: (k, v) => P[k] = v, setBool: (k, v) => P[k] = v, setInt: (k, v) => P[k] = v, version: () => "preview",
    hasFiles: () => true, askFiles() { }, agentOn: () => false, openAccessibility() { }, openAppInfo() { }, apps: () => JSON.stringify([{ key: "uber", label: "Uber" }, { key: "swiggy", label: "Swiggy" }]), allApps: () => '["Amazon","Flipkart","Uber"]',
    setBars() { }, setCanGoBack() { }, haptic() { }, dynamicColors: () => null, voice(id) { atlasCb(id, false, "no voice"); },
    scan(id) { setTimeout(() => atlasCb(id, true, "ok"), 300); }, stats: () => JSON.stringify({ files: files.length, bytes: 1e9, kinds: { image: { n: 3, b: 4e6 }, pdf: { n: 2, b: 3e5 }, video: { n: 1, b: 9e8 }, apk: { n: 1, b: 2.2e7 }, doc: { n: 1, b: 9e4 } }, free: 180e9, cap: 512e9, analyzed: 0 }),
    search: (q, k) => { const it = files.filter(f => (!q || f.n.toLowerCase().includes(q.toLowerCase())) && (k === "any" || k === f.k || k === "todo" && (f.k === "image" || f.k === "pdf"))); return JSON.stringify({ total: it.length, bytes: it.reduce((a, b) => a + b.s, 0), items: it }); },
    listDir: p => JSON.stringify({ p: p || "/storage/emulated/0", rel: "Phone", up: "", dirs: [{ p: "/storage/emulated/0/Download", n: "Download", c: 3 }], files: files.slice(0, 2) }),
    info: p => JSON.stringify(files.find(f => f.p === p) || { missing: true }), root: () => "/storage/emulated/0", duplicates(id) { atlasCb(id, true, JSON.stringify({ groups: [], wasted: 0, count: 0 })); },
    move: () => '{"ok":true,"path":"x"}', rename: () => '{"ok":true,"path":"x"}', mkdir: () => '{"ok":true}', trash: () => '{"ok":true,"path":"x"}', putContent() { }, clearContent() { }, open() { }, share() { },
    aiReady: () => true, ai(req, id) { setTimeout(() => atlasCb(id, true, JSON.stringify({ say: "On it — opening Uber.", actions: [{ type: "phone_task", app: "uber", goal: "Book an Uber Go from current location to Nashik Road station" }, { type: "email", to: "manager@sbi.co.in", subject: "Leave on Monday", body: "Dear Sir,\n\nI request leave on Monday.\n\nRegards,\nVishwajeet" }] })), 600); },
    agentBusy: () => false, runTasks: () => "off", stopAgent() { }, agentLog: () => "[]", launch: () => false, email: () => "opened",
  };
}
