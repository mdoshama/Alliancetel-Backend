# AllianceTel Scheduling

Weekly workforce scheduling for AllianceTel stores. Admins set up areas (San Diego, Los Angeles,
San Francisco, …), their stores and staff. Managers build and publish each week's schedule, and
employees request days off and see their published week.

This repository is the **backend** (Spring Boot API + PostgreSQL). The web app lives in
[`scheduling-software-frontend`](../scheduling-software-frontend) (Angular + Bootstrap). The two
folders sit side by side:

```
IdeaProjects/
├── scheduling-software/            ← this repo: API, docker-compose.yml, DEPLOYMENT.md
└── scheduling-software-frontend/   ← web app (Angular), nginx config
```

```
browser ──► frontend (nginx: Angular site, forwards /api) ──► backend (Spring Boot API) ──► PostgreSQL
```

---

## What it does

### Roles

| Role | Can do |
|---|---|
| **Admin** | Everything: areas, stores, all employees, every area's schedules and requests. |
| **Manager** | Their own area: team, store assignments, day-off approvals, building and publishing schedules. |
| **Employee** | Request days off (including emergencies); see their published schedule and the area's stores with addresses and hours. |

### Areas, stores and staff

- Each **area** has **stores**. Every store has a short code, a **color** (used on the schedule),
  **opening hours per weekday** (e.g. Mon–Sat 10am–8pm, Sun 11am–7pm, or closed), and a
  **minimum number of staff per day**.
- Each employee belongs to an area and has a role at a store:
  - **Primary**: stays at their home store. Generate and Auto-fill never move them. A manager can
    move them by hand, after a warning.
  - **Secondary**: has a home store, but Auto-fill may send them to cover another store.
  - **Floater**: no home store. Auto-fill puts them wherever people are needed.
- Most staff work 6 days a week (configurable per person).

### Weekly schedules

- **Generate** builds the Monday–Sunday week with **everyone working every day their stores are open**.
  Approved days off are the only days off; nobody gets an automatic day off. Then everyone is placed at a store.
- **Day-off requests on the schedule**: pending requests are marked **Off?** on the person's day and listed in a
  panel above the grid with a staffing check (Covered / No spare staff / Leaves a store short). Approve or reject
  each one there or from the day itself, or **Approve all that fit**: requests are approved one at a time,
  earliest day first (emergencies first on a day), and any that would leave a store below its minimum stay
  pending for you to decide.
- **No day off yet**: people working more days than usual (e.g. 7 of 6) are flagged in amber with a banner, so
  you can approve their request or give them a day by hand (click a day → Off, or drag onto Off).
- **When a store's primary is off**, that store's secondaries stay there to cover it instead of being sent
  elsewhere.
- **Store assignment** keeps people at their home store, sends floaters (then secondaries) to stores
  below their minimum, never moves primaries or managers, skips stores closed that day, and spreads
  moves fairly. Anything a manager set by hand is kept.
- **Warnings**: store-days below their minimum show in red, with a banner listing them.
- **Editing**: drag any day onto any other day (any person, any day) and the two trade places, or onto
  a store's row to send the shift there. Or click any day to change it, swap two people, or move someone for the whole week.
  A work day always needs a store. Approved days off can't be turned back into work.
- **Auto-fill stores** re-runs store assignment after you change minimums or staff.
- **Publish** makes the week visible to employees and locks it. **Revert to draft** unlocks it.

### Days off

- Employees ask for specific days, at most **2 per week** (pending + approved).
- **One person off per store per day**: if a colleague at the same home store already has a day (pending or
  approved), it shows as *Taken* and a normal request is refused. This also keeps a store's primary and
  secondary from being off together. Floaters are never blocked.
- **Never short-staffed by requests**: a normal request is refused when the day is **Full**, meaning the
  area's other staff who would still be working are fewer than its open stores' minimums added up. Once the week's
  schedule exists it counts who is actually off that day (including days given by hand); before that it counts the
  pending and approved requests. Full days are marked in the request form, and the message names who is off.
