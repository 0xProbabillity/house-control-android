// A small translation layer the Android app adds on top of House Control's web UI.
//
// The upstream web UI writes its labels as plain English literals. Rather than rewriting
// every one of them, `h()` (ui/dom.js) passes text nodes and the readable attributes
// through `t()`, which looks the English text up in a dictionary. The English text is
// the key, so a missing entry simply shows the original words: adding a language can
// never break the English UI, and upstream can keep editing its labels as before.
//
// Android tells the page which language the app is running in through
// window.__HOUSE_BOOT__.locale; in a browser, navigator.language is used instead.

import { ZH_CN } from './locales/zh-CN.js';

const DICTIONARIES = {
  'zh-CN': ZH_CN,
};

/**
 * Simplified Chinese is the only extra language shipped so far. Only the regions whose
 * Android resources also resolve to values-zh-rCN take that path, so the native screens
 * and the web UI never disagree (a zh-TW device, for instance, stays in English).
 */
function resolveLocale() {
  const boot = typeof window === 'undefined' ? null : window.__HOUSE_BOOT__;
  const tag = String(boot?.locale || (typeof navigator !== 'undefined' && navigator.language) || 'en');
  const lower = tag.toLowerCase().replace(/_/g, '-');
  return /^zh(?:-hans|-cn|-sg)(?:-|$)/.test(lower) ? 'zh-CN' : 'en';
}

export const locale = resolveLocale();

const dictionary = DICTIONARIES[locale] || {};

/**
 * Translate a piece of UI text. Anything not in the dictionary is returned unchanged,
 * including numbers, device names and other user content.
 */
export function t(text) {
  const key = String(text);
  return Object.prototype.hasOwnProperty.call(dictionary, key) ? dictionary[key] : key;
}

/**
 * Translate a phrase with {0}, {1}, … placeholders. English needs no dictionary entry:
 * the placeholders are simply filled in, so the original wording is kept exactly.
 */
export function tf(text, ...args) {
  return args.reduce((phrase, value, i) => phrase.replaceAll(`{${i}}`, String(value)), t(String(text)));
}

/** Keep the document's language in step with the app for accessibility and font choice. */
export function applyDocumentLanguage() {
  if (typeof document !== 'undefined') document.documentElement.lang = locale === 'en' ? 'en' : locale;
}
