# Changelog

## 1.3.1

- **Smart Life in the Google Play edition too.** Sign in with the Smart Life app's QR code in **Settings → Smart Life**;
  your Smart Life lamps, plugs and air conditioners link to the plan as in the GitHub edition.

## 1.3.0

- **On/off scenes.** Tick **On/off button** on a scene in the editor and it becomes a room button: a tap turns its
  lights on, the next tap (while any of them is on) turns them off. It shows as on while its lights are lit, in the
  panel and the widgets.
- **Earlier homes.** Before a file, the editor or a paired Mac replaces the home, the one there is kept.
  **Settings → Earlier homes** brings one back, here and on the paired Mac.
- **Learned IR lights** (a Smart Life DIY remote key) switch through their Smart Life ON and OFF Tap-to-Run scenes.
- The editor says how to turn a door or a window: drag it onto a wall that runs the other way.
- **Two editions:** the APK on GitHub (with Smart Life, as before) and Google Play (`com.oxprobabillity.housecontrol`,
  Home Assistant and Hue, no Smart Life).

## 1.2.0

- **Turn the home all the way round** with one finger, and tilt it from nearly level with the floor to straight down.
  The outer walls on your side sink out of the way as you go, whichever side that is.
- **Two fingers move the home** up, down, left and right, and pinch to zoom, closer than before. What is under your
  fingers stays under them.
- **A button puts the view back.** It shows beside the model only while the view has been changed.
- Your view stays where you left it: a room is framed from your angle, and leaving a room or a device returns to it.
- The shadows redraw when the model stops moving, so turning it does not stutter.

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
