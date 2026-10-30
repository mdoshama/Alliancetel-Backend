# Deploying AllianceTel Scheduling

The app has three parts:

```
 browser ──► frontend (nginx: Angular site, forwards /api) ──► backend (Spring Boot API) ──► PostgreSQL
```

The two projects must sit side by side, as they do now:

```
IdeaProjects/
├── scheduling-software/            ← backend, docker-compose.yml, .env, this guide
└── scheduling-software-frontend/   ← Angular app, nginx config
```

---

## Option A — Railway (recommended)

On Railway the app runs as three services in one project:

| Service | Built from | Public? |
|---|---|---|
| **Postgres** | Railway's PostgreSQL template | No |
| **backend** | `scheduling-software` (its `Dockerfile` + `railway.json`) | No, private network only |
| **frontend** | `scheduling-software-frontend` (its `Dockerfile` + `railway.json`) | Yes, this is the website |

Railway builds each service from its `Dockerfile` and checks health using `railway.json`: the backend at
`/actuator/health`, the site at `/healthz`. Railway gives the website HTTPS automatically.

### 1. Put both projects on GitHub

Create two private repositories (e.g. `alliancetel-backend` and `alliancetel-frontend`) and push:

```bash
cd ~/IdeaProjects/scheduling-software
git init && git add . && git commit -m "AllianceTel scheduling backend"
git branch -M main
git remote add origin git@github.com:<you>/alliancetel-backend.git
git push -u origin main

cd ~/IdeaProjects/scheduling-software-frontend      # already a git repository
git add . && git commit -m "AllianceTel scheduling frontend"
git branch -M main
git remote add origin git@github.com:<you>/alliancetel-frontend.git
git push -u origin main
```

`.env` files are git-ignored, so no secrets end up on GitHub.

### 2. Create the project and database

1. In Railway: **New Project → Deploy PostgreSQL**. Keep the service name **`Postgres`**.
2. In the Postgres service, open the **Backups** tab and turn on the **Daily** (and ideally **Weekly**) schedule.
   Backups are billed like volume storage and can be restored into the same project.

### 3. Add the backend

1. **+ New → GitHub Repo → alliancetel-backend**. In the service **Settings**, rename it to **`backend`**
   (the frontend refers to it by this name).
2. **Variables** tab → **Raw Editor**, paste, then fill in the three blanks:

   ```
   PORT=8080
   DB_URL=jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}
   DB_USERNAME=${{Postgres.PGUSER}}
   DB_PASSWORD=${{Postgres.PGPASSWORD}}
   JWT_SECRET=
   ADMIN_EMAIL=
   ADMIN_PASSWORD=
   TZ=America/Los_Angeles
   ```

   - `JWT_SECRET`: run `openssl rand -base64 48` on your Mac and paste the result.
   - `ADMIN_EMAIL` / `ADMIN_PASSWORD`: the first administrator (8+ characters with a letter and a number).
     They must choose a new password after the first sign-in.
   - The `${{Postgres...}}` values are Railway references; Railway fills them in from the database service.
3. Don't generate a public domain for the backend. It only needs to be reachable from the frontend.

### 4. Add the frontend (the website)

1. **+ New → GitHub Repo → alliancetel-frontend**, and name the service **`frontend`**.
2. **Variables**:

   ```
   BACKEND_URL=http://${{backend.RAILWAY_PRIVATE_DOMAIN}}:8080
   ```

   This resolves to `http://backend.railway.internal:8080`; you can also type that directly.

3. **Settings → Networking → Generate Domain** gives a `*.up.railway.app` address with HTTPS. You can also
   add a custom domain such as `schedule.alliancetel.com`; Railway shows the DNS record to create.

### 5. Deploy and sign in

Railway deploys each service when it's created and on every push to `main`. The first backend build takes a few
minutes while Maven downloads libraries. When all three services show **Active**, open the site and sign in with
`ADMIN_EMAIL` / `ADMIN_PASSWORD`. Then set up areas, stores (with minimum staff per day) and employees.
After the first sign-in you can delete the `ADMIN_PASSWORD` variable.

### Moving your current local data to Railway (optional)

If you'd rather keep what you've already entered on your Mac than start fresh:

1. In the Postgres service, **Settings → Networking**, add **Public Access** (a TCP proxy). This fills in
   `DATABASE_PUBLIC_URL`; copy it from the **Variables** tab.
