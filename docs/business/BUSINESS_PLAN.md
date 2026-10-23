# AutoSentry: business plan (v1, 2026-10-22)

The goal is first revenue in 30 days without breaking the promise that built the product: the core app stays free, the data stays on the phone, and no link ever sits next to a trouble code.

Builds on `readme.md` (Business model), `docs/PRODUCT_ANSWERS_DRAFT.md` (Q22–24) and `docs/PROMO_PLAN.md`. Changes one call from Q24: we start taking money now, before the data can prove demand to suppliers. The sponsor offer below is priced for that (cheap, refundable, labeled).

---

## Where the money comes from, fastest first

| # | Stream | Price | Who pays | Ready? | First dollar |
|---|---|---|---|---|---|
| 1 | **Founding Member** pre-sale | $39 one time, first 100 | Owners | Landing page built; needs a Stripe Payment Link | Week 1 |
| 2 | **Launch partner** placements | $250 for 6 months, or 10% of sales on a code | 7.3L parts vendors | Deals tab built; one config line per signed deal | Weeks 2–4 |
| 3 | **Adapter affiliate** links | ~3–5% of a ~$80–140 adapter (check the live rate table) | Amazon | Built into the app and the site; needs an Associates tag | First sale |
| 4 | **Parts affiliate** links on jobs coming due | Same | Amazon | Built; same tag | As trucks come due |
| 5 | **Resale Report** | $19 per truck | Owners selling a truck | Not built (readme priority 9) | Public release |

**Not doing:** subscriptions, ads, selling data, paid features in the free core, or any offer on the Checks tab.

### The three owner levels

Each level includes everything in the one before it. The landing page shows this as three cards plus a comparison table.

| | Level 1: Free | Level 2: Resale Report | Level 3: Founding Member |
|---|---|---|---|
| **For** | Running your truck | Selling a truck | Owners backing the build |
| **Price** | $0 | $19 per truck | $39 once, first 100 |
| **Available** | Now | At public release | Now |
| Tracking, gauges, codes, schedule, service log, parts for jobs coming due | ✓ | ✓ | ✓ |
| Resale Report PDF | — | 1 truck | Every truck you own |
| Re-export until it sells | — | ✓ | ✓ |
| New builds before the public, direct line to the builder | — | — | ✓ |
| Vote on the next engine, name in the credits | — | — | ✓ |
| Refund | — | — | Full, any time before the report ships |

**The rule that keeps the levels clean:** nothing needed to keep the truck running ever moves above Level 1. Level 2 is about selling a truck, and Level 3 adds covering every truck plus access to the build. A new perk goes into exactly one level, and the comparison table on the site gets updated in the same change.

### 1. Founding Member: $39, first 100 owners

This is the only stream that can bring in real money this month, because it doesn't wait for users to come due on a service.

- **They get:** Resale Reports for every truck they ever own, new builds before the public, a vote on the next engine, and their name in the credits if they want it.
- **Why $39:** about two Resale Reports ($19 each), so it pays off on the second truck. Owners of these trucks often have two.
- **Why cap at 100:** scarcity is honest here. It's the beta group, and 100 people is about as many as one builder can talk to directly.
- **Refund promise:** full refund any time before the Resale Report ships. This is a pre-sale of something not built yet, so the refund promise is what makes it fair. It's printed on the pricing card.
- **Upside:** 100 × $39 = **$3,900**, about $3,760 after Stripe's 2.9% + 30¢.

