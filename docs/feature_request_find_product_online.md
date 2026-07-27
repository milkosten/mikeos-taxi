# Feature Request & Architecture — "Find Product Online" (the buy-it-for-me capability)

> **This document is shared by MikeProducts and MikeShopping.** The architecture is identical for both;
> your app's specific responsibility is in **§13 — Roles**. Read the whole thing, then build your half.
> This is a first-class, AI-native OS feature — not a nice-to-have. Treat it as a core mission.

---

## 0. TL;DR

The user says (or the agent infers) **"I need X."** MikeOS should then, on its own:
1. **Understand** the product (what exactly is X, which spec matters).
2. **Navigate the real web** with **chrome-pool** (headless Chrome fleet) to find where X is sold — price, stock, a **direct buy link**.
3. **Reason** over the scraped pages on the **free GPU** to pick the best option(s) and explain *how* to get it.
4. **Locate** the nearest physical store selling it, relative to the user's real location.
5. **Hand off** to the rest of the OS: a **buy link opened in MikeBrowser**, and **turn-by-turn directions in MikeMaps**, plus a spoken summary (TTS) and a notification.

That is the whole thesis of an AI-native OS: the user states an intent; the fleet of agents + shared infrastructure does the legwork across the web and the physical world. A dumb list of search results is a failure; a **decision + two tappable actions (Open / Directions)** is success.

## 1. Why this is the point of the OS (not optional)

A conventional phone makes the human do the work: open a browser, type into Fnac, compare tabs, copy an address into Maps. MikeOS inverts that — **the agent does the browsing and the comparison, and the human just approves.** MikeProducts and MikeShopping are the two agents whose entire domain *is* products and acquiring them, so this capability belongs to them before anything else. If these two apps can't answer *"where do I buy this and how do I get there,"* they aren't doing their job.

## 2. Canonical user story (this actually happened — via chrome-pool, today)

> Mike (at **Villefranche-sur-Mer**) needs a **self-powered USB hub** to run 2–3 phones off the MikeOS-computer.
> A human Claude session, using **chrome-pool**, searched retailers, found **Fnac blocked by DataDome**, fell
> back to **LDLC** (scraped cleanly), refined the query to real USB hubs, and returned:
>
> **INOVU Hub USB-A/C 3.0 → 4× USB-A 3.0 (avec alimentation externe) — €24.95 — En stock —**
> `https://www.ldlc.com/fiche/PB00544468.html`
>
> …with the nearest physical stores (Nice / CAP3000) as pickup options.

**That entire flow is what MikeProducts/MikeShopping must do autonomously.** This doc tells you how, using the exact infrastructure that made it work.

## 3. What "done" looks like — the result card