2. On your Mac:

   ```bash
   pg_dump -U postgres -Fc alliancetel > alliancetel.dump
   pg_restore --clean --if-exists --no-owner -d "<DATABASE_PUBLIC_URL>" alliancetel.dump
   ```

3. Remove the public access again (traffic through it is billed) and restart the backend service.

Demo accounts (`…@alliancetel.example`, password `Welcome123`) come across too. Deactivate them in Employees.

### Updating on Railway

Push to `main` in either repository and Railway redeploys that service; the old version keeps serving until the new one passes its health check. The site keeps
working while the backend redeploys, because nginx looks up the backend's new address every 10 seconds.
Take a manual backup (Postgres → Backups) before deploying anything that changes stored data.

### Railway troubleshooting

| Symptom | Check |
|---|---|
| Backend deploy fails its healthcheck | Backend **Deploy Logs**: the first error line names the missing setting (e.g. `JWT_SECRET`, `ADMIN_PASSWORD`, `DB_URL`). |
| Site loads but sign-in says "Cannot reach the server" | The backend service must be named `backend` and have `PORT=8080`; the frontend's `BACKEND_URL` must match. |
| `${{Postgres.PGHOST}}` stays empty | The database service must be named exactly `Postgres` (or change the references to its name). |

### Email messages

The app emails each employee their schedule through **Resend** (resend.com). Railway blocks ordinary SMTP email on
its Free and Hobby plans, so the app uses Resend's web API instead, which works on every plan. Resend's free plan
covers 3,000 emails a month (100 a day). Until these steps are done, emails are only recorded on the **Messages**
page (status *Not sent*).

