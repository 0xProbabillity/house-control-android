// Thin bridge to the native shell (the macOS menu bar app, the iPhone app or the Android app).
// In a plain browser every call is a no-op, so the same bundle runs under `vite dev` too.

// WebKit hands the page a message handler; Android's WebView a Java object that takes JSON text.
const android = window.HouseAndroid;
const handler = window.webkit?.messageHandlers?.house
  || (android && { postMessage: (message) => android.postMessage(JSON.stringify(message)) });

export const isNative = Boolean(handler);

// 'ios' inside the iPhone app, 'android' inside the Android app (or with ?platform=… in a
// browser, to work on that layout).
export const platform = window.__HOUSE_BOOT__?.platform
  || new URLSearchParams(location.search).get('platform') || (android ? 'android' : handler ? 'macos' : 'web');
export const isAndroid = platform === 'android';
// Both phones share one layout: the panel full screen, made for a thumb.
export const isPhone = platform === 'ios' || isAndroid;

export function post(type, payload = {}) {
  handler?.postMessage({ type, ...payload });
}

// A tap you can feel, on a phone only: 'tick' for a step of a slider, 'light' /
// 'medium' for a switch or a scene, 'error' when a device refuses a change.
export function haptic(kind = 'light') {
  if (isPhone) post('haptic', { kind });
}

// Native -> JS entry points are attached here by main.js.
export const incoming = (window.house = window.house || {});

// Request/response on top of the one-way message channel.
let nextId = 1;
const pending = new Map();

export function call(type, payload = {}) {
  if (!handler) return Promise.reject(new Error('Only available inside the app'));
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    handler.postMessage({ type, callId: id, ...payload });
  });
}

incoming.reply = (id, result) => {
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  if (result && result.error) p.reject(Object.assign(new Error(result.error), { detail: result }));
  else p.resolve(result);
};