The app surfaces a **Result Card** (Compose, Material 3) with:
- **Product + verdict:** "INOVU 4-port USB 3.0 hub, external PSU — €24.95, in stock at LDLC. Best match for running 3 phones."
- **How-to line:** one sentence on *how to get it* (order online / pick up in store).
- **[ Open in MikeBrowser ]** button → opens the buy link in MikeBrowser (see §11).
- **[ Directions in MikeMaps ]** button → routes from the user's location to the nearest store (see §10).
- A **spoken summary** via `tts.osmike.com` (optional) and a **notification** (so it's visible even off-screen — the "invisible output" house-rule).
- Optionally a small **comparison list** (2–3 retailers, price, in-stock) under the verdict.

## 4. Architecture overview

```
   ┌─────────────────────────── ON DEVICE ───────────────────────────┐
   │  User intent ("I need a powered USB hub")  OR  proactive trigger │
   │        (MikeShopping detects a needed item on the heartbeat)     │
   │                              │                                   │
   │                     MikeAgent skill: find_where_to_buy           │
   │                              │  (X-API-KEY)                      │
   └──────────────────────────────┼──────────────────────────────────┘
                                   ▼
   ┌──────────────────────── CLOUD (Railway) ────────────────────────┐
   │  POST /api/find-product  { query, location, constraints }        │
   │   1. resolve product (MikeProducts identity + key spec)          │
   │   2. chrome-pool: search N scrapeable retailers → price/stock/url │
   │   3. free GPU (qwen3:8b): normalize + rank + pick + how-to        │
   │   4. Nominatim: geocode nearest physical store vs user location  │
   │   5. cache result;  return structured JSON                       │
   └──────────────────────────────┼──────────────────────────────────┘
                                   ▼
   ┌─────────────────────────── ON DEVICE ───────────────────────────┐
   │  Result Card  →  [Open in MikeBrowser]   [Directions in MikeMaps]│
   │        via hive domain events: browser.open / maps.route         │
   └──────────────────────────────────────────────────────────────────┘
```

**Golden rule:** the **cloud does the web scraping and GPU reasoning**; the **phone orchestrates and presents**; the **shared runtime (core) does the hand-off** to MikeBrowser/MikeMaps. Do NOT put chrome-pool credentials or scraping logic on the phone (see §6 security).

## 5. Infrastructure you MUST use (all free / self-hosted — cost = ZERO)

| Capability | Endpoint / creds | Use it for |
|---|---|---|
| **chrome-pool** (headless-Chrome fleet, now up to **50 sessions**) | `https://81.8.177.182:16700`, HTTP Basic `mikeos:uB49VXwMDy7R2JE0H7mI`, self-signed (verify=false). `GET /health` no-auth. | **Navigating & reading real retailer sites.** Session→navigate(acceptCookies)→snapshot/eval→close. Reference client: `mikeos-news-cloud/server/chrome.py`. |
| **Free GPU** (Ollama, Kittelfjäll) | `OLLAMA_GPU_URL=ollama://mikeos:uB49VXwMDy7R2JE0H7mI@81.8.177.182:11443`, model `qwen3:8b` (`think:false`, `format` for JSON schema), vision `qwen2.5vl:7b`. | Normalize scraped listings, rank, pick best, write the "how-to" + a spoken summary. Never a paid API. |
| **OSM Nominatim** (self-hosted) | `https://nominatim.osmike.com` (`/search`, `/reverse`) | Geocode a retailer store address → coordinates; reverse-geocode the user's fix → city; find the nearest store. |
| **Daemon location authority** | `GET https://127.0.0.1:7743/api/location` (loopback, auth-exempt) | The user's *from* location. NEVER run GPS; NEVER ask a peer. Core already exposes `location()`. |
| **Hive** (agent messaging) | daemon `/api/agents/register`, `/api/events`; core `MikeHive` + `onDomain(type){}` | Hand off to MikeBrowser (`browser.open`) and MikeMaps (`maps.route`); ask MikeProducts to resolve a product. |
| **tts.osmike.com** | `POST /api/tts {text,lang}` bearer (server-side) | Optional spoken summary of the result. |

### chrome-pool navigation recipe (the exact flow that worked today)
```
POST   /session                                  -> { id | sessionId }         (retry on 503; pool now 50)
POST   /session/{id}/navigate {url, acceptCookies:true}
GET    /session/{id}/snapshot                    -> { text: <accessibility tree> }   # readable page
POST   /session/{id}/eval {expression:"<JS>"}    -> { value: <string> }              # absolute hrefs, prices, stock
POST   /session/{id}/close
```
- **Reuse ONE session per retailer** (30-min TTL). `eval` returns `{"value":...}` — the param key is **`expression`** (not `code`/`script`).
- **DataDome / bot-walls are real:** **Fnac & Darty challenge headless Chrome** (you'll see `DataDome CAPTCHA` in the snapshot) → skip them or use their store-locator only. **LDLC, Boulanger, Amazon.fr scrape fine.** Detect a CAPTCHA/`RootWebArea` with no products and fall through to the next retailer. **Log which retailers you dropped** — never silently return "nothing found" when a bot-wall was the cause.
- Search-query quality matters: `"hub usb alimente"` returned power supplies on LDLC; `"hub usb externe secteur"` + a client-side filter (`/hub|dock/i` and not `/80PLUS|\d{3,4}W/`) returned the right products. **Have the GPU refine the query and filter the results.**

## 6. Where the logic lives + SECURITY (non-negotiable)

- **The CLOUD calls chrome-pool and the GPU — never the phone.** chrome-pool's Basic creds and the GPU creds are **shared infrastructure secrets**; per the house rule *"no key on a phone is ever admin / shared,"* they live **server-side only** (Railway env). The phone calls **your cloud** with its **X-API-KEY**; the cloud does the scraping.
- The phone's job is: build the request (intent + location), call the cloud, render the Result Card, and fire the hive hand-offs. Keep it thin.
- **Never-trust-200 / never-trust-scrape:** verify a scraped result actually has a price + a resolvable URL before returning it. A 200 from chrome-pool with a CAPTCHA page is *not* a result.

## 7. Cloud endpoint contract

`POST /api/find-product`  (X-API-KEY → user_id)
```jsonc
// request
{
  "query": "self-powered USB hub for 3 phones",   // natural language OR a product_id from MikeProducts
  "location": { "lat": 43.7032, "lon": 7.3230 },   // optional; else omit and app supplies from daemon
  "constraints": { "max_price_eur": 40, "must_have": ["external power","4 ports"] },
  "want_nearby": true
}
// response
{
  "product": { "name": "INOVU Hub USB-A/C 3.0 4x USB-A (alim. externe)", "key_specs": ["4 ports","USB 3.0","external PSU"] },
  "best": {
    "retailer": "LDLC",
    "price_eur": 24.95,
    "in_stock": true,
    "buy_url": "https://www.ldlc.com/fiche/PB00544468.html",
    "how_to": "Order online (delivered in ~2 days) or pick up at the Nice LDLC.",
    "confidence": 0.9
  },
  "alternatives": [ { "retailer":"Boulanger", "price_eur": 29.99, "in_stock": true, "buy_url":"..." } ],
  "nearest_store": {
    "label": "LDLC Nice", "address": "…", "lat": 43.70, "lon": 7.26,
    "distance_km": 6.2, "map_url": "geo:43.70,7.26?q=LDLC+Nice"
  },
  "spoken": "I found a 4-port powered USB hub at LDLC for 24.95 euros, in stock…",
  "dropped_retailers": ["Fnac (DataDome bot-wall)","Darty (DataDome)"],
  "cached": false
}
```
- **Cache** by `(normalized_query, city, day)` in a `product_finds` table (idempotent migration, `byte_len` not reserved words, ISO-8601 timestamps). Scraping is slow (~seconds) — serve cache-hits instantly.
- If nothing scrapes (all bot-walled / no stock), return `best: null` with `dropped_retailers` populated — **honest, not silent**.

## 8. The GPU reasoning step

After chrome-pool returns raw listings from each retailer, POST them to the GPU (`qwen3:8b`, `think:false`, `format` = the response schema) with a prompt like: *"From these scraped listings, pick the item that best matches '{query}' with constraints {…}. Prefer in-stock, external-PSU, closest price under budget. Return {product,best,alternatives,how_to,spoken}. Reject accessories/PSUs that only matched keywords."* The GPU does the messy human judgement (dedupe, reject false matches like the "80PLUS PSU with Hub"), writes the one-line how-to and the spoken summary. Reference GPU client: `mikeos-photos-cloud/server/analysis/vision.py` (`_ollama_chat`).

## 9. Store location + directions (MikeMaps hand-off)

1. Get the retailer's store list (from the retailer's store-locator via chrome-pool, or a known-stores table), geocode with **Nominatim** (`/search?q=<address>&format=json`).
2. Pick the store nearest the user's location (`GET /api/location` for *from*; Haversine to each store).
3. Return `nearest_store` with `lat/lon` + a `map_url` (`geo:` URI or an OSRM route).
4. The app fires a hive domain event **`maps.route`** `{ to_lat, to_lon, label }`; **MikeMaps** listens via `onDomain("maps.route"){…}` and renders turn-by-turn from the daemon's current location. (If MikeMaps isn't installed, fall back to a `geo:` Intent.)

