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

## Option A — One server with Docker (recommended)

Works on any Linux server with Docker: AWS EC2 or Lightsail, DigitalOcean, Azure, or an office machine.
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

## Option B — Without Docker

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
