# MikeTaxi — Onboarding & Gap Analysis vs Uber & Bolt

*Strategy doc · 2026-07-28. Visual version was delivered as a Claude artifact; this is the durable copy.*

## TL;DR

MikeTaxi has a strong **skeleton** (real map, live booking, the 95/5 fare, driver lifecycle) but no
**muscle**: nobody is verified and **no money moves**. Rough maturity — rider ~45%, driver ~25%,
money/trust ~10%. The launch-blocking work is **identity + compliance + payments**, not more UI polish.

**Two structural decisions taken here:**
1. **Drivers are independent contractors paid via automated marketplace payouts**, *not* salaried. Salary
   breaks the "keep 95%" pitch, doesn't scale, and is brutal under French employment law.
2. **Enforce the France VTC licence + mandatory dashcam** — turn regulation into the brand moat (the
   fully-licensed, on-the-record fair alternative).

---

## Gap analysis (MikeTaxi today vs Uber & Bolt)

Legend: ✅ have · 🟡 partial/stub · ❌ missing. Priority: **P0** launch-blocker · **P1** fast-follow · **P2** later.

### Rider experience
| Capability | Uber | Bolt | MikeTaxi today | Priority |
|---|---|---|---|---|
| Map, live ETA, live driver tracking | ✅ | ✅ | ✅ (our tiles) | done |
| Upfront fare + transparent breakdown | ✅ | ✅ | ✅ (95/5 shown) | done |
| In-app payment (card / Apple-Google Pay) | ✅ | ✅ | ❌ no charge happens | **P0** |
| Wallet / stored balance & credits | ✅ Uber Cash | ✅ Bolt Balance | ❌ | P1 |
| Receipts & itemised history | ✅ | ✅ | 🟡 list only | P1 |
| Saved places (Home/Work) | ✅ | ✅ | ❌ | P1 |
| Scheduled / advance booking | ✅ 30d | ✅ | 🟡 API only, no UI | P1 |
| Ratings & tipping | ✅ | ✅ | ❌ | P1 |
| Safety: share trip · SOS · trip check | ✅ | ✅ | ❌ | P1 |
| Ride classes · split fare · business profile | ✅ | 🟡 | ❌ | P2 |

### Driver experience
| Capability | Uber | Bolt | MikeTaxi today | Priority |
|---|---|---|---|---|
| Go online/offline · accept · lifecycle | ✅ | ✅ | ✅ | done |
| Document onboarding (licence, ID, insurance) | ✅ | ✅ | ❌ self-declared | **P0** |
| Identity / selfie liveness verification | ✅ Regula | ✅ Veriff | ❌ | **P0** |
| Background / criminal + driving-record check | ✅ | ✅ | ❌ | **P0** |
| Payout account + bank/KYC (get paid) | ✅ | ✅ | ❌ | **P0** |
| Earnings statements · instant cashout · tax | ✅ Instant Pay | 🟡 weekly | 🟡 sum only | P1 |
| Turn-by-turn navigation | ✅ | ✅ | ❌ (OSRM ready) | P1 |
| Demand heatmap · incentives · rewards tier | ✅ Uber Pro | ✅ | ❌ | P2 |
| **Mandatory dashcam → street imagery** | ❌ | ❌ | 🟡 enforced flag — **our edge** | P1 |

### Trust, safety & compliance
| Capability | Uber/Bolt | MikeTaxi today | Priority |
|---|---|---|---|
| Rider identity (verified email) | ✅ | ✅ MikeOS OAuth | done |
| Driver KYC + right-to-work + local licence | ✅ | ❌ | **P0** |
| France VTC "carte professionnelle" check | ✅ | ❌ | **P0** |
| Insurance validity + expiry re-checks | ✅ | ❌ | **P0** |
| GDPR-grade document storage & retention | ✅ | ❌ | **P0** |
| PSD2 / SCA compliant card payments | ✅ | ❌ | **P0** |

---

## Driver onboarding — a real, staged funnel

Replace today's single form + checkbox with a **staged, resumable, gated** funnel (mirrors Uber/Bolt).
A driver cannot go online until every *blocking* stage passes.

1. **Account & profile** *(soft gate)* — already MikeOS-signed-in; capture legal name, phone, city, selfie photo.
2. **Identity** *(blocking)* — passport / EU ID scan + **selfie liveness** matched to the document.
3. **Right to drive** *(blocking)* — driving licence (≥1–2 yrs), **France VTC card**, vehicle registration
   (carte grise), valid insurance certificate.
4. **Background check** *(blocking)* — criminal + driving record via a vetting partner; auto re-run on schedule.
5. **Payouts & dashcam** *(blocking)* — connect a payout account (IBAN + KYB), confirm the mandatory dashcam is live.