## 10. Opening the buy link (MikeBrowser hand-off)

The **[Open in MikeBrowser]** button fires a hive domain event **`browser.open`** `{ url, title }`; **MikeBrowser** listens via `onDomain("browser.open"){…}` and opens the page in its content engine (MikeBrowser is the *only* app allowed a web-content engine). Fallback: a `VIEW` Intent to `url`. **The phone never scrapes in MikeBrowser** — MikeBrowser is for the *human* to view/complete the purchase; the *agent's* scraping is chrome-pool server-side.

## 11. MikeAgent harness / shared-core changes

Add to the **shared runtime** (`com.mikeos.core`) so the whole fleet gains it (then roll with `deploy-all-apps.sh`):
- A universal skill **`find_where_to_buy(query)`** → calls the app's cloud `POST /api/find-product`, returns the structured result to the brain and the UI.
- Universal hand-off capabilities every app can call:
  - **`browser_open(url,title)`** → emits hive `browser.open`.
  - **`maps_directions(lat,lon,label)`** → emits hive `maps.route`.
- These belong in core (like `location()`), so MikeShopping, MikeProducts, MikeRecipes, MikeLocal, etc. can all offer "buy it / take me there." Keep them **deterministic** (call them from the result card / heartbeat), not only via LLM skill-selection — the "capability the LLM never picks" lesson.

## 12. Proactive triggers (make it AI-native, not a search box)

Don't wait to be asked. On the heartbeat (deterministic, throttled):
- **MikeShopping:** when a shopping-list item is unchecked for N days, or a household staple is "low," proactively run `find_where_to_buy` and surface "Ready to buy: <item> — €X at <store>, [Open][Directions]."
- **MikeProducts:** when the user photographs/scans a product (or asks "what is this"), after identifying it, offer "Find where to buy" as a one-tap follow-up.
- Both should also handle a direct natural-language ask ("where can I buy a powered USB hub near me").