- **Emergency day off**: for urgent cases. It skips the one-per-store rule, the Full-day rule and the weekly
  limit, is flagged in red, and still needs a manager's approval.
- Before approving, managers see a **staffing check**: how many people would still be working, who
  else is off that day, and whether any store would drop below its minimum (Covered / No spare
  staff / Leaves a store short).
- Approving updates that week's schedule straight away if it already exists, published or draft: that day
  becomes **Off ✓** (an approved day off) and the stores are refilled. No other day changes. Approved days off
  are locked: they can't be turned into work or edited by hand, and opening or publishing a week re-checks that
  every approved day is off.

### Email and WhatsApp messages

Employees hear about their schedule by **email** (on by default) and, once it's connected, on **WhatsApp**:

| When | Who gets a message | What it says |
|---|---|---|
| A week is **published** | Everyone on it | Their own week: each day's store and hours, or Off, and a link |
| A published week **changes** (republished after edits, or an approved day off moves people) | Only people whose days changed | Just the changed days: "Wed 11/4 now Off (was Oceanside 10am–8pm)" |
| A **day off** is approved or rejected | The person who asked | The decision and the manager's note |
| An **announcement** (Messages page) | Everyone in the area | The message, from the manager or admin |

- **Email** goes to each person's work email through [Resend](https://resend.com)'s web API (Railway blocks
  ordinary SMTP email on its Free and Hobby plans). It's on for everyone; people can turn it off in their account
  menu (top right), or a manager can in the employee's details.
- **WhatsApp** (Meta WhatsApp Cloud API) is optional and can be connected later. People must agree first (Meta's
  rule): a manager ticks "Agreed to get schedule updates on WhatsApp", or the person turns it on in their account
  menu. Until WhatsApp is connected, no WhatsApp messages are made.
- Every message is saved with the change that caused it and sent within seconds by a background sender. Ones that
  can't go out right away are retried; emails carry a key so the same email is never delivered twice. The
  **Messages** page lists what went to whom (filter by channel), failures with the reason, and **Try again**.
