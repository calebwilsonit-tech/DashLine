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
| Search along the route, with minutes added per stop | ⬜ Phase 2 |
| Open now / rating / price filters, sort | ⬜ Phase 2 |
| Chain's other branches, "More results", "Search this area" | ⬜ Phase 3 |
| Contacts' addresses in search | ⬜ Phase 3 |

## Places
| Feature | Status |
| --- | --- |
| Save as Home, Work or a named favorite ("Mom's house", by voice) | ✅ 0.2.0 |
| Recent places | ✅ 0.2.0 |
| Place card: open/closed, hours, phone (Call), rating, gas prices | ⬜ Phase 2 |
| Reviews, photos, menus, popular times, Street View | ➖ |
| Share place / Google Maps links from other apps | ⬜ Phase 3 |

## Routes
| Feature | Status |
| --- | --- |
| Google's route with live traffic and closures | ✅ |
| Up to four routes with time and traffic; pick one | ✅ 0.2.0 |
| Route line colored by traffic | ✅ 0.2.0 |
| Stops (add, reorder), round trip | ⬜ Phase 2 |
| Avoid tolls / highways / ferries; no-toll option | ⬜ Phase 2 |
| Depart at / arrive by | ⬜ Phase 3 |
| Step list before starting | ⬜ Phase 2 |
| Save a route | ⬜ Phase 3 |
| Closing-time warning at the destination | ⬜ Phase 3 |
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
| Posted speed limit | ⬜ Phase 2 |
| Overview / re-center, zoom, north-up or heading-up | ✅ 0.2.0 |
| Mute spoken turns | ✅ 0.2.0 |
| Arrival card with trip time and distance | ✅ 0.2.0 |
| Voice detail levels (everything / brief / highway only), street names on/off | ⬜ Phase 2 |
| Step list during the drive, look ahead | ⬜ Phase 2 |
| Pause a drive | ⬜ Phase 3 |
| Road-ahead bar, turn arrows on the route, cross-street callouts, road shields on the line | ⬜ Phase 3 |
| Speed cameras, red-light cameras, speeding alerts | ⬜ Phase 2 |
| Resume a drive after a restart | ⬜ Phase 3 |
| Gas stations only on the map while driving | ⬜ Phase 3 |

## Map
| Feature | Status |
| --- | --- |
| Open vector map, day/night (follows the theme) | ✅ |
| Your position | ✅ (heading cone ⬜) |
| Map styles (Bright, Positron, Fiord, Black, satellite) | ⬜ Phase 3 |
| Live traffic overlay while browsing | ⬜ Phase 2 |
| Place icons from Vela's open data | ⬜ Phase 3 |
| 3D buildings, tilt | ⬜ Phase 3 |
| Offline maps and routing | ⬜ Phase 3 |

## Settings, privacy
| Feature | Status |
| --- | --- |
| Units, voice speed | ⬜ Phase 3 |
| Google-free mode | ⬜ Phase 3 |
| Diagnostics, trip recording | ➖ |
