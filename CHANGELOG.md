# Changelog

## Unreleased

- **Chinese.** House Control now speaks Simplified Chinese: the native screens, the widgets and
  the bundled web UI (the 3D home, the panel and the floor plan editor). The app follows the
  phone's language, and on Android 13 and later it is listed in Settings → Apps → House Control
  → Language. English is unchanged.

## 1.1.0

- **Homes on several floors.** Floors with their own names and ceiling heights; the 3D model shows one floor at a
  time, standing on the ones under it, with buttons under it to switch. Picking a room or a device goes to its
  floor. Stairs as furniture, and a new **House** demo on two floors (Settings and **New home**).
- **A better floor plan editor:** floor tabs over the plan; a new floor copies the rooms of the one below or starts
  as one big room, and the floor below shows faintly underneath with its stairs. Resize a room from any corner or
  side, with edges snapping to the rooms around it; doors and windows slide along the walls and jump to the
  nearest one, and their ends drag to make them wider; **Split in two**, **Duplicate** and **Turn**; **Add** has
  windows, stairs and floors.
- The Home widget draws the floor with the most lamps on; the Devices screen names each room's floor.
- **Fixed:** the editor did not fill the screen in newer Android System WebView versions.

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
