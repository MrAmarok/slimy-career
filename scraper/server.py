"""Service de recherche d'offres : reçoit une demande du serveur de l'application et renvoie le résultat au fil de l'eau.

Il tourne dans son propre conteneur, sans aucun secret : ni mot de passe de la base, ni clé d'API, ni accès au
réseau de la base. Il ne fait que visiter les sites d'emploi et renvoyer ce qu'il y trouve ; c'est le serveur de
l'application qui enregistre les offres.

    POST /run     corps = la demande JSON (voir scrape.py) ; réponse = les lignes écrites par scrape.py,
                  envoyées au fur et à mesure. Si l'appelant coupe la connexion, la recherche est arrêtée.
    GET  /health  « ok »

    python server.py            # écoute sur le port 8000 (variable PORT pour en changer)
"""
import os
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).parent
MAX_BODY = 256 * 1024
MAX_RUNS = int(os.environ.get("MAX_RUNS", "4"))        # recherches simultanées (une par utilisateur)
MAX_SECONDS = int(os.environ.get("MAX_SECONDS", "3600"))
PING_SECONDS = 5
# le script de recherche ne reçoit que le strict nécessaire de l'environnement
CHILD_ENV = {"PATH": os.environ.get("PATH", ""), "PYTHONIOENCODING": "utf-8", "HOME": os.environ.get("HOME", "/tmp"),
             "LANG": "C.UTF-8"}
_slots = threading.BoundedSemaphore(MAX_RUNS)


class Handler(BaseHTTPRequestHandler):
    server_version = "scraper"

    def log_message(self, fmt, *args):
        pass

    def _text(self, status, text):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._text(200, "ok") if self.path == "/health" else self._text(404, "introuvable")

    def do_POST(self):
        if self.path != "/run":
            return self._text(404, "introuvable")
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if not 0 < length <= MAX_BODY:
            return self._text(413, "demande absente ou trop volumineuse")
        body = self.rfile.read(length)
        if not _slots.acquire(blocking=False):
            return self._text(429, "trop de recherches en cours")
        try:
            self._run(body)
        finally:
            _slots.release()

    def _run(self, body):
        proc = subprocess.Popen([sys.executable, "-u", str(ROOT / "scrape.py")], cwd=ROOT, env=CHILD_ENV,
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        proc.stdin.write(body)
        proc.stdin.close()
        # réponse sans taille annoncée : elle se termine à la fermeture de la connexion
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        lock = threading.Lock()
        started = time.monotonic()

        def send(data):
            with lock:
                self.wfile.write(data)
                self.wfile.flush()

        def ping():
            # une ligne régulière : si l'appelant est parti (recherche arrêtée), l'écriture échoue et on arrête tout
            while proc.poll() is None:
                time.sleep(PING_SECONDS)
                try:
                    send(b"@@PING\n")
                except OSError:
                    proc.kill()
                    return
                if time.monotonic() - started > MAX_SECONDS:
                    proc.kill()
                    return

        threading.Thread(target=ping, daemon=True).start()
        try:
            for line in proc.stdout:
                send(line)
            send(f"@@EXIT {proc.wait()}\n".encode())
        except OSError:
            pass
        finally:
            if proc.poll() is None:
                proc.kill()
            proc.wait()


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8000"))
    print(f"service de recherche : port {port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