### Document checklist (France launch)
- **Government ID** — passport or EU/EEA ID card *(blocking)*
- **Driving licence** — category B, held ≥ 1–2 years *(blocking)*
- **VTC carte professionnelle** — the French private-hire licence, renewed every 5 yrs *(blocking)*
- **Vehicle registration** (carte grise), VTC-eligible *(blocking)*
- **Professional insurance** certificate, with expiry tracking *(blocking)*
- **Proof of address** — utility bill / attestation *(soft)*
- **Selfie** — liveness + periodic re-auth *(soft)*
- **Dashcam active** — already enforced *(our differentiator)*

### Verification stack — buy, don't build
- **ID + liveness:** a KYC vendor (Veriff / Onfido / Regula-class) via a hosted flow — AI document read + face match.
- **Background checks:** a European vetting partner; store only pass/fail + reference, never the raw report.
- **Document store:** encrypted, access-logged, GDPR retention + auto-expiry re-request. *Not* the zero-knowledge
  Vault (compliance must be able to review).
- **State machine:** per-document status `pending → in_review → approved / rejected → expired`, driving the
  "can this driver go online?" gate on every heartbeat.

---

## Client onboarding, wallet & payments

Riders already sign in with MikeOS (light onboarding). The work is **money + safety**.

**First-run (post-login):** add a payment method (card via PSP; Apple/Google Pay) · verify phone (SMS) ·
saved places (Home/Work) · optional emergency contact.

**MikeWallet & money features:** stored balance & credits ("MikeTaxi Balance", à la Uber Cash / Bolt Balance) ·
cards + wallets + cash-trip option (still 5% to us) · per-ride receipts with the 95/5 line itemised ·
history with rebook / receipt / tip / report-an-issue · **tipping (100% to the driver, on top of 95%)**.

---

## Decision — how drivers get paid: **payouts, not salary**

| | 🅰 Salary (employees) | 🅱 Marketplace payouts ✓ |
|---|---|---|
| Model | Employees, fixed wage + charges | Independent (France VTC = auto-entrepreneur) |
| Cost/risk | Payroll, employer tax, idle-time risk | No wage/idle risk; scales |
| The pitch | **Breaks** "keep 95% of the fare" | **Is** the product — 95/5 made real |
| Scale | Doesn't scale to 1000s of drivers | Scales |

**Recommendation:** independent contractors + automated payouts via a licensed marketplace PSP
(Stripe Connect Express, Adyen for Platforms, or grow **mikeos-pay** in-house). Per-ride money flow:
**charge rider → platform balance → 95% to driver's connected account, 5% fee retained → weekly payout or
instant cashout.** Drivers manage tax as auto-entrepreneurs; we furnish annual earnings statements. Salaried
ops fleet can come later, but the platform must be **payout-native from day one**.

---

## Build order

- **P0 (launch-blocker) — make it lawful & payable:** driver KYC + document funnel + VTC/insurance gating ·
  background check · payout account (Connect/KYB) · rider payment method + real charge on ride-complete ·
  GDPR document store · SCA. *Without these there is no legal, paid platform.*
- **P1 (fast-follow) — feel like the incumbents:** MikeWallet + credits · receipts · saved places ·
  scheduled-ride UI · ratings + tipping · driver statements + instant cashout · turn-by-turn (OSRM) ·
  share-trip / SOS · dashcam→MikeMaps ingest.
- **P2 (differentiate):** ride classes & split fare · business profiles · driver rewards tier & demand heatmap ·
  referrals · "fully-licensed + dashcam on-the-record" brand campaign.

## MikeOS advantage — what we don't have to build
- **Identity** — account.osmike.com OAuth (rider + driver login, done).
- **mikeos-pay** — the payment service to grow into wallet + payouts.
- **Own map stack** — tiles, Nominatim, OSRM (nav) → ~€0/ride, the 5% enabler.
- **Dashcam → MikeMaps** — a safety + data moat Uber/Bolt lack.

**Must add / integrate:** KYC/liveness vendor + background-check partner · card acquiring + SCA (PSP) +
marketplace payouts/KYB · compliance document store with retention/expiry jobs · safety layer (share-trip, SOS).

---

## Sources
Uber vehicle/driver requirements & France VTC — uber.com/fr, way-partner.com (VTC 2025); background checks —
help.uber.com. Bolt sign-up documents & verification — bolt.eu driver docs, bolt.eu FR requirements,
Veriff × Bolt (einpresswire). Uber identity/liveness — Regula case study. Rider features — addevice, Uber Cash,
Uber cash trips. Payouts & commission (Instant Pay, weekly, 10–25%) — fleeto, appscrip. "MikeTaxi today" column
= this repo's current build.
