# CleanStreet

A civic complaint management platform that automatically routes complaints to configured municipal offices based on geographic location. It is a portfolio project and is **not** connected to any government or municipal system.

## Problem
Illegal dumping and sanitation issues go unreported or unresolved because citizens do not know who to tell and cannot see progress. CleanStreet gives citizens one place to report with a photo and GPS, and gives offices and crews a workflow to act and prove the work.

## Features
- Citizen registration with **real WhatsApp OTP via Wakit** (with Twilio SMS and offline dev fallback) plus resend cooldown, max attempts and provider-side expiry handling
- Report with photo, GPS, category and description; complaint number like `CS-2026-000123`
- **Location-based routing**: Haversine distance to the nearest active office whose service radius covers the point; "No municipal office configured for this area" otherwise
- Workflow with enforced transitions and role checks, permanent status history with remarks and photos
- Mandatory after-cleaning photo before work can be completed
- Priority and configurable SLA due dates per category
- Public tracking by complaint number (privacy-safe: no reporter details, no public evidence photos, location rounded to ~100 m)
- Responsive UI, OpenStreetMap links

## Roles
CITIZEN, OFFICER (own office only), CREW (assigned complaints only), ADMIN.

## Complaint lifecycle
`REPORTED -> VERIFIED -> ASSIGNED -> ACCEPTED -> IN_PROGRESS -> COMPLETED -> RESOLVED`, with `REJECTED` (officer) and `RESOLVED -> REOPENED` (citizen). See `Workflow.java`.

## Nearest-office algorithm
`OfficeService.nearest`: filter active offices, keep those within `serviceRadiusKm`, pick the smallest Haversine distance. Offices come from `src/main/resources/offices.csv` (import on first run). The bundled file is a starter set (AP, Telangana and major cities), **not** every municipality in India; import a fuller official dataset with the same columns via `OfficeService.importCsv`.

## Stack
Java 17, Spring Boot 3.2 (Web, Data JPA), H2 (default) or MySQL, BCrypt, Wakit WhatsApp OTP REST API, optional Twilio Verify SMS, vanilla HTML/CSS/JS.

## Setup
```bash
cp .env.example .env
# For real WhatsApp OTP:
export OTP_PROVIDER=wakit
export WAKIT_API_KEY=...
# Or for local/offline testing:
export OTP_PROVIDER=dev
mvn spring-boot:run      # http://localhost:8080
mvn test
```
**Real WhatsApp OTP (recommended for the portfolio demo):** create a Wakit account, copy the API key, then run with `OTP_PROVIDER=wakit` and `WAKIT_API_KEY=...`. Wakit's OTP API sends the code through WhatsApp and returns a message ID used by CleanStreet for verification. New Wakit workspaces currently advertise free starter messages; after the free allowance, sending is prepaid. See the provider docs for current limits/pricing. For offline demos, `OTP_PROVIDER=dev` accepts `123456` and sends nothing. Twilio remains available with `OTP_PROVIDER=twilio` if you have a configured Verify service. Delete `./data` if upgrading from the first version.

## Demo accounts (password `demo123`)
Officer `+919000000010` (Visakhapatnam), crew `+919000000011` / `+919000000012`, admin `+919000099999`. Set `app.seed-demo-users=false` in production.

## Demo workflow
Register citizen and verify OTP -> report issue -> officer verifies and assigns crew -> crew accepts, starts, completes with after photo -> officer resolves -> track by number on the home page.

## Security notes
BCrypt passwords, server-side role and ownership checks on complaint endpoints, expiring sessions, failed-login lockout, complaint submission rate limiting, audit events for important actions, image type and size validation with server-generated filenames, no secrets in code, no OTP stored or logged, same-origin only (no open CORS); evidence images are served through an authenticated media endpoint rather than a public static directory.

## Not implemented yet (roadmap)
Admin panel UI (office/category/user management), in-app notifications, Spring Security/JWT migration, Leaflet embedded map, reverse geocoding, officer filters and overdue views, integration tests, Docker.

## Screenshots
_Add screenshots here._