## 13. Roles — MikeProducts vs MikeShopping (and how they collaborate)

- **MikeProducts** = *product identity & knowledge*. Owns: resolving a fuzzy query into a concrete product + the **key spec that matters** ("USB hub → the deciding spec is *external power*"), normalizing noisy names, and (optionally) enriching with reviews/specs. It exposes a hive capability **`product.resolve {query} -> {name,key_specs,category}`** that MikeShopping calls first.
- **MikeShopping** = *acquisition*. Owns: the end-to-end **find_where_to_buy** flow, the cloud `POST /api/find-product`, the Result Card, and the MikeBrowser/MikeMaps hand-offs. It calls MikeProducts (`product.resolve`) to sharpen the query, then does the chrome-pool + GPU + Nominatim work in its cloud.
- **Collaboration:** MikeShopping → (hive) `product.resolve` → MikeProducts → returns product + key spec → MikeShopping searches retailers → returns the card. Either app may *initiate* (MikeProducts after an identification, MikeShopping after a list gap), but **MikeShopping owns the buy pipeline**; MikeProducts owns *what the thing is*.
- Whichever repo you are reading this in: build **your** half, and message the other via the hive. Do not duplicate the buy pipeline in both.

## 14. Step-by-step implementation plan

**Cloud (MikeShopping-cloud primarily; MikeProducts-cloud adds `product.resolve`):**
1. `server/chrome.py` — copy the reference from `mikeos-news-cloud/server/chrome.py` (session lifecycle, acceptCookies, snapshot, eval, retry-on-503, DataDome detection → fall through).
2. `server/find_product.py` — orchestrator: resolve product → scrape a ranked list of scrapeable retailers (LDLC, Boulanger, Amazon.fr; store-locator for Fnac) → GPU rank/pick → Nominatim nearest store → assemble the response schema.
3. `POST /api/find-product` (X-API-KEY), a `product_finds` cache table (idempotent migration), tolerant timestamps, parameterized SQL.
4. Env: `CHROME_POOL_URL`, `CHROME_POOL_BASIC`, `OLLAMA_GPU_URL`, `NOMINATIM_URL` — all **server-side**. Grab existing values from a service that has them (`railway variables -s mikeos-photos-cloud`).

**App (Kotlin/Compose):**
5. Cloud client method `findProduct(query, location, constraints)`.
6. **Result Card** composable with the two action buttons + notification + optional TTS.
7. Wire the buttons to the core `browser_open` / `maps_directions` capabilities.
8. Proactive heartbeat trigger (§12), throttled, deterministic.

**Core (shared runtime — one change, rolled fleet-wide):**
9. Add `find_where_to_buy`, `browser_open`, `maps_directions` universal capabilities + the `browser.open` / `maps.route` domain events; MikeBrowser & MikeMaps add `onDomain` handlers. Roll with `deploy-all-apps.sh "<reason>"`.

## 15. Acceptance criteria (how you know it works)

- Ask "where can I buy a powered USB hub near me" → within ~10 s a Result Card appears naming a **real, in-stock** product with a **real price** and a **working buy URL**, plus a nearest store with a distance.
- **[Open in MikeBrowser]** opens that exact URL in MikeBrowser. **[Directions in MikeMaps]** routes from your current daemon location to the store.
- Fnac being bot-walled does **not** break the flow — the response lists it under `dropped_retailers` and still returns an LDLC/Boulanger result.
- A repeat of the same query within the day is served from cache in <1 s.
- Nothing is logged as "success" unless a price + resolvable URL were actually captured (never-trust-200/scrape).

## 16. House rules to respect (from android_mikeos/CLAUDE.md)

- **Shared-infra creds (chrome-pool, GPU, TTS) stay server-side.** The phone holds only its X-API-KEY.
- **Never load a whole file into RAM;** cap any downloaded media ~30 MB.
- **ISO-8601 timestamps** to clouds; numeric fields numeric (no empty strings → 422 silent drops).
- **No reserved-keyword SQL columns; idempotent migrations; parameterized queries.**
- **Never trust HTTP 200 / a scrape alone** — verify real data (price + URL) before returning/persisting.
- **One shared location** (daemon `/api/location`); **native only** (no WebView except MikeBrowser's engine).
- Reason on the **free GPU** only; **cost = zero**.

---

*Companion reading: `mikeos-architecture/docs/APP-ANATOMY.md` (the agent contract), `FLEET-CHARTER.md`
(mission/vision per app), and the `## chrome-pool` + free-GPU sections of `android_mikeos/CLAUDE.md`.
Reference clients: `mikeos-news-cloud/server/chrome.py` (chrome-pool), `mikeos-photos-cloud/server/analysis/vision.py` (GPU).*
