# Changelog

RidePack follows [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

- **MAJOR**: incompatible changes or a milestone release (e.g. the ride pairing and offline maps release).
- **MINOR**: new features that stay compatible (e.g. online pairing, saved-place quick destination).
- **PATCH**: bug fixes and optimisations only.

`versionCode` in `app/build.gradle.kts` increases by 1 with every release. `versionName` holds the version above.

APK files are saved as `RidePack-v<versionName>.apk` in `Downloads\RidePack`.

## 3.11.0 (versionCode 52)
- During a ride the app now checks the map every 5 seconds and brings the camera back to your current location if it drifted away (for example after panning the map or looking at a saved place).

## 3.10.5 (versionCode 51)
- Setting a destination no longer leaves the map parked on the destination: the camera stays on your current location with the route and destination marker drawn ahead.
- The "Follow me" button now also appears while navigating, so you can snap back to your location after panning the map.

## 3.10.4 (versionCode 50)
- Fixed the internet rider link: the app now connects to the correct Realtime Database URL (asia-southeast1). Previously it used a default database URL that does not exist, so the internet link could not work.
- The link now reports a clear message instead of failing silently if the database cannot be reached.

## 3.10.3 (versionCode 49)
- Place search now looks near you first and only widens the radius (25 km, then 200 km, then 1000 km, then worldwide) when nothing matches, so a common name no longer jumps to a place thousands of kilometres away.
- Search results are sorted nearest-first, show the distance from you, and list several matches instead of one.
- Fixed settings (Night mode, font size/colour, brightness, auto-centre/rotate, 3D, offline) resetting after the app closed; they are now read from storage at first use.

## 3.10.2 (versionCode 48)
- ETA is now based on a smoothed, floored speed so it stays stable and realistic instead of spiking to large values when GPS speed fluctuates.

## 3.10.1 (versionCode 47)
- "Add rider by ID" now looks up the rider in the nearby list and pairs with them (joins their ride), instead of only tagging a name.

## 3.10.0 (versionCode 46)
- Nearby riders are now discovered automatically and listed in the Pairing tab; tap Pair to join a nearby ride instead of typing a code.
- The Search button on the map now opens the location search instead of the tab panel.

## 3.9.1 (versionCode 45)
- The vehicle registration number is no longer shown or shared with other riders; only the name is shared. It is still collected during setup and stored locally.

## 3.9.0 (versionCode 44)
- First-launch setup: asks for your name and vehicle registration number, stores them, and shares them with the ride group (shown next to each rider).

## 3.8.1 (versionCode 43)
- Added anonymous Firebase sign-in and tied the database node keys to the signed-in user, so the Realtime Database can use authentication-based security rules.

## 3.8.0 (versionCode 42)
- Replaced the internet rider link (ntfy.sh) with Firebase Realtime Database for a more reliable, always-on internet link. Riders, routes, and SOS are stored under separate paths so they never overwrite each other, and a rider who disconnects is removed automatically.

## 3.7.3 (versionCode 41)
- Landscape: the Recenter button sits in the bottom-right corner (no longer overlapping Search/Refresh), and is dark in night mode.
- Landscape: the Tabs / Hide tabs buttons are now round icons, matching the Search and Refresh buttons.

## 3.7.2 (versionCode 40)
- A Recenter button is now always shown at the bottom-right of the map; it re-centers on your location and re-enables follow.

## 3.7.1 (versionCode 39)
- Landscape: the navigation bottom bar is now a compact bar aligned to the bottom-left, instead of spanning the full width.

## 3.7.0 (versionCode 38)
- Stops now show on the map as lettered markers (A, B, C...) with a grey line joining them in order.

## 3.6.2 (versionCode 37)
- Rider tracking: added an impossible-jump filter (rejects fixes implying over 70 m/s) and a stale-fix check, alongside the accuracy filter, to stop the marker jumping.
- Heading rotation now only applies while moving faster than 2.5 m/s, so the map doesn't spin when stationary.

## 3.6.1 (versionCode 36)
- Dropping a pin (long-press or Drop pin) now asks whether to add the point to the stops list, instead of immediately setting the destination. This lets you build a multi-stop route by dropping points.
- Saved-place editing is now only in the Places tab.

## 3.6.0 (versionCode 35)
- On launch, the app asks whether to continue a previous ride instead of auto-joining it.
- The search bar is now semi-transparent, and tapping a search result centers the map on that place.
- Long-press on the map drops a pin (sets the destination) at that spot.

## 3.5.1 (versionCode 34)
- Sequential stop navigation: Set ride navigates to the first stop; on arrival, the app offers "Next: <stop>" until the last stop is reached.

## 3.5.0 (versionCode 33)
- Added a Set ride button (on the map search card and in the Ride tab). It starts navigating through the arranged stops, using the last stop as the destination.
- Removed the "+ Map center" and "+ My location" buttons from the stops section.

## 3.4.2 (versionCode 32)
- Fix: route position (distance-along-route and distance-off-route) now matches the route length exactly; the two used slightly different metres-per-degree values.
- Added unit tests for distance, bearing, route length, route position and turn detection (9 tests, all passing).
- Fix: the navigation bottom bar no longer clips its second line and no longer repeats the ETA; it now shows the ETA large with the remaining distance below.
- Fix: opening the tab panel from the navigation bar's Menu button (or the Search button) expands it, so the tabs are readable instead of a clipped strip.

## 3.4.1 (versionCode 31)
- Rotating between portrait and landscape no longer restarts the map, so it no longer jumps; the camera eases to the new layout.
- A Refresh route button appears on the right while a destination is set. It re-plans the route from your current position, through the stops.

## 3.4.0 (versionCode 30)
- The top card on the map is now a location search (replaces the Ready card). Each result has + Stop and Go. Stops show in order on the card.
- Routes pass through all stops in order to the destination.
- Location search removed from the Ride tab; the stops list there stays, with Up/Down to reorder.

## 3.3.1 (versionCode 29)
- The X on the navigation bar stops the ride, the same as Stop ride in the Ride tab, and clears the destination.

## 3.3.0 (versionCode 28)
- Gimbal-style follow: while following, the map's position, heading and tilt ease smoothly between GPS updates, so the view glides instead of jumping.

## 3.2.1 (versionCode 27)
- The navigation card stays at the top while navigating, so it no longer overlaps the bottom trip bar.

## 3.2.0 (versionCode 26)
- Full-screen navigation: the status bar and navigation bar are hidden while navigating. Swipe from the top or bottom edge to show them briefly.

## 3.1.1 (versionCode 25)
- After dropping a pin, the map returns to your position and follows you again.

## 3.1.0 (versionCode 24)
- Landscape navigation uses the same design as portrait: round buttons on the right, and the bottom trip bar with End, ETA, distance and the menu button.
- While navigating, the separate My Location and Follow me buttons are hidden; the recenter button on the right replaces them.

## 3.0.1 (versionCode 23)
- The map colour follows the Night mode setting: dark map when Night mode is on, light map when it's off. Navigation keeps the cyan route on the dark map.

## 3.0.0 (versionCode 22)
Major release: navigation screen redesigned to match common navigation apps.
- While navigating to a destination: dark map with a cyan route; teal instruction card at the top; bottom trip bar with End, ETA and remaining distance, and a menu button.
- Portrait: round buttons on the right (recenter, search). Tabs are hidden during navigation and open from the menu button.
- Landscape keeps the compact overlay and the hideable tabs panel.

## 2.0.1 (versionCode 21)
- GPS: fixes with poor accuracy (over 25 m) are ignored unless they're about as good as the current fix, or the current fix is over 10 seconds old. This stops the marker drifting.

## 2.0.0 (versionCode 20)
Major release. Summary of everything since 1.0.0:
- Pairing: nearby link (Bluetooth/Wi-Fi) plus internet link via ntfy.sh, with a link status indicator on the map and the Pairing tab.
- Stops: search custom places, add them as stops, reorder with Up/Down.
- Places: Go, Show and Edit (latitude/longitude) for Home, Work and custom places.
- Map: wider road-style route line; north-up while riding with no destination; landscape layout with compact overlay, speed in the navigation card, hideable tabs panel and a lower position marker.
- SOS: Food break, Fuel and Regroup, with a high-importance alert sound.
- Road route: second router fallback; no straight-line fallback.

## 1.3.13 (versionCode 19)
- Landscape: the position marker sits much lower on the map, showing more road ahead.

## 1.3.12 (versionCode 18)
- Landscape: the tabs panel starts hidden. Tabs (top-right) shows it and Hide tabs hides it.
- Landscape: tapping the map brings the Hide tabs button back for 20 seconds.
- Landscape: tighter padding on the navigation card and status line.

## 1.3.11 (versionCode 17)
- Landscape: the Hide tabs and Tabs buttons sit in the top-right corner, below the compass, so they no longer overlap the My Location button.

## 1.3.10 (versionCode 16)
- Landscape: the map centre is shifted so the position marker sits lower on the screen, showing more of the road ahead.
- Landscape: the Hide tabs button disappears 20 seconds after it appears. When the tabs are hidden, a Tabs button stays on the right edge.

## 1.3.9 (versionCode 15)
- SOS menu has a Regroup option.
- SOS alerts use a high-importance channel, so they play a sound and vibrate.

## 1.3.8 (versionCode 14)
- Landscape: speed and GPS accuracy are shown inside the navigation card.
- Speed updates more often: newer GPS fixes are accepted even when they are slightly less accurate.
- Landscape: the tab panel can be hidden with the Hide tabs button (top-right); Show tabs brings it back.

## 1.3.7 (versionCode 13)
- Landscape: speed and GPS accuracy show below the navigation card on the left, instead of inside the card.

## 1.3.6 (versionCode 12)
- Landscape: the navigation card, alert banners, and SOS button are smaller, and the overlay column is narrower, so less of the map is covered.

## 1.3.5 (versionCode 11)
- Out-of-range banner shortened to "Out of range: <name>".
- My Location button moved to the bottom-right of the map, above Follow me, so it no longer overlaps the SOS button or the destination card.

## 1.3.4 (versionCode 10)
- Landscape: the bottom panel (tabs) takes at most 40% of the screen height, so the map has more room. It can still be collapsed with the handle.

## 1.3.3 (versionCode 9)
- SOS button moved to the bottom-left of the map; the My Location button sits above it.

## 1.3.2 (versionCode 8)
- Map: routes are drawn as a wide blue road with a darker outline, and the width grows as you zoom in (similar to Google Maps). Applies to the planned route, the ride route, and the traveled part.

## 1.3.1 (versionCode 7)
- Landscape: the navigation card and alerts sit in a narrow top-left column instead of covering the map and bottom buttons.

## 1.3.0 (versionCode 6)
- Link indicator: shows "Linked: Internet", "Linked: Nearby (n)", or both, on the map during a ride and on the Pairing tab.

## 1.2.0 (versionCode 5)
- Internet pairing over ntfy.sh (free, no account). Riders join the same ride code over mobile data or Wi-Fi, in addition to the nearby Bluetooth/Wi-Fi link. Can be turned off in Settings.
- Road route fix: when the first router fails, a second router is tried. No more straight-line fallback drawn as a road; a message explains when no road route is available.
- SOS alerts are shown once even when received over both links.

## 1.1.0 (versionCode 4)
- Edit latitude and longitude for Home, Work, and custom saved places, from the Places tab (Edit on each row) or the Ride tab (Edit button next to Home/Work/Me).

## 1.0.0 (versionCode 3)
- First major release (previously 0.2).
- Ride pairing (Riders tab, Pairing sub-tab): join or create a ride code, persisted and auto-rejoined on launch.
- Stops: search custom places and add them as stops; reorder with Up/Down; arrange in route order.
- Places: Go button sets the destination from Home, Work, or any saved place.
- Map: higher default zoom; north-up while riding with no destination; route-up with a destination.
- Rider link: destination and route sync from the ride leader; discovery retries every ~30 s when no rider is linked.
- Optimisation: single route lookup per map update, binary search for route position, points-of-interest layer rebuilt only when it changes, unused code removed.
