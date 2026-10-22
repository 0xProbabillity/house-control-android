# Changelog

## 1.0.0 — 2026-10-22

The first release of House Control for Android.

- **Your home in 3D** on Android phones and tablets: the floor plan, its lamps, air conditioners and scenes, with
  the same renderer and panel as House Control on the Mac and iPhone. Tablets and phones on their side get the
  model beside the panel.
- **Set up entirely on the phone**: start a new home (one room, or the Studio or Apartment layout), draw it in the
  touch editor, import or export a plan file.
- **Real devices**: Smart Life / Tuya through a QR sign-in (lights, plugs, Wi-Fi and infrared air conditioners),
  Home Assistant (found on the Wi-Fi, live updates over its WebSocket) and Philips Hue (found on the Wi-Fi, live
  updates from the bridge's event stream). Link them in **Settings → Devices** or in the editor.
- **Sync with House Control on a Mac** (optional): pair by scanning the Mac's code; the plan, device links and the
  Home Assistant and Hue connections stay the same on both, over TLS with a pre-shared key.
- **Widgets** (Home, Scenes, Lights), **app shortcuts** for scenes and lamps, and an **All lights** Quick Settings
  tile, all switching the devices without opening the app.
- Secrets encrypted with an Android Keystore key and kept out of backups. No account, no analytics.
