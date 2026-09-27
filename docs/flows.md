# AutoSentry Flow Structure — sourced from Appllama (free tier, screen names only)

Source: appllama.io/flows/{insights, progress-tracking, progress, tools, main-navigation}, pulled 2026-09-27. Screen images locked on free; screen names and order are real.

## 1. Insights → Diagnostics tab

| Source app | Real screen order | AutoSentry equivalent |
|---|---|---|
| Heartify (12) | Introduction → one screen per metric (Blood Pressure, Weight, Stress, Sleep, Heart Health, Low Heart Rate…) | Diagnostics Intro → one screen per system: Fuel/HPOP, Injectors, Turbo/Boost, Oil, Coolant, Electrical, Glow Plugs |
| Clover (3) | Expected Symptoms → Self Assessment Hub → Assessment Recommendations | Symptoms picker (hard start, smoke color, power loss) → Assessment → Recommended fix + parts |
| How We Feel (8) | Check-In Breakdown → Monthly Trend → Monthly Calendar → Top Tags → Top Emotions → Time of Day → Day of Week → Health Data Sync | Scan Breakdown → Monthly Trend → Scan Calendar → Top Fault Codes → Top Symptoms → Cold vs Warm start → Day of Week → OBD Sync |
| Human Design (3) | Articles → Guides → Guide List | Learn: 7.3L articles → repair guides → guide list |

**Build order:** Intro → per-system screens → symptom assessment → recommendations. Trend/calendar screens come after scan data exists.

## 2. Progress Tracking → Service History tab

| Source app | Real screen order | AutoSentry equivalent |
|---|---|---|
| STRNG (2) | Progress Empty → New Progress Entry | "No service logged" empty state → Log Service |
| Move With Us (4) | Tracking Chart → Measurements → Update Measurements → Entries | Mileage/interval chart → current readings → update readings → service log |
| Calo (5) | Weight Progress → Overview → Trends → Water & Exercise → Widgets | Oil-change progress → Overview → Trends → Fluids → Home-screen widget |
| WeightBuddy (3) | Progress — Weight → Overview → Metrics | Interval → Overview → Metrics |
| Fitia (4) | Overview → Stats → Calories Analytics → Weight Analytics | Overview → Stats → Fuel economy → Maintenance cost |

**Paid PDF report hook:** Service log entries + analytics = the content of the one-time service-history PDF.

## 3. Tools → Tools tab

| Source app | Real screen order | AutoSentry equivalent |
|---|---|---|
| Knitandnote (4) | Calculator → Calculator → Result → Ruler | Fuel-mix calc → Tire/gear ratio calc → Result → Torque spec reference |
| Invoice Fly (7) | Tools Hub → Import/Export → Scanner Intro → Image Picker → Empty states → Widget Setup | Tools Hub → Export data → VIN/receipt scanner intro → photo picker → empty states → widget setup |
| Acrobat (7) | More Tools Sheet → All → categories | Tools sheet → All → Diagnose / Maintain / Calculate / Reference |

## 4. Main Navigation

| Source app | Tabs |
|---|---|
| Expedia | Search · Trips · Inbox (empty state) · Explore · Account |
| Photo Math | Home · History · Settings |

**AutoSentry tabs:** Dashboard · Diagnostics · History · Deals · Account (Tools under Dashboard or Account).

## Required empty states (every source app ships them)

- No vehicle added
- Scanner not connected
- No scans yet
- No service logged
- No deals matching alerts