**How founders get their reports when it ships:** keep the Stripe customer list. When the report ships, issue each founder an unlock code by email. The code system gets built with the report, not before (no point gating a feature that doesn't exist). Inside a Play build, the app must not link to the web checkout (see *Play rules* below). Entering a code bought elsewhere is fine; selling or steering to it from inside the app is not.

### 2. Launch partners: $250 for 6 months, one per job

The Deals tab shows the parts for a job once it reaches 80% of its interval, before the owner needs them. That's a high-intent buyer, and the readme's primary model.

- **Offer A, flat:** $250 for 6 months. They get first slot under one job (say, fuel filter), labeled "Sponsored", with their discount code, plus a mention on the landing page and in the beta thread.
- **Offer B, performance:** free placement. They give owners a code, and we take 10% of what the code sells, reported monthly by the vendor. Lower trust needed from the vendor; slower for us.
- **Ask for A, fall back to B.** Either one is a live partner by the public launch.
- **Rules (non-negotiable, in the pitch):** one partner per job; always labeled; never on a trouble code; the partner can't change intervals or due dates; we only take a partner whose part we'd recommend anyway.
- **Upside:** 3–5 partners × $250 = **$750–1,250**.

Adding a signed partner is one line in `app/src/main/java/com/autosentry/app/business/BusinessConfig.java` (`SPONSORED`).

### 3–4. Affiliate links

Every owner needs a supported adapter (OBDLink MX+ or LX), so the adapter link is the most reliable affiliate earner we have. Parts links follow the schedule.

- Revenue is small (a few dollars a sale). Its real value is proving the "parts at the moment of need" model with real click and purchase numbers before we pitch bigger suppliers.
- Amazon closes new Associates accounts that don't make **3 qualifying sales within 180 days**. Turn the tag on when the beta thread goes up, not before.
- List the app, its Play listing once it exists, and the landing page as your sites in Associates Central. Amazon's Operating Agreement covers links inside apps, so read that section before shipping a tagged build.
- No OBDLink or diesel-vendor affiliate programs turned up in a search, so those go through direct deals (stream 2). Ask OBDLink for an owner discount code; it's an easy yes for them.

### 5. Resale Report: $19 per truck

The moat product. A truck with a documented history sells faster and for more, and every report a buyer reads is an ad. Build order and gating:

- Ships with the public release (readme priority 9).
- **Play build:** sold through Google Play Billing (required for anything unlocked inside the app).
- **GitHub / F-Droid build:** same code-unlock path as founders.
- Price check: under half the price of a single CARFAX report, and it covers driveway work CARFAX never sees.

---

## The money setup only you can do

These need your identity, bank, or a signature, so they're yours. Each takes under an hour except the Play test window.

1. **Stripe.** Make an account and create a Payment Link for "AutoSentry Founding Member", $39, a cap of 100 (Payment Links can limit quantity sold), and collect email. Paste the link into `SHOP.foundingCheckoutUrl` in `site/index.html`. Copy for the product and receipt is in `OUTREACH_KIT.md`.
2. **Amazon Associates.** Sign up, then paste your tracking ID into `SHOP.amazonTag` (site) and `AMAZON_ASSOCIATES_TAG` (app). The disclosure lines appear on their own once a tag is set.
3. **A contact email** for partners: `SHOP.partnerEmail`. Until then the button opens a GitHub issue.
4. **Google Play developer account.** $25 one time. Start the 12-tester / 14-day closed test early, because it's the long pole for the Resale Report.
5. **Texas paperwork.** See *Legal* below.

Then merge `feat/business-side`. The Pages workflow publishes `site/` from `master`.

---

## 30-day plan

| Week | Do | Done when |
|---|---|---|
| 1 | Stripe link live on the site. Amazon tag in. Post the build thread (r/Powerstroke first) with the Founding Member link. | First founder paid |
| 1 | Email 10 vendors from the target list with the partner pitch. | 10 sent |
| 2 | Post in 2 forums and 2 Facebook groups. Reply to every comment the same day. Follow up with vendors who opened. | 25 founders; 2 partner calls |
| 3 | Pitch 3 YouTube channels (free adapter + founder access). Ship a beta build to founders. | First partner signed |
| 4 | Post an honest update: what founders found, what got fixed. Add signed partners to `SPONSORED` and ship. | 50 founders; $2,000+ in |

**Kill or change criteria:** fewer than 10 founders after two weeks of posting means the pitch or the price is wrong, not the market. Drop to $29 or reframe around the resale report before posting more.

---

## Numbers to watch

- **Founders sold** (target 100 by launch) and the conversion from post views to sales.
- **Deals tab taps per truck coming due.** Proves the supplier pitch. (Counting taps needs opt-in telemetry, which isn't built. Until then, use Amazon's click reports and vendor code counts.)
- **Adapter link sales.** The leading sign of new installs.
- **Refund requests.** More than 5% means the promise outran the product.

---

## Legal and policy checklist (Texas)

Not legal advice. Each line is worth one call to a Texas CPA or small-business attorney before money passes $1,000.

- **Entity.** Start as a sole proprietor. If you trade as "AutoSentry", file an assumed name (DBA) certificate with your county clerk. Form a Texas LLC through SOSDirect (about $300) before signing the first sponsor contract: an app that gives maintenance guidance should not put your personal assets on the line.
- **Sales tax.** Texas taxes some digital products and data-processing services. Get a free Texas sales and use tax permit from the Comptroller and turn on Stripe Tax, or have a CPA confirm the Founding Member and report aren't taxable.
- **Pre-sale.** Say what's not built yet, give a target date, and honor the refund promise without argument. That's what keeps a pre-sale on the right side of consumer protection law.
- **Affiliate disclosure (FTC).** Clear disclosure next to the links. Done in the app (Deals tab note plus per-link labels) and on the site (shows once a tag is set).
- **Play rules.** Physical parts links are fine. Anything unlocked inside the app goes through Play Billing, and the Play build never links to the web checkout.
- **Trademarks.** Use "Ford" and "Power Stroke" only to describe fit ("for the 7.3L Power Stroke"), never in the name, icon or ads. Run a USPTO search on "AutoSentry" before spending on the brand.
- **Privacy.** Deals links open in the browser. The app still has no internet permission, so the Play Data Safety answers don't change.

---

## Board check (cash now: Franklin leads, Rockefeller pushes back)

- **Franklin:** "Sell the founders this week. A pre-sale with a refund promise is the cheapest market research there is."
- **Rockefeller:** "$250 is cheap. The chokepoint is the moment a fuel filter comes due on a truck, and we own it. Price it up once we can show the count." *Accepted: re-price partners at the public launch with real numbers.*
- **Marshall:** "A pre-sale for an unbuilt feature is a promise in writing. Print the refund terms, keep the customer list, and form the LLC before a vendor contract." *In the checklist.*
- **Girard:** "The founder who gets a personal email after buying brings two friends. The one who gets a receipt brings nobody." *The thank-you email in the kit is written to be sent by hand.*
- **Greene:** "Cap it at 100 and show the count. Scarcity you can see sells; scarcity you claim doesn't." *`SHOP.foundingSpotsLeft` shows the real count; update it as you sell, and never fake it.*