- Until email is connected, emails are only recorded there (status *Not sent*). How to connect email and
  WhatsApp: [DEPLOYMENT.md](DEPLOYMENT.md#email-messages).

### Sign-in and security

- **Passwords** people choose must be at least 10 characters with a letter and a number, and can't be a common
  word with digits added ("Welcome2026!", "P@ssw0rd1"), a keyboard or alphabet run ("qwerty1234"), the company name,
  or contain the person's own name or email. The change-password page checks as you type.
- **Temporary passwords** (new staff, resets) are 12 random characters, must be changed at first sign-in, and stop
  working after 7 days (`TEMP_PASSWORD_HOURS`).
- **Sessions** last 12 hours. *Sign out* ends that device's session on the server too, so a copied token stops
  working. *Sign out everywhere* (account menu) ends every session, e.g. after losing a phone. Changing or resetting a
  password, deactivating someone or changing their role or email also signs them out everywhere at once.
- **Guessing** is slowed down: 5 wrong passwords for one email (or 20 from one address) pause sign-in for 15 minutes.
  Wrong current passwords on the change-password page count too. Unknown emails and wrong passwords get the same
  answer in the same time, so the form doesn't reveal who has an account.
- **Storage:** passwords are kept only as BCrypt hashes (cost 12; older hashes are upgraded at the next sign-in).
- **Browser:** the site sends a strict Content-Security-Policy (only its own scripts may run), HSTS, and
  anti-framing headers (`nginx/security-headers.conf` in the frontend).
- **Logs:** sign-ins, failures, lockouts, password changes and resets are logged with the address they came from.

### Input checks

Every form and API request checks its values, with the same rules in the browser and on the server:
names (letters, spaces, `-` `'` `.`), codes (letters, numbers, `-` `_`), emails (`name@example.com`), store and area
names, and free text (no `<` `>` or line breaks). Phone numbers can be typed in any common format and are saved in
international form (`(619) 555-0142` → `+16195550142`; other countries need `+` and the country code). Errors
come back as plain sentences, e.g. "Phone must be a valid number, e.g. (619) 555-0142 …".

---

## Run it locally

### Requirements

- Java 25 (Temurin)
- PostgreSQL 14+ (a local database named `alliancetel`)
- Node 22 for the frontend

### Backend

```bash
createdb alliancetel                 # once
./mvnw spring-boot:run               # http://localhost:8080
```

On an empty database it adds **demo data** (3 areas, 9 stores, 24 staff). Every demo account uses
the password `Welcome123`:

| Who | Email |
|---|---|
| Admin | `admin@alliancetel.example` |
| Manager, San Diego | `manager.sandiego@alliancetel.example` |
| Employee | `aisha.khan@alliancetel.example` |

Turn the demo data off with `DEMO_DATA=false`.

### Frontend

```bash
cd ../scheduling-software-frontend
npm install
npm start                            # http://localhost:4200, forwards /api to :8080
```

### Everything in Docker (production setup)

```bash
cp .env.example .env                 # fill in DB_PASSWORD, JWT_SECRET, ADMIN_EMAIL, ADMIN_PASSWORD
docker compose up --build            # http://localhost
```

---

## Configuration

Every setting can be given as an environment variable.

| Variable | Default (local) | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/alliancetel` | Database connection. |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | Database login. |
| `JWT_SECRET` | development value | Signs sign-in tokens. **Required in production** (32+ random characters). |
| `TOKEN_HOURS` | `12` | How long a sign-in lasts. |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | demo values | First administrator, created only if no admin can sign in. Must change the password at first sign-in. |
| `MAX_FAILED_LOGINS` / `LOGIN_LOCK_MINUTES` | `5` / `15` | Sign-in lockout after repeated wrong passwords. |
| `TEMP_PASSWORD_HOURS` | `168` (7 days) | How long a temporary password works before an admin must reset it again. |
| `BCRYPT_STRENGTH` | `12` | Password hashing cost; each step doubles the work. |
| `MAX_DAY_OFF_PER_WEEK` | `2` | Day-off requests allowed per person per week. |
| `DEMO_DATA` | `true` (off in production) | Seed sample data into an empty database. |
| `CORS_ALLOWED_ORIGINS` | empty | Only needed if the site and API are on different domains. |
| `PORT` | `8080` | HTTP port. |
| `TZ` | `America/Los_Angeles` (Docker) | Decides what "today" and "this week" are. |
| `APP_URL` | empty | The site's address (e.g. `https://alliancetel.com`), for links in WhatsApp messages. |
| `EMAIL_ENABLED` | `false` | Send schedule emails (needs the next two). |
| `RESEND_API_KEY` | empty | API key from resend.com (Sending access). |
| `EMAIL_FROM` | empty | Sender, e.g. `AllianceTel <schedule@mail.yourdomain.com>`, on a domain verified in Resend. |
| `EMAIL_REPLY_TO` | empty | Optional: where replies go. |
| `WHATSAPP_ENABLED` | `false` | Send WhatsApp messages (needs the next two). |
| `WHATSAPP_PHONE_NUMBER_ID` / `WHATSAPP_ACCESS_TOKEN` | empty | From Meta's WhatsApp Manager; see DEPLOYMENT.md. |
| `WHATSAPP_API_VERSION` / `WHATSAPP_TEMPLATE_LANGUAGE` | `v22.0` / `en_US` | Meta Graph API version and template language. |
| `WHATSAPP_TEMPLATE_WEEK_PUBLISHED`, `…_WEEK_CHANGED`, `…_DAY_OFF`, `…_ANNOUNCEMENT` | `schedule_published`, `schedule_changed`, `day_off_update`, `team_announcement` | Template names, if yours differ. |

**Production mode** (`SPRING_PROFILES_ACTIVE=prod`, set automatically in Docker) refuses to start
without a database, a strong `JWT_SECRET` and, on an empty database, a valid `ADMIN_PASSWORD`. It
never seeds demo data and never shows error details to the browser.

---

## Tests

```bash
./mvnw test
```

- Unit tests: day-off spreading (`ScheduleGeneratorTest`), store assignment including primaries,
  floaters and closed days (`StoreAssignerTest`), approved days off (`DayOffPlannerTest`),
  sign-in lockout (`LoginAttemptServiceTest`).
- Integration tests on an in-memory database: access rules per role, stores and hours, colors,
  store roles, minimum staffing, day-off approval and the staffing check, paging and search.

GitHub Actions (`.github/workflows/ci.yml`) runs the tests and a Docker build on every push.

---

## API overview

All endpoints are under `/api` and need a sign-in token (`Authorization: Bearer …`) except
`POST /api/auth/login`. Health check: `GET /actuator/health`.

| Area | Endpoints |
|---|---|
| Sign-in | `POST /auth/login`, `GET /auth/me`, `POST /auth/change-password` |
| Areas | `GET/POST /areas`, `GET/PUT/DELETE /areas/{id}` |
| Stores | `GET/POST /stores`, `PUT/DELETE /stores/{id}` (hours, color, minimum) |
| Employees | `GET /employees/page?page&size&sort&areaId&storeId&role&storeRole&active&q` (paged), `GET/POST /employees`, `GET/PUT/DELETE /employees/{id}`, `PATCH /employees/{id}/home-store` (store and primary/secondary), `POST /employees/{id}/reset-password` |
| Messages | `GET /notifications?status&channel&page&size`, `PUT /auth/me/email` (`{optIn}`), `GET /notifications/status`, `POST /notifications/announce` (`{areaId, message}`), `POST /notifications/{id}/retry`, `PUT /auth/me/whatsapp` (`{optIn}`) |
| Days off | `POST /day-off-requests/approve-many` (`{ids, onlyIfCovered}`), `GET /day-off-requests/page?page&size&status&areaId&from&to&q` (paged), `GET/POST /day-off-requests`, `GET /day-off-requests/taken-days?employeeId&from&to`, `GET /day-off-requests/{id}/impact`, `POST /day-off-requests/{id}/approve`, `POST /day-off-requests/{id}/reject`, `DELETE /day-off-requests/{id}` |
| Schedules | `GET /schedules?areaId&weekStart`, `POST /schedules/generate`, `PATCH /schedules/{id}/entries/{entryId}`, `POST /schedules/{id}/swap`, `POST /schedules/{id}/swap-days`, `POST /schedules/{id}/exchange` (drag and drop), `POST /schedules/{id}/auto-assign-stores`, `POST /schedules/{id}/move-for-week`, `POST /schedules/{id}/publish`, `POST /schedules/{id}/revert-to-draft` |

Paged endpoints return `{ content, page, size, totalElements, totalPages }`. Pages start at 0, `size` is
1–100 (default 25), `q` is a case-insensitive search (`%` and `_` match themselves). The plain list
endpoints stay for pickers.

Errors come back as JSON (`application/problem+json`) with a plain-language `detail` message.

---

## Project layout

```
src/main/java/com/example/schedulingsoftware/
├── area/        areas
├── store/       stores, opening hours, colors
├── employee/    staff, roles, home store and primary/secondary/floater
├── dayoff/      day-off requests and approvals
├── schedule/    weekly schedules, generator, store assigner, day-off planner, staffing check
├── notify/      email (Resend) and WhatsApp (Meta) messages: queue, senders, background dispatcher, announcements
├── auth/        sign-in (JWT), access rules, password policy, lockout
├── config/      first admin and demo data
└── common/      error handling, page responses, input rules, phone numbers
```

The database tables are created and extended automatically on start-up (`ddl-auto: update`).
Take a backup before deploying a new version. See DEPLOYMENT.md.

---

## Deploying

See **[DEPLOYMENT.md](DEPLOYMENT.md)**:

- **Railway** (recommended): three services (Postgres, backend, frontend), with variables you can paste.
- Your own server with Docker Compose, with HTTPS via Caddy or a load balancer.
- Without Docker.
- Backups, moving local data, troubleshooting.
