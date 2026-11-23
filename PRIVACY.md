# Dashline privacy

Dashline has no server and no account. It talks to these services directly from the phone:

| What | Goes to | When |
| --- | --- | --- |
| Map tiles | OpenFreeMap (tiles.openfreemap.org) | Whenever the map is on screen |
| Place search, routes, live traffic | Google, through Vela's engine (keyless web requests, no account) | When you search or navigate |
| Turn details, reverse geocoding | OpenStreetMap services (OSRM, Valhalla, Nominatim, Photon) | When you navigate |
| Vela's request templates | Vela's GitHub repository | When the map first starts |
| Assistant questions (optional) | Google's Gemini API, with your own key | Only if you add a key and use the built-in assistant |
| Update checks | This repository's GitHub releases | At most once a day |
| Car certificate | The aasdk project on GitHub | Once, during setup |
| Weather (approximate location) | Open-Meteo (api.open-meteo.com) | While the car is connected |

Your location goes to the map and routing services above only as part of a search or a route.
Messages, contacts, calls and the garage stay on the phone. The phone's own Google Assistant
works the way it always does, under Google's terms.
