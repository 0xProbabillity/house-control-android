# Web UI

House Control's web UI — the Three.js scene, the panel and the floor plan editor — from
[House Control](https://github.com/0xProbabillity/house-control) at the revision in `../UPSTREAM-REVISION`, with
what Android needs on top:

- `src/bridge.js` talks to the app through `window.HouseAndroid` (a WebView JavaScript interface) and treats
  `platform: 'android'` as a phone.
- `src/main.js` and `src/editor/editor.js` answer Android's back button (`window.house.back()`).
- `src/style.css` adds the side safe areas, the tablet layout and the layout on its side.
- `vite.config.js` targets Chrome 100 as well as Safari 17.

On top of that revision it carries upstream's homes on several floors and the reworked floor plan editor
(upstream commit 85a440b), with handles kept clear of Android's back gesture.

The Gradle build runs `npm ci` and `npm run build` here and packs `dist/` into the app.