1. **Sign up** at [resend.com](https://resend.com).
2. **Verify a domain.** *Domains → Add domain* → enter a subdomain of your site, e.g. `mail.yourdomain.com`.
   Resend shows a few DNS records (TXT for SPF and DKIM, and an MX). In **Namecheap → Domain List → Manage → Advanced
   DNS**, add each one exactly as shown (in the Host field, leave off your main domain, e.g. `resend._domainkey.mail`
   instead of `resend._domainkey.mail.yourdomain.com`). Back in Resend click **Verify DNS records**; it usually turns
   *Verified* within minutes.
3. **Create an API key.** *API Keys → Create API Key*, permission **Sending access**, optionally limited to that
   domain. Copy it now (it starts with `re_` and is shown once).
4. **Railway → backend service → Variables**, add:

   | Variable | Value |
   |---|---|
   | `EMAIL_ENABLED` | `true` |
   | `RESEND_API_KEY` | the key from step 3 |
   | `EMAIL_FROM` | `AllianceTel <schedule@mail.yourdomain.com>` (any name before the @, on the verified domain) |
   | `EMAIL_REPLY_TO` | optional, e.g. a manager's address, so replies reach a person |
   | `APP_URL` | your site, e.g. `https://yourdomain.com` (the button in each email opens it) |

   The backend restarts. The Messages page now shows **Email connected**.
5. **Try it.** Publish a week, or send an announcement from the Messages page; each email turns *Sent* within a few
   seconds. If one fails, the Messages page shows Resend's reason, e.g. *domain is not verified* (step 2) or *daily
   quota exceeded* (more than 100 in a day on the free plan); fix it, then **Try again**.

Before verifying a domain you can test by setting `EMAIL_FROM` to `AllianceTel <onboarding@resend.dev>`, but Resend
then only delivers to the email address you signed up with.

### WhatsApp messages

Optional, and can be added any time after email. The app sends each employee their schedule on WhatsApp through
Meta's **WhatsApp Cloud API**. Until these steps are done, no WhatsApp messages are made; email keeps working.

1. **Meta Business account and app.** At [business.facebook.com](https://business.facebook.com) create (or use) your
   business. At [developers.facebook.com](https://developers.facebook.com) → *My Apps* → *Create app* → type
   *Business*, then add the **WhatsApp** product.
2. **A phone number for WhatsApp.** In *WhatsApp Manager → Phone numbers*, add the number messages will come from
   (it can't already be used in the WhatsApp app). Verify it, and set the display name (e.g. "AllianceTel").
   Copy its **Phone number ID** (a long number, not the phone number itself).
3. **A permanent access token.** *Business settings → Users → System users* → add a system user (Admin) → *Add
   assets*: your app (full control) and your WhatsApp account → *Generate new token* for the app with the
   permissions `whatsapp_business_messaging` and `whatsapp_business_management`, set to never expire. Copy it now;
   it's shown once. (The 24-hour token on the app's *API Setup* page is only for trying things out.)
4. **Four message templates.** *WhatsApp Manager → Message templates → Create template*, category **Utility**,
   language **English (US)**, with exactly these names and bodies (the admin's Messages page shows them with Copy
   buttons). Add a sample for each {{n}} when asked, and wait for each to be **Approved**.

   | Name | Body |
   |---|---|
   | `schedule_published` | `Hi {{1}}, your AllianceTel schedule for {{2}} is posted: {{3}}. See it anytime at {{4}}.` |
   | `schedule_changed` | `Hi {{1}}, your AllianceTel schedule for {{2}} has changed: {{3}}. See your full week at {{4}}.` |
   | `day_off_update` | `Hi {{1}}, your day-off request for {{2}} was {{3}}. {{4}}` |
   | `team_announcement` | `Message from {{1}} for the AllianceTel {{2}} team: {{3}}` |

5. **Railway → backend service → Variables**, add:

   | Variable | Value |
   |---|---|
   | `WHATSAPP_ENABLED` | `true` |
   | `WHATSAPP_PHONE_NUMBER_ID` | the Phone number ID from step 2 |
   | `WHATSAPP_ACCESS_TOKEN` | the token from step 3 |
   | `APP_URL` | your site, e.g. `https://alliancetel.com` (used in the link at the end of messages) |

   The backend restarts. The Messages page now says *WhatsApp is connected*.
6. **Phone numbers and agreement.** Add each employee's mobile number (any format; it's saved as +1…), and tick
   *Agreed to get schedule updates on WhatsApp* once they've agreed, or let them switch it on from their account
   menu. Publish a week and watch the Messages page: each message turns *Sent* within a few seconds.

Good to know:
- Meta charges per template message (rates depend on the country and category; see Meta's WhatsApp pricing page),
  billed to the payment method on your WhatsApp account. New accounts start with a daily messaging limit that rises
  with use.
- If a message fails, the Messages page shows WhatsApp's reason, e.g. *Template name does not exist* (the name or
  language doesn't match an approved template) or *Receiver is incapable of receiving this message* (that number
  isn't on WhatsApp). Fix it, then **Try again**.
- Run a single backend instance (the Railway default), so each message is sent once.

---

## Option B — Your own server with Docker

For hosting on your own Linux server instead of Railway (AWS EC2 or Lightsail, DigitalOcean, an office machine).
This also lets you run the full production setup on your Mac: `docker compose up --build`.
A small server (2 vCPU, 2–4 GB RAM) is plenty for a few hundred employees.

### 1. Prepare the server

Install Docker Engine with the Compose plugin (https://docs.docker.com/engine/install/), then copy both
project folders onto the server (git clone, or scp).

Open only ports **80** and **443** in the firewall / security group. The database and backend are never exposed.

### 2. Create the settings file

```bash
cd scheduling-software
cp .env.example .env
openssl rand -base64 48    # paste as JWT_SECRET
openssl rand -base64 24    # paste as DB_PASSWORD
nano .env                  # also set ADMIN_EMAIL and ADMIN_PASSWORD
```

| Setting | What it is |
|---|---|
| `DB_PASSWORD` | PostgreSQL password (used by the database and the API). |
| `JWT_SECRET` | Signs sign-in tokens. 32+ random characters. Changing it signs everyone out. |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | The first administrator, created on the first start only. They must choose a new password after signing in. You can clear `ADMIN_PASSWORD` afterwards. |
| `HTTP_PORT` | Port the site is served on (default 80). |
| `TZ` | Time zone that decides "today" and "this week" (default `America/Los_Angeles`). |

### 3. Start it

```bash
docker compose up -d --build
docker compose ps           # all three should become "healthy" / "running" within ~2 minutes
docker compose logs -f backend
```

Open `http://<server-address>` and sign in with `ADMIN_EMAIL` / `ADMIN_PASSWORD`. Then add areas, stores
(with their minimum staff per day) and employees. Each new employee gets a one-time temporary password to hand over.

### 4. Turn on HTTPS

Passwords must never travel over plain HTTP. Pick one:

- **Load balancer** (AWS ALB, Cloudflare, etc.): terminate HTTPS there and forward to port 80 on the server.
- **Caddy on the same server** (automatic free certificates): set `HTTP_PORT=8080` in `.env`, install Caddy,
  and use this `/etc/caddy/Caddyfile`:

  ```
  schedule.alliancetel.com {
      reverse_proxy localhost:8080
  }
  ```

  Point the domain's DNS at the server, then `sudo systemctl reload caddy`.

### 5. Updating to a new version

```bash
# back up first (see below), then:
git pull                                   # in both project folders
docker compose up -d --build
```

The database keeps its data in the `alliancetel_db-data` Docker volume across updates and restarts.

---

## Option C — Without Docker

1. **Database:** PostgreSQL 14+; create a database and user.
2. **Backend:** `./mvnw -DskipTests package` produces `target/alliancetel-scheduling.jar` (needs Java 25). Run it with:

   ```bash
   SPRING_PROFILES_ACTIVE=prod \
   DB_URL=jdbc:postgresql://localhost:5432/alliancetel DB_USERNAME=alliancetel DB_PASSWORD=... \
   JWT_SECRET=... ADMIN_EMAIL=... ADMIN_PASSWORD=... \
   java -Duser.timezone=America/Los_Angeles -jar target/alliancetel-scheduling.jar
   ```

   (Use a systemd service so it restarts automatically.)
3. **Frontend:** `npm ci && npx ng build` produces `dist/scheduling-software-frontend/browser`. Serve that folder with
   nginx using `scheduling-software-frontend/nginx/default.conf.template`: replace `${BACKEND_URL}` with
   `http://127.0.0.1:8080` and put `security-headers.conf` in `/etc/nginx/snippets/`.

If the site and API must live on different domains, set `CORS_ALLOWED_ORIGINS=https://your-site-domain` on the backend.

---

## Backups

Daily database backup (add to the server's crontab with `crontab -e`):

```
0 2 * * * cd /path/to/scheduling-software && docker compose exec -T db pg_dump -U alliancetel -Fc alliancetel > /var/backups/alliancetel-$(date +\%F).dump
```

Restore:

```bash
docker compose exec -T db pg_restore -U alliancetel -d alliancetel --clean < /var/backups/alliancetel-2026-11-02.dump
```

Copy backups off the server too (S3, another machine).

### Moving your local data to the server

On your Mac:

```bash
pg_dump -U postgres -Fc alliancetel > alliancetel.dump
```

Copy the file to the server, start the stack once, then:

```bash
docker compose exec -T db pg_restore -U alliancetel -d alliancetel --clean --no-owner < alliancetel.dump
docker compose restart backend
```

Your existing accounts keep working. Demo accounts (`…@alliancetel.example`, password `Welcome123`) come
across too, so deactivate them or reset their passwords.

---

## What production mode does

`SPRING_PROFILES_ACTIVE=prod` (set automatically by Docker):

- **Refuses to start** without `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` or `JWT_SECRET`, with the development JWT secret,
  or, on an empty database, without a valid `ADMIN_PASSWORD`. The log says exactly what is missing.
- **No demo data** is created.
- **Sign-in protection:** after 5 wrong passwords for one email (or 20 from one address) sign-in is paused for
  15 minutes (`MAX_FAILED_LOGINS`, `LOGIN_LOCK_MINUTES` to adjust).
- **Errors** never reveal internal details to the browser; full details go to the backend log.
- **Health checks:** `GET /actuator/health` (API) and `GET /healthz` (site) for load balancers and monitoring.

### Database tables

Tables are created and extended automatically when the backend starts (Hibernate `ddl-auto: update`).
This adds new tables and columns safely, but it never renames or removes them. **Always take a backup before
updating.** If the schema needs a rename or a data change in future, add a migration tool (Flyway) first.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Site shows but sign-in says "Cannot reach the server" | `docker compose logs backend`; the backend may still be starting (≈30–60 s). |
| Backend exits right away | `docker compose logs backend`: look for the line naming the missing setting. |
| Everyone was signed out | `JWT_SECRET` changed, or tokens expired (12 h by default, `TOKEN_HOURS`). |
| Locked out of the only admin account | Wait 15 minutes; or reset via the database: set `password_hash = NULL` for that admin, set `ADMIN_EMAIL`/`ADMIN_PASSWORD` and restart. The admin is re-created with that password. |
| Dates look a day off | Check `TZ` in `.env`. |
