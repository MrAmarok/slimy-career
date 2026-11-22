// Lancement des services en développement, depuis la racine du dépôt :
//
//   bun run database    base PostgreSQL (conteneur Docker, port 5433)
//   bun run scraper     service de recherche d'offres (http://localhost:8000)
//   bun run backend     serveur Spring Boot (http://localhost:8080)
//   bun run frontend    interface Angular (http://localhost:4200)
//   bun run dev         les quatre à la fois
//
// Réglages (facultatifs) dans « .env.dev », à la racine, non versionné. Le fichier « .env » du déploiement
// n'est jamais lu ici : ses secrets ne doivent pas arriver dans les services de développement.

import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";

const root = resolve(import.meta.dir, "..");
const settings = { ...process.env, ...readEnvFile(join(root, ".env.dev")) };

const DB_CONTAINER = "candidatures-dev-db";
const DB_VOLUME = "candidatures-dev-db";
const DB_PORT = settings.DEV_DB_PORT || "5433";
const BACKEND_PORT = settings.DEV_BACKEND_PORT || "8080";
const SCRAPER_PORT = settings.DEV_SCRAPER_PORT || "8000";
const FRONTEND_PORT = settings.DEV_FRONTEND_PORT || "4200";

type Service = { name: string; color: string; cmd: string[]; cwd: string; env: Record<string, string | undefined> };

