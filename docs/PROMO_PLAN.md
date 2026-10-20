# AutoSentry — Promotional Plan (draft v1, 2026-09-22)

## Positioning

**One line:** Plug it in once, never think about it again. AutoSentry tracks your truck's health, trips, and maintenance automatically, and builds a maintenance record backed by photos that a buyer can actually trust.

**Beachhead:** 7.3L Power Stroke owners (1999–2003 Super Duty / Excursion). It's a big, loyal, DIY-heavy community that already buys OBD gear and loves keeping old trucks alive. Win this niche before going wide.

**Three hooks, in priority order:**
1. **"CarFax misses what you did in your driveway."** A photo-verified DIY maintenance history, tied to odometer readings, that you can show a buyer. It speaks to resale value, the hook reaches people who don't care about diagnostics, and no competitor has it.
2. **Zero habit change.** Starts tracking at key-on and stops at shutdown with no app to open. This is the money shot for a demo video.
3. **Built for the 7.3.** It watches engine oil temp (not a generic coolant assumption), has a 7.3-specific DTC dictionary, handles J1850 PWM, and uses the real Ford severe/normal intervals. It also says so when the truck doesn't return a value instead of guessing.

**How it differs from what owners already use:**

| Tool | What it does | What AutoSentry adds |
|---|---|---|
| FORScan | Deep Ford diagnostics, manual sessions | Runs on its own; maintenance and trip history |
| Torque Pro / Car Scanner | Generic live gauges | 7.3-aware; maintenance engine; hands-off |
| Fuelly / Drivvo | Manual maintenance/fuel logs | Automatic mileage, oil life, and evidence photos |

## Phase 0: Launch blockers (before any promotion)

- [ ] **Fix `readme.md`.** Unresolved `<<<<<<< / >>>>>>>` merge markers are committed on master. It's the first thing a forum visitor clicking a GitHub link would see.
- [ ] **Release signing.** The APK is currently debug-signed. Create an upload key and use Play App Signing.
- [ ] **Distribution channel.** Google Play is the main one. F-Droid is a strong second because the app has no Play Services dependency, and FOSS/privacy-minded gearheads care.
- [ ] **Play closed-testing requirement.** New personal developer accounts need 12+ testers opted in for 14 continuous days before production access. Plan the beta around this (it becomes Phase 1).
- [ ] **Tested adapter list.** Many cheap ELM327 clones handle J1850 PWM badly or not at all. Test and publish a "known good" list (e.g. OBDLink LX/MX+, a Vgate model if it passes). This prevents most one-star reviews.
- [ ] **Privacy policy.** Play requires one. "All data stays on your device, no account" is also a selling point, so lead with it.
- [ ] **Trademark hygiene.** "Power Stroke" and "Ford" are Ford trademarks. Use them only descriptively ("for the 7.3L Power Stroke"), never in the app name or icon.
- [ ] **Disclaimer.** State that it's not a substitute for professional inspection (the README already says this; surface it in-app and in the listing).
- [ ] **Store assets.** Six to eight screenshots from the real truck, a feature graphic, and a 30–60 sec video: key turns → app wakes by itself → oil life ticks → service reminder.

## Phase 1: Seeded beta (weeks 1–4)

- **Recruit 20–40 testers** from the 7.3 community. Post as a real owner-builder, not as an ad:
  - r/Powerstroke, r/Diesel, r/f250, r/OBD2
  - Powerstrokenation, Ford-Trucks.com (7.3 section), TheDieselStop, OilBurners
  - Facebook groups: "7.3 Powerstroke Owners" and similar
- **Post format:** a build thread. "I got tired of X, so I built Y for my 2000 F-250." Include screenshots, the video, and an honest list of what's rough.
- **Collect:** adapter model, truck year/trans, what broke, and what they'd pay. Use a simple Google Form.
- **Success gate:** 12+ testers for 14 days (Play requirement), zero crash-on-connect reports, and the adapter list validated on at least 3 models.

## Phase 2: Public launch (weeks 5–8)

- **Play production release** plus an F-Droid submission.
- **Launch posts:** an update to the forum build threads ("it's live, thanks to the testers") beats fresh spam. Credit beta testers by handle.
- **YouTube:** pitch 2–3 mid-size 7.3 / diesel DIY channels. Offer them a free adapter plus early access, and let them film it on their own truck. One real demo is worth more than any ad.
- **The "seller's report" angle.** Add a one-tap PDF export of the maintenance history with photos, if it isn't already there. Every truck sold with an AutoSentry report is an ad in front of a buyer.

## Phase 3: Retention and referral (ongoing)

- **Service reminders are the retention loop.** Make them accurate and not naggy, because a bad reminder gets the app uninstalled.
- **A monthly "truck health" notification** (miles, MPG trend, next service) keeps the app remembered without spam.
- **Ask for a review at a good moment:** right after a logged service or a clean trip summary, never after an error.
- **Referral via the report:** the exported PDF footer says "Maintenance record generated by AutoSentry" with a link.

## Phase 4: Expand the beachhead

Each new vehicle profile opens a whole new community with the same playbook:
6.0L / 6.4L / 6.7L Power Stroke → 5.9/6.7 Cummins → Duramax. Prioritize by beta-tester demand, and only ship a profile once it's tested on a real truck. The "honest data" promise is the brand.

## Metrics to watch

- Installs → successful first adapter connection (the real activation metric)
- Trucks with ≥1 automatic trip in their first week
- Maintenance records logged per active user
- 30-day retention
- Review rating and the share of complaints that are adapter-related

## Open decisions

1. **Business model:** free, one-time paid, or free + paid pro (e.g. PDF seller report, multiple vehicles)?
2. **Budget:** $0 organic, or some spend on adapters for YouTubers or small ads?
3. **Identity:** post as yourself (the owner-builder story) or under the AutoSentry brand only?
