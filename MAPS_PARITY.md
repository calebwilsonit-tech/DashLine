# Dashline maps vs Vela Maps

Goal: everything Vela Maps does that makes sense on a truck screen, built as Dashline's own UI
on Vela's engine (`DriveApp/vela/`). Source list: Vela's `FEATURES.md` (commit 09f9d84).

Status: ✅ done · 🟡 partly · ⬜ to do · ➖ not for the truck (phone-only or not useful while driving)

## Search
| Feature | Status |
| --- | --- |
| Search by name, category or address | ✅ |
| Addresses found in the right city (Google autocomplete with a wide span + OpenStreetMap Photon, exact house number first) | ✅ 0.2.0 |
| Typed search on the car screen | ✅ 0.2.0 |
| Suggestions as you type, saved and recent places first | ✅ 0.2.0 |
| Voice search ("Where to?") | ✅ |
| Category shortcuts (gas, food, coffee, parking, EV, grocery) | ✅ 0.2.0 |
| Commands: "take me home", "navigate to mom's house" | ✅ 0.2.0 |
| Results with rating, category, distance | ✅ 0.2.0 |
| Search along the route, with minutes added per stop (tap one to add it as a stop) | ✅ 0.3.0 |
| Open now filter; sort by best, nearest, top rated | ✅ 0.3.0 (price filter ⬜) |
| Chain's other locations, More results, Search this area | ✅ 0.6.0 |
| Contacts' addresses in search and voice ("navigate to Mom") | ✅ 0.5.0 |

## Places
| Feature | Status |
| --- | --- |
| Save as Home, Work or a named favorite ("Mom's house", by voice) | ✅ 0.2.0 |
| Recent places | ✅ 0.2.0 |
| Place card: open/closed, closing time, phone (Call), rating, gas prices | ✅ 0.3.0 |
| Reviews, photos, menus, popular times, Street View | ➖ |
| Google Maps links and geo: from other apps (Open with / Share) go to the car's Map | ✅ 0.5.0 |

## Routes
| Feature | Status |
| --- | --- |
| Google's route with live traffic and closures | ✅ |
| Up to four routes with time and traffic; pick one | ✅ 0.2.0 |
| Route line colored by traffic | ✅ 0.2.0 |
| Stops: add, remove, reorder (◀), reverse; round trip | ✅ 0.3.0–0.6.0 |
| Avoid tolls / highways (remembered) | ✅ 0.3.0 (ferries, no-toll comparison ⬜) |
| Depart at / arrive by | ➖ (a trip-planning screen, not for the driver's seat) |
| Step list before starting | ✅ 0.3.0 |
| Save a trip (destination and stops) | ✅ 0.5.0 |
| Warning when the destination closes before or soon after you arrive | ✅ 0.5.0 |
| Walking, bike, transit, GPX tracks | ➖ |

## Navigation
| Feature | Status |
| --- | --- |
| Spoken turn-by-turn on the truck's directions channel | ✅ (needs a drive test) |
| Turn card: arrow, distance, road, highway shield | ✅ 0.2.0 |
| Lane guidance | ✅ 0.2.0 |
| "Then" next turn when close | ✅ 0.2.0 |
| Time, distance and arrival time; destination name | ✅ 0.2.0 |
| Rerouting (with banner) | ✅ engine; banner 0.2.0 |
| Faster route offers (Take it / No) | ✅ 0.2.0 |
| Your speed | ✅ 0.2.0 |
| Posted speed limit (streamed from Vela's overlays with signal; from a downloaded region without), speeding alert | ✅ 0.4.0 / 0.6.0 |
| Overview / re-center, zoom, north-up or heading-up | ✅ 0.2.0 |
| Mute spoken turns | ✅ 0.2.0 |
| Arrival card with trip time and distance | ✅ 0.2.0 |
| Voice detail levels (everything / brief / highway exits only) | ✅ 0.3.0 |
| Step list during the drive (tap the turn card) | ✅ 0.3.0 |
| Pause a drive | ✅ 0.4.0 |
| Road-ahead bar (traffic and cameras); turn arrow on the route; road-number shields on the line | ✅ 0.5.0 / 0.6.0 (cross-street callouts ⬜) |
| Speed cameras (OpenStreetMap, on the map, banner and spoken) | ✅ 0.3.0 (red-light cameras ⬜) |
| Resume a drive after a restart | ✅ 0.4.0 |
| Gas stations only on the map while driving | ⬜ Next |

## Map
| Feature | Status |
| --- | --- |
| Open vector map, day/night (follows the theme) | ✅ |
| Your position with heading arrow | ✅ 0.4.0 |
| Map styles: auto, Liberty, Bright, Positron, Fiord, Dark, Satellite | ✅ 0.4.0 / 0.5.0 |
| Live traffic overlay while browsing (🚦 toggle) | ✅ 0.3.0 |
| Place icons from Vela's open data | ⬜ Next |
| 3D buildings, tilt | ⬜ Next |
| Offline regions (447, from Vela's catalog) for routing and speed limits; map tiles for no signal | ✅ 0.4.0 / 0.6.0 (offline place packs ⬜) |

## Settings, privacy
| Feature | Status |
| --- | --- |
| Units, voice speed | ⬜ Next |
| Google-free mode (phone settings) | ✅ 0.5.0 |
| Diagnostics, trip recording | ➖ |