function readEnvFile(path: string): Record<string, string> {
  if (!existsSync(path)) return {};
  const values: Record<string, string> = {};
  for (const line of readFileSync(path, "utf8").split("\n")) {
    const match = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
    if (match) values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return values;
}

function fail(message: string): never {
  console.error(`\x1b[31m${message}\x1b[0m`);
  process.exit(1);
}

function need(tool: string, hint: string) {
  if (!Bun.which(tool)) fail(`« ${tool} » est introuvable. ${hint}`);
}

async function run(cmd: string[], cwd = root, env: Record<string, string | undefined> = process.env) {
  const code = await Bun.spawn(cmd, { cwd, env, stdio: ["inherit", "inherit", "inherit"] }).exited;
  if (code !== 0) process.exit(code);
}

async function capture(cmd: string[]): Promise<{ code: number; out: string }> {
  const proc = Bun.spawn(cmd, { cwd: root, stdout: "pipe", stderr: "pipe" });
  const out = await new Response(proc.stdout).text();
  return { code: await proc.exited, out: out.trim() };
}

// ---------- base de données ----------

async function database() {
  need("docker", "Docker est nécessaire pour la base de données.");
  const state = await capture(["docker", "inspect", "-f", "{{.State.Running}}", DB_CONTAINER]);
  if (state.code !== 0) {
    await run([
      "docker", "run", "-d", "--name", DB_CONTAINER,
      "-p", `127.0.0.1:${DB_PORT}:5432`,
      "-v", `${DB_VOLUME}:/var/lib/postgresql/data`,
      "-e", "POSTGRES_DB=candidatures", "-e", "POSTGRES_USER=candidatures", "-e", "POSTGRES_PASSWORD=candidatures",
      "postgres:17-alpine",
    ]);
  } else if (state.out !== "true") {
    await run(["docker", "start", DB_CONTAINER]);
  }
  const published = await capture(["docker", "port", DB_CONTAINER, "5432/tcp"]);
  if (!published.out.endsWith(`:${DB_PORT}`)) {
    fail(`Le conteneur ${DB_CONTAINER} n'est pas publié sur le port ${DB_PORT} (${published.out || "aucun port"}).\n`
      + `Le port est peut-être pris, ou DEV_DB_PORT a changé : « bun run database:reset » recrée la base (données de dev effacées).`);
  }
  for (let i = 0; i < 60; i++) {
    const ready = await capture(["docker", "exec", DB_CONTAINER, "pg_isready", "-U", "candidatures", "-d", "candidatures"]);
    if (ready.code === 0) {
      console.log(`Base de données prête : localhost:${DB_PORT} (conteneur ${DB_CONTAINER}).`);
      return;
    }
    await Bun.sleep(500);
  }
  fail("La base de données ne répond pas. Voir : docker logs " + DB_CONTAINER);
}

async function databaseStop() {
  await run(["docker", "stop", DB_CONTAINER]);
}

async function databaseReset() {
  await capture(["docker", "rm", "-f", DB_CONTAINER]);
  await capture(["docker", "volume", "rm", DB_VOLUME]);
  await database();
}

// ---------- services ----------

async function scraper(): Promise<Service> {
  need("python3", "Python 3.12 est nécessaire pour la recherche d'offres.");
  const python = join(root, ".venv", "bin", "python");
  const stamp = join(root, ".venv", ".requirements");
  const requirements = join(root, "scraper", "requirements.txt");
  const wanted = Bun.hash(readFileSync(requirements)).toString();
  if (!existsSync(python)) await run(["python3", "-m", "venv", ".venv"]);
  if (!existsSync(stamp) || readFileSync(stamp, "utf8") !== wanted) {
    await run([python, "-m", "pip", "install", "-q", "--require-hashes", "-r", requirements]);
    await Bun.write(stamp, wanted);
  }
  // aucun secret : le service visite des sites tiers
  const env = { PATH: process.env.PATH, HOME: process.env.HOME, LANG: process.env.LANG, PORT: SCRAPER_PORT };
  return { name: "scraper", color: "35", cmd: [python, "server.py"], cwd: join(root, "scraper"), env };
}

function backend(): Service {
  need("mvn", "Java 21 et Maven sont nécessaires pour le serveur (voir docs/developpement.md pour s'en passer).");
  const env = {
    ...process.env,
    PORT: BACKEND_PORT,
    DATABASE_URL: settings.DATABASE_URL || `jdbc:postgresql://localhost:${DB_PORT}/candidatures`,
    DATABASE_USER: settings.DATABASE_USER || "candidatures",
    DATABASE_PASSWORD: settings.DATABASE_PASSWORD || "candidatures",
    APP_ADMIN_USERNAME: settings.APP_ADMIN_USERNAME || "admin",
    APP_ADMIN_PASSWORD: settings.APP_ADMIN_PASSWORD || "changeme-123",
    APP_SECRET_KEY: settings.APP_SECRET_KEY || "dev-seulement",
    SCRAPER_URL: settings.SCRAPER_URL || `http://localhost:${SCRAPER_PORT}`,
    SERPAPI_KEY: settings.SERPAPI_KEY || "",
    ...(settings.LANGUAGETOOL_URL ? { LANGUAGETOOL_URL: settings.LANGUAGETOOL_URL } : {}),
  };
  return { name: "backend", color: "33", cmd: ["mvn", "-q", "spring-boot:run"], cwd: join(root, "backend"), env };
}

async function frontend(): Promise<Service> {
  const cwd = join(root, "frontend");
  if (!existsSync(join(cwd, "node_modules"))) await run(["bun", "install"], cwd);
  const cmd = ["bun", "run", "start", "--port", FRONTEND_PORT];
  return { name: "frontend", color: "36", cmd, cwd, env: { ...process.env, DEV_BACKEND_PORT: BACKEND_PORT } };
}

// ---------- exécution ----------

async function foreground(service: Service) {
  const proc = Bun.spawn(service.cmd, { cwd: service.cwd, env: service.env, stdio: ["inherit", "inherit", "inherit"] });
  for (const signal of ["SIGINT", "SIGTERM"] as const) process.on(signal, () => proc.kill(signal));
  process.exit(await proc.exited);
}

async function prefixed(stream: ReadableStream<Uint8Array>, service: Service) {
  const label = `\x1b[${service.color}m${service.name.padEnd(8)}│\x1b[0m `;
  const decoder = new TextDecoder();
  let rest = "";
  for await (const chunk of stream) {
    const lines = (rest + decoder.decode(chunk, { stream: true })).split("\n");
    rest = lines.pop() ?? "";
    for (const line of lines) console.log(label + line);
  }
  if (rest) console.log(label + rest);
}

async function all() {
  await database();
  const services = [await scraper(), backend(), await frontend()];
  const procs = services.map((service) => {
    const proc = Bun.spawn(service.cmd, { cwd: service.cwd, env: service.env, stdin: "ignore", stdout: "pipe", stderr: "pipe" });
    prefixed(proc.stdout, service);
    prefixed(proc.stderr, service);
    return proc;
  });
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    for (const proc of procs) proc.kill("SIGTERM");
  };
  process.on("SIGINT", stop);
  process.on("SIGTERM", stop);
  console.log(`Interface : http://localhost:${FRONTEND_PORT}   (Ctrl+C pour tout arrêter ; la base continue de tourner)`);
  // si un service s'arrête, on arrête les autres
  const first = await Promise.race(procs.map((proc, i) => proc.exited.then((code) => ({ code, name: services[i].name }))));
  if (!stopping) console.error(`\x1b[31mLe service « ${first.name} » s'est arrêté (code ${first.code}).\x1b[0m`);
  const failed = !stopping && first.code !== 0;
  stop();
  await Promise.all(procs.map((proc) => proc.exited));
  process.exit(failed ? 1 : 0);
}

const commands: Record<string, () => Promise<unknown>> = {
  database,
  "database:stop": databaseStop,
  "database:reset": databaseReset,
  scraper: async () => foreground(await scraper()),
  backend: async () => foreground(backend()),
  frontend: async () => foreground(await frontend()),
  dev: all,
};

const command = commands[process.argv[2] ?? ""];
if (!command) fail(`Commande inconnue. Disponibles : ${Object.keys(commands).join(", ")}`);
await command();
