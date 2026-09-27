#!/usr/bin/env python3
"""
Ares ASM CLI
Usage:
  ares --version / -V                 — print the installed CLI version
  ares configure                      — log in via browser, save server URL + API key
  ares configure --manual             — paste an API key manually instead
  ares orgs                           — list organizations
  ares projects --org SLUG            — list projects in an organization
  ares assets [OPTIONS]               — list assets
  ares scope --org SLUG --project CODE — list a project's scope entries
  ares load FILE [OPTIONS]            — upload a scan file
  ares exploits [OPTIONS]             — search exploits/PoCs
  ares exploits pull <ID|CVE>... [OPTIONS] — download exploit(s) as zip
  ares tag {add,rm,ls} <ASSET> ...    — manage tags on an asset
  ares wl {ls,use,cat,push} [OPTIONS] — wordlist management
  ares update [-y]                    — check for and install CLI updates
  ares cli                            — interactive shell
"""

import argparse
import json
import mimetypes
import os
import platform
import re
import shutil
import sys
import tempfile
import textwrap
import time
import webbrowser
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

try:
    import requests
except ImportError:
    print("Error: 'requests' library is required. Run: pip install requests", file=sys.stderr)
    sys.exit(1)

# Bumped alongside ares-cli/pyproject.toml's version on every release — this is the
# single source of truth read at runtime (pyproject.toml isn't shipped alongside the
# raw-script/.deb/.rpm installs, only this file is), used both to report `ares --version`
# and to compare against the server's version for the auto-update check below.
CLI_VERSION = "1.0.0-beta4"

# ── Config ────────────────────────────────────────────────────────────────────

CONFIG_PATH = Path.home() / ".ares" / "config.json"

def load_config() -> dict:
    cfg = {}
    if CONFIG_PATH.exists():
        try:
            cfg = json.loads(CONFIG_PATH.read_text())
        except Exception:
            pass
    # Env vars override the config file — lets CI/automation (no interactive `ares
    # configure` possible) authenticate via secrets without ever writing a credential
    # file to a (possibly shared, self-hosted) runner's disk.
    if os.environ.get("ARES_SERVER"):  cfg["server"]  = os.environ["ARES_SERVER"]
    if os.environ.get("ARES_API_KEY"): cfg["api_key"] = os.environ["ARES_API_KEY"]
    return cfg

def save_config(cfg: dict):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))

def require_config(cfg: dict):
    if not cfg.get("server") or not cfg.get("api_key"):
        print("Not configured. Run: ares configure", file=sys.stderr)
        sys.exit(1)

# ── Update check ─────────────────────────────────────────────────────────────

UPDATE_CHECK_INTERVAL = timedelta(hours=24)

def fetch_server_version(server: str, timeout: int = 5) -> Optional[str]:
    try:
        r = requests.get(f"{server.rstrip('/')}/api/v1/cli/version", timeout=timeout)
        r.raise_for_status()
        return r.json().get("version")
    except Exception:
        return None

def check_for_update(cfg: dict):
    """Best-effort, throttled (once per UPDATE_CHECK_INTERVAL) check against the
    configured server's current CLI version, cached in config.json so most runs don't
    make an extra network call. Never raises — a network hiccup here must not break
    whatever real command the user is running."""
    server = cfg.get("server")
    if not server:
        return

    meta = cfg.get("_update_check") or {}
    latest = meta.get("latest_version")
    last_checked = meta.get("last_checked")

    stale = True
    if last_checked:
        try:
            stale = datetime.now(timezone.utc) - datetime.fromisoformat(last_checked) >= UPDATE_CHECK_INTERVAL
        except Exception:
            stale = True

    if stale:
        fetched = fetch_server_version(server, timeout=3)
        if fetched is None:
            return  # offline / unreachable — don't update the cache, try again next run
        latest = fetched
        cfg["_update_check"] = {"last_checked": datetime.now(timezone.utc).isoformat(), "latest_version": latest}
        try:
            save_config(cfg)
        except Exception:
            pass

    if latest and latest != CLI_VERSION:
        print(f"→ A new version of the Ares CLI is available: {CLI_VERSION} → {latest}. Run 'ares update' to upgrade.",
              file=sys.stderr)

# ── Wordlist cache ────────────────────────────────────────────────────────────

WL_CACHE_DIR   = Path.home() / ".ares" / "wordlists"
WL_CACHE_INDEX = WL_CACHE_DIR / ".index.json"

def _load_wl_index() -> dict:
    if WL_CACHE_INDEX.exists():
        try:
            return json.loads(WL_CACHE_INDEX.read_text())
        except Exception:
            pass
    return {}

def _save_wl_index(idx: dict):
    WL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    WL_CACHE_INDEX.write_text(json.dumps(idx, indent=2))

# ── HTTP client ───────────────────────────────────────────────────────────────

class AresClient:
    def __init__(self, server: str, api_key: str):
        self.base = server.rstrip("/") + "/api/v1"
        self.session = requests.Session()
        self.session.headers.update({"X-API-Key": api_key, "Accept": "application/json"})

    def get(self, path: str, params: dict = None) -> dict | list:
        r = self.session.get(f"{self.base}{path}", params=params, timeout=30)
        r.raise_for_status()
        return r.json()

    def post_json(self, path: str, body: dict) -> dict | list:
        r = self.session.post(f"{self.base}{path}", json=body, timeout=30)
        r.raise_for_status()
        return r.json() if r.content else {}

    def delete(self, path: str) -> None:
        r = self.session.delete(f"{self.base}{path}", timeout=30)
        r.raise_for_status()

    def post_file(self, path: str, file_path: Path, tool: str, fmt: str = "default") -> dict:
        with open(file_path, "rb") as f:
            r = self.session.post(
                f"{self.base}{path}",
                params={"tool": tool, "format": fmt},
                files={"file": (file_path.name, f, "application/octet-stream")},
                timeout=120,
            )
        r.raise_for_status()
        return r.json()

    def list_tools(self, project_id: int) -> list:
        return self.get(f"/projects/{project_id}/imports/tools")

    def list_orgs(self) -> list:
        return self.get("/organizations")["items"]

    def list_projects(self, org_id: int) -> list:
        return self.get("/projects", params={"organizationId": org_id, "size": 200})["items"]

    def get_project(self, project_id: int) -> dict:
        return self.get(f"/projects/{project_id}")

    def find_org(self, slug: str) -> Optional[dict]:
        for o in self.list_orgs():
            if o["slug"].upper() == slug.upper() or str(o["id"]) == slug:
                return o
        return None

    def find_project(self, org_id: int, code: str) -> Optional[dict]:
        for p in self.list_projects(org_id):
            if p["code"].upper() == code.upper() or str(p["id"]) == code:
                return p
        return None

    def find_asset(self, code_or_id: str, project_id: int = None) -> Optional[dict]:
        """Resolve an asset by numeric ID (works with no project context) or by its
        code within a project (requires project_id)."""
        if code_or_id.isdigit():
            try:
                return self.get_asset(int(code_or_id))
            except requests.HTTPError:
                return None
        if project_id is None:
            return None
        data = self.list_assets(project_id=project_id, q=code_or_id, size=200)
        for a in data.get("items", []):
            if a.get("code", "").upper() == code_or_id.upper():
                return a
        return None

    def list_assets(self, org_id: int = None, project_id: int = None,
                    asset_type: str = None, q: str = None, aql: str = None,
                    page: int = 0, size: int = 50) -> dict:
        params = {"page": page, "size": size}
        if org_id:     params["organizationId"] = org_id
        if project_id: params["projectId"] = project_id
        if asset_type: params["type"] = asset_type
        if q:          params["q"] = q
        if aql:        params["aql"] = aql
        return self.get("/assets", params=params)

    def get_asset(self, asset_id: int) -> dict:
        return self.get(f"/assets/{asset_id}")

    # ── Detection methods ────────────────────────────────────────────────────
    # AQL implementation plan, Phase 4: --aql is an alternative filtering mode to
    # the discrete --severity/--status/-q filters, not merged with them — same
    # coexistence rule the backend enforces (aql present -> discrete params ignored).

    def list_detections(self, project_id: int, severity: list = None, status: list = None,
                        source: list = None, q: str = None, aql: str = None,
                        page: int = 0, size: int = 50) -> dict:
        params: dict = {"projectId": project_id, "page": page, "size": size}
        if severity: params["severity"] = severity
        if status:   params["status"] = status
        if source:   params["sourceType"] = source
        if q:        params["q"] = q
        if aql:      params["aql"] = aql
        return self.get("/detections", params=params)

    # ── Finding methods ──────────────────────────────────────────────────────

    def list_findings(self, org_id: int = None, project_id: int = None,
                      severity: list = None, include_drafts: bool = False,
                      q: str = None, aql: str = None, page: int = 0, size: int = 50) -> dict:
        params: dict = {"page": page, "size": size}
        if org_id:     params["organizationId"] = org_id
        if project_id: params["projectId"] = project_id
        if severity:   params["severity"] = severity
        if include_drafts: params["includeDrafts"] = "true"
        if q:          params["q"] = q
        if aql:        params["aql"] = aql
        return self.get("/findings", params=params)

    def assign_asset_tag(self, asset_id: int, tag_id: int) -> dict:
        return self.post_json(f"/assets/{asset_id}/tags/{tag_id}", {})

    def unassign_asset_tag(self, asset_id: int, tag_id: int) -> dict:
        self.delete(f"/assets/{asset_id}/tags/{tag_id}")
        return self.get_asset(asset_id)

    # ── Tag methods ────────────────────────────────────────────────────────────
    # (org-scoped; assigned onto assets/detections via each entity's own
    #  /{id}/tags/{tagId} endpoints — see assign_asset_tag/unassign_asset_tag above)

    def list_tags(self, org_id: int) -> list:
        return self.get(f"/organizations/{org_id}/tags")

    def create_tag(self, org_id: int, name: str, color: str = None) -> dict:
        return self.post_json(f"/organizations/{org_id}/tags", {"name": name, "color": color})

    # ── Exploit methods ───────────────────────────────────────────────────────
    # NOTE for future AQL work: --cve/-q/--source are discrete, named filters —
    # deliberately not a bespoke query-string syntax — so a future `--aql "..."`
    # flag can slot in as an alternative filtering mode without having to redesign
    # these. Keep that pattern for any new list/search command added before AQL lands.

    def list_exploits(self, cve: str = None, q: str = None, source: list = None,
                      page: int = 0, size: int = 50) -> dict:
        params: dict = {"page": page, "size": size}
        if cve:    params["cve"] = cve
        if q:      params["q"] = q
        if source: params["source"] = source
        return self.get("/kb/exploits", params=params)

    def download_exploit_zip_stream(self, exploit_id: str):
        r = self.session.get(
            f"{self.base}/kb/exploits/{exploit_id}/download-zip",
            timeout=120,
            stream=True,
        )
        r.raise_for_status()
        return r

    # ── Wordlist methods ──────────────────────────────────────────────────────

    def list_wordlist_folders(self) -> list:
        return self.get("/kb/wordlists/folders")

    def list_wordlists(self, folder_id=None, q=None, size=500) -> list:
        params: dict = {"size": size}
        if folder_id is not None: params["folderId"] = folder_id
        if q:                     params["q"] = q
        return self.get("/kb/wordlists", params=params).get("items", [])

    def get_wordlist_content_stream(self, wl_id: int):
        r = self.session.get(
            f"{self.base}/kb/wordlists/{wl_id}/content",
            timeout=120,
            stream=True,
        )
        r.raise_for_status()
        return r

    def upload_wordlist(self, file_path: Path, folder_id=None, description=None,
                        filename: str = None) -> dict:
        with open(file_path, "rb") as f:
            data: dict = {}
            if folder_id is not None: data["folderId"] = str(folder_id)
            if description:            data["description"] = description
            upload_name = filename or file_path.name
            r = self.session.post(
                f"{self.base}/kb/wordlists",
                files={"file": (upload_name, f, "text/plain")},
                data=data,
                timeout=120,
            )
        r.raise_for_status()
        return r.json()

# ── Wordlist helpers ──────────────────────────────────────────────────────────

def _build_folder_maps(folders: list) -> tuple[dict, dict]:
    """Returns (id_to_path, path_to_id) by recursively building full paths."""
    by_id = {f["id"]: f for f in folders}

    def full_path(fid: int) -> str:
        parts: list[str] = []
        cur = fid
        visited: set = set()
        while cur is not None and cur not in visited:
            visited.add(cur)
            fo = by_id.get(cur)
            if not fo:
                break
            parts.append(fo["name"])
            cur = fo.get("parentId")
        return "/".join(reversed(parts))

    id_to_path: dict = {f["id"]: full_path(f["id"]) for f in folders}
    path_to_id: dict = {v: k for k, v in id_to_path.items()}
    return id_to_path, path_to_id


def _resolve_wl(client: AresClient, wl_path: str) -> dict:
    """Resolve 'Folder/Sub/name.txt' → wordlist metadata dict. Exits on error."""
    folders = client.list_wordlist_folders()
    id_to_path, path_to_id = _build_folder_maps(folders)

    parts = wl_path.replace("\\", "/").split("/")
    name = parts[-1]
    folder_path_str = "/".join(parts[:-1])

    folder_id = None
    if folder_path_str:
        folder_id = path_to_id.get(folder_path_str)
        if folder_id is None:
            print(f"Folder not found: {folder_path_str}", file=sys.stderr)
            sys.exit(1)

    wls = client.list_wordlists(folder_id=folder_id, q=name)

    if folder_id is not None:
        matches = [w for w in wls if w["name"] == name and w.get("folderId") == folder_id]
    else:
        matches = [w for w in wls if w["name"] == name and not w.get("folderId")]

    if not matches:
        print(f"Wordlist not found: {wl_path}", file=sys.stderr)
        sys.exit(1)
    if len(matches) > 1:
        print(f"Ambiguous path '{wl_path}' — {len(matches)} matches found.", file=sys.stderr)
        sys.exit(1)

    return matches[0]


def _fmt_size(n: int | None) -> str:
    if n is None:
        return "?"
    if n >= 1_048_576:
        return f"{n / 1_048_576:.1f} MB"
    if n >= 1024:
        return f"{n // 1024} KB"
    return f"{n} B"


# ── Tag helpers ───────────────────────────────────────────────────────────────

def _resolve_or_create_tag(client: AresClient, org_id: int, name: str,
                           color: str = None, create: bool = True) -> Optional[dict]:
    """Find an org tag by name (case-insensitive); create it if missing and
    create=True. Tags are org-scoped and name-unique, so 'add' resolve-or-creates
    while 'rm' only resolves (nothing to remove from a tag that was never created)."""
    for t in client.list_tags(org_id):
        if t["name"].lower() == name.lower():
            return t
    if not create:
        return None
    try:
        return client.create_tag(org_id, name, color)
    except requests.HTTPError as e:
        if e.response.status_code == 409:
            # Race: created by someone else between our list and create — refetch.
            for t in client.list_tags(org_id):
                if t["name"].lower() == name.lower():
                    return t
        raise


# ── Exploit helpers ───────────────────────────────────────────────────────────

CVE_RE = re.compile(r"^CVE-\d{4}-\d{4,}$", re.IGNORECASE)

def _safe_filename(name: str) -> str:
    return "".join(c if c.isalnum() or c in "._-" else "_" for c in name) or "exploit"


# ── Formatting ────────────────────────────────────────────────────────────────

def print_orgs(orgs: list, fmt: str = "table"):
    if not orgs:
        print("No organizations found.")
        return
    if fmt == "json":
        print(json.dumps(orgs, indent=2))
        return
    cols = [("Slug", "slug", 20), ("Name", "name", 40), ("ID", "id", 8)]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for o in orgs:
        row = "  ".join(str(o.get(c, "")).ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(orgs)} organization(s)")

def print_projects(projects: list, fmt: str = "table"):
    if not projects:
        print("No projects found.")
        return
    if fmt == "json":
        print(json.dumps(projects, indent=2))
        return
    cols = [
        ("Code",   "code",   28),
        ("Name",   "name",   36),
        ("Status", "status", 12),
        ("ID",     "id",      8),
    ]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for p in projects:
        row = "  ".join(str(p.get(c, "")).ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(projects)} project(s)")

def print_assets(data: dict, fmt: str = "table"):
    items = data.get("items", [])
    total = data.get("total", len(items))
    if not items:
        if fmt not in ("list", "identifiers"):
            print("No assets found.")
        return

    if fmt == "json":
        print(json.dumps(items, indent=2))
        return

    if fmt in ("list", "identifiers"):
        for a in items:
            print(a.get("identifier", ""))
        return

    cols = [
        ("Code",       "code",       20),
        ("Type",       "type",       18),
        ("Identifier", "identifier", 50),
    ]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for a in items:
        row = "  ".join(str(a.get(c, "")).ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(items)} of {total} assets")

def print_detections(data: dict, fmt: str = "table"):
    items = data.get("items", [])
    total = data.get("total", len(items))
    if not items:
        if fmt not in ("list", "identifiers"):
            print("No detections found.")
        return

    if fmt == "json":
        print(json.dumps(items, indent=2))
        return

    if fmt in ("list", "identifiers"):
        for d in items:
            print(d.get("title", ""))
        return

    cols = [
        ("Sev.",   "severity",       8),
        ("Title",  "title",          45),
        ("Status", "status",         14),
        ("Asset",  "assetIdentifier", 30),
    ]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for d in items:
        row = "  ".join(str(d.get(c, "") or "").ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(items)} of {total} detection(s)")

def print_findings(data: dict, fmt: str = "table"):
    items = data.get("items", [])
    total = data.get("total", len(items))
    if not items:
        if fmt not in ("list", "identifiers"):
            print("No findings found.")
        return

    if fmt == "json":
        print(json.dumps(items, indent=2))
        return

    if fmt in ("list", "identifiers"):
        for f in items:
            print(f.get("code") or f.get("title", ""))
        return

    cols = [
        ("Code",     "code",       20),
        ("Sev.",     "severity",   8),
        ("Title",    "title",      45),
        ("Status",   "statusName", 18),
    ]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for f in items:
        row = "  ".join(str(f.get(c, "") or "").ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(items)} of {total} finding(s)")

def print_scope(entries: list, fmt: str = "table"):
    if not entries:
        if fmt != "list":
            print("No scope entries found.")
        return

    if fmt == "json":
        print(json.dumps(entries, indent=2))
        return

    if fmt == "list":
        for e in entries:
            print(e.get("value", ""))
        return

    cols = [
        ("Kind",     "kind",   12),
        ("Value",    "value",  40),
        ("In Scope", "scope",  10),
        ("Source",   "source", 14),
    ]
    header = "  ".join(label.ljust(w) for label, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for e in entries:
        row_data = {**e, "scope": "yes" if e.get("inScope") else "no"}
        row = "  ".join(str(row_data.get(c, "")).ljust(w)[:w] for _, c, w in cols)
        print(row)
    print(f"\n{len(entries)} scope entr{'y' if len(entries) == 1 else 'ies'}")

def print_exploits(data: dict, fmt: str = "table"):
    # /kb/exploits returns a Spring Data Page (content/totalElements), not the
    # items/total shape used by /assets and friends — different from print_assets.
    items = data.get("content", [])
    total = data.get("totalElements", len(items))
    if not items:
        if fmt != "list":
            print("No exploits found.")
        return

    if fmt == "json":
        print(json.dumps(items, indent=2))
        return

    if fmt == "list":
        for e in items:
            print(e.get("id", ""))
        return

    cols = [
        ("ID",     "id",     26),
        ("Title",  "title",  40),
        ("CVE(s)", "_cves",  24),
        ("Source", "sourceType", 14),
    ]
    header = "  ".join(c.ljust(w) for c, _, w in cols)
    sep    = "  ".join("-" * w for _, _, w in cols)
    print(header)
    print(sep)
    for e in items:
        e = {**e, "_cves": ", ".join(e.get("cveIds") or [])}
        row = "  ".join(str(e.get(k, "")).ljust(w)[:w] for _, k, w in cols)
        print(row)
    print(f"\n{len(items)} of {total} exploit(s)")

def print_import_result(r: dict):
    """`r` is a ScanImportDto (as returned by GET /projects/{id}/imports/{importId}) — imports
    run in the background server-side now, so this is read from a poll, not the upload's own
    (immediate, "running") response. No per-item warnings/errors list here: that detail was only
    ever visible in the upload's own one-shot response, never persisted — same as `ares imports
    list` already only ever showed the counts + a single top-level error message."""
    status = r.get("status", "unknown")
    ok = status == "completed"
    assets   = r.get("assetsCreated", 0)
    new_det  = r.get("detectionsCreated", 0)
    upd_det  = r.get("detectionsUpdated", 0)
    err_msg  = r.get("errorMessage")

    mark = "✓" if ok else ("✗" if status == "failed" else "…")
    print(f"{mark} {status}" + (f": {err_msg}" if err_msg else ""))
    print(f"  Assets created:      {assets}")
    print(f"  Detections created:  {new_det}")
    print(f"  Detections updated:  {upd_det}")

def poll_import_until_done(client: "AresClient", project_id: int, import_id: int,
                            timeout: float = 1800, interval: float = 3) -> dict:
    """Polls GET /projects/{id}/imports/{importId} (status "running" until the background
    processing flips it to "completed"/"failed") — the upload endpoint itself only ever
    returns the "running" row immediately (see ImportService#startImport on the server).
    Gives up after `timeout` seconds and returns whatever the last poll saw (still "running"),
    pointing the operator at `ares imports list` instead of hanging the CLI indefinitely."""
    deadline = time.monotonic() + timeout
    last: dict = {}
    while time.monotonic() < deadline:
        last = client.get(f"/projects/{project_id}/imports/{import_id}")
        if last.get("status") in ("completed", "failed"):
            return last
        time.sleep(interval)
    print(f"  (still running after {int(timeout)}s — check `ares imports list` for the final result)",
          file=sys.stderr)
    return last

# ── Commands ──────────────────────────────────────────────────────────────────

def cmd_configure(args):
    cfg = load_config()
    print("Ares CLI configuration\n")
    server = (getattr(args, "server", None) or "").strip()
    if not server:
        server = input(f"Server URL [{cfg.get('server', '')}]: ").strip() or cfg.get("server", "")
    if not server:
        print("Server URL is required.", file=sys.stderr); sys.exit(1)
    server = server.rstrip("/")

    if getattr(args, "manual", False):
        cmd_configure_manual(cfg, server)
    else:
        cmd_configure_browser(cfg, server)

def cmd_configure_manual(cfg: dict, server: str):
    """Paste-an-API-key fallback for headless environments (no browser available), or
    for anyone who prefers it. Selected via 'ares configure --manual'."""
    current = cfg.get("api_key", "")
    api_key = input(f"API key [{current[:12] + '...' if current else ''}]: ").strip() or current
    if not api_key:
        print("API key is required.", file=sys.stderr); sys.exit(1)
    cfg["server"]  = server
    cfg["api_key"] = api_key
    save_config(cfg)
    print(f"\nConfiguration saved to {CONFIG_PATH}")

def cmd_configure_browser(cfg: dict, server: str):
    """Opens the Ares web app so the user logs in (if needed) and approves this CLI —
    the same 'device authorization' pattern used by tools like `gh`/`heroku`. No API key
    is ever typed or pasted; the server mints one and hands it over once approved."""
    client_info = f"{platform.node()} ({platform.system()})".strip()
    try:
        r = requests.post(f"{server}/api/v1/cli/device/start", json={"clientInfo": client_info}, timeout=15)
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        print(f"Could not reach {server}: {e}", file=sys.stderr)
        print("If the server isn't reachable from a browser on this machine, try: ares configure --manual", file=sys.stderr)
        sys.exit(1)

    device_code      = data["deviceCode"]
    user_code        = data["userCode"]
    verification_url = data["verificationUrl"]
    interval         = data.get("interval", 3)
    expires_in       = data.get("expiresIn", 600)

    print(f"Confirmation code: {user_code}")
    print("(Check this matches the code shown on the page that opens in your browser.)\n")
    print("Opening your browser to authorize the CLI...")
    print(f"If it doesn't open automatically, visit:\n  {verification_url}\n")
    try:
        webbrowser.open(verification_url)
    except Exception:
        pass

    print("Waiting for authorization...", end="", flush=True)
    deadline = time.time() + expires_in
    api_key = None
    while time.time() < deadline:
        time.sleep(interval)
        try:
            r = requests.get(f"{server}/api/v1/cli/device/poll", params={"code": device_code}, timeout=15)
            r.raise_for_status()
            poll = r.json()
        except requests.RequestException:
            print(".", end="", flush=True)
            continue

        status = poll.get("status")
        if status == "approved":
            api_key = poll.get("apiKey")
            break
        if status == "denied":
            print("\nAuthorization was denied.", file=sys.stderr)
            sys.exit(1)
        if status == "expired":
            print("\nAuthorization request expired. Run 'ares configure' again.", file=sys.stderr)
            sys.exit(1)
        print(".", end="", flush=True)

    if not api_key:
        print("\nTimed out waiting for authorization. Run 'ares configure' again.", file=sys.stderr)
        sys.exit(1)

    print(" authorized.")
    cfg["server"]  = server
    cfg["api_key"] = api_key
    save_config(cfg)
    print(f"\nConfiguration saved to {CONFIG_PATH}")

def cmd_update(args, cfg: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    server = cfg["server"].rstrip("/")

    print("Checking for updates...")
    latest = fetch_server_version(server, timeout=15)
    if latest is None:
        print(f"Could not reach {server} to check for updates.", file=sys.stderr)
        sys.exit(1)

    if latest == CLI_VERSION:
        print(f"Already up to date (v{CLI_VERSION}).")
        return

    print(f"New version available: {CLI_VERSION} → {latest}")

    # Self-locate the running script. Works for the raw-script install (~/.local/bin/ares_cli.py)
    # and .deb/.rpm installs (/usr/local/bin/ares_cli.py) alike, since both just run this file
    # directly with python3 — only fails (gracefully, below) for installs this process can't
    # write to, e.g. a system-wide package install running without elevated permissions.
    script_path = os.path.realpath(__file__)
    if not (os.access(script_path, os.W_OK) and os.access(os.path.dirname(script_path), os.W_OK)):
        print(f"\n'{script_path}' isn't writable by this user, so it can't be updated in place.", file=sys.stderr)
        print("If it was installed via a package manager (.deb/.rpm) or the Windows installer,", file=sys.stderr)
        print("re-run that installer, or use sudo/an elevated shell and try again.", file=sys.stderr)
        sys.exit(1)

    if not getattr(args, "yes", False):
        answer = input("Update now? [Y/n] ").strip().lower()
        if answer not in ("", "y", "yes"):
            print("Cancelled.")
            return

    try:
        r = requests.get(f"{server}/api/v1/cli/ares_cli.py", timeout=30)
        r.raise_for_status()
    except requests.RequestException as e:
        print(f"Download failed: {e}", file=sys.stderr)
        sys.exit(1)

    # Write to a sibling temp file and rename into place — os.replace() is atomic on both
    # POSIX and Windows, so a failed/interrupted download never leaves ares_cli.py truncated
    # or half-written.
    tmp_path = script_path + ".new"
    with open(tmp_path, "wb") as f:
        f.write(r.content)
    shutil.copymode(script_path, tmp_path)
    os.replace(tmp_path, script_path)

    print(f"Updated to v{latest}. Run 'ares' again to use the new version.")

def cmd_orgs(args, cfg: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])
    try:
        orgs = client.list_orgs()
        print_orgs(orgs, fmt=getattr(args, "format", "table"))
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_projects(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    org_slug = getattr(args, "org", None) or (ctx and ctx.get("org_slug"))
    if not org_slug:
        print("--org is required.", file=sys.stderr); sys.exit(1)

    org = client.find_org(org_slug)
    if not org:
        print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)

    try:
        projects = client.list_projects(org["id"])
        print(f"Projects in {org['name']} ({org['slug']}):")
        print_projects(projects, fmt=getattr(args, "format", "table"))
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_assets(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    org_id = project_id = None

    org_slug = args.org or (ctx and ctx.get("org_slug"))
    if org_slug:
        org = client.find_org(org_slug)
        if not org:
            print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
        org_id = org["id"]

    proj_code = args.project or (ctx and ctx.get("proj_code"))
    if proj_code:
        if org_id is None:
            print("--org required when using --project.", file=sys.stderr); sys.exit(1)
        proj = client.find_project(org_id, proj_code)
        if not proj:
            print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)
        project_id = proj["id"]

    if org_id is None and project_id is None:
        print("Specify at least --org or --project.", file=sys.stderr); sys.exit(1)

    fetch_all = getattr(args, "all", False)
    q   = getattr(args, "query", None)
    aql = getattr(args, "aql", None)

    try:
        if fetch_all:
            all_items = []
            page = 0
            page_size = 200
            while True:
                data = client.list_assets(
                    org_id=org_id if not project_id else None,
                    project_id=project_id,
                    asset_type=args.type,
                    q=q, aql=aql,
                    page=page,
                    size=page_size,
                )
                items = data.get("items", [])
                all_items.extend(items)
                if len(all_items) >= data.get("total", 0) or not items:
                    break
                page += 1
            print_assets({"items": all_items, "total": len(all_items)}, fmt=args.format)
        else:
            data = client.list_assets(
                org_id=org_id if not project_id else None,
                project_id=project_id,
                asset_type=args.type,
                q=q, aql=aql,
                page=args.page,
                size=args.size,
            )
            print_assets(data, fmt=args.format)
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_detections(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    org_slug = args.org or (ctx and ctx.get("org_slug"))
    if not org_slug:
        print("--org is required.", file=sys.stderr); sys.exit(1)
    org = client.find_org(org_slug)
    if not org:
        print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)

    proj_code = args.project or (ctx and ctx.get("proj_code"))
    if not proj_code:
        # Unlike assets/findings, Detections have no org-wide view server-side —
        # every detection belongs to exactly one project's scan history.
        print("--project is required (detections are always project-scoped).", file=sys.stderr); sys.exit(1)
    proj = client.find_project(org["id"], proj_code)
    if not proj:
        print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)
    project_id = proj["id"]

    fetch_all = getattr(args, "all", False)
    try:
        if fetch_all:
            all_items = []
            page = 0
            page_size = 200
            while True:
                data = client.list_detections(
                    project_id=project_id, severity=args.severity, status=args.status,
                    source=args.source, q=args.query, aql=args.aql, page=page, size=page_size,
                )
                items = data.get("items", [])
                all_items.extend(items)
                if len(all_items) >= data.get("total", 0) or not items:
                    break
                page += 1
            print_detections({"items": all_items, "total": len(all_items)}, fmt=args.format)
        else:
            data = client.list_detections(
                project_id=project_id, severity=args.severity, status=args.status,
                source=args.source, q=args.query, aql=args.aql, page=args.page, size=args.size,
            )
            print_detections(data, fmt=args.format)
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_findings(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    org_id = project_id = None

    org_slug = args.org or (ctx and ctx.get("org_slug"))
    if org_slug:
        org = client.find_org(org_slug)
        if not org:
            print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
        org_id = org["id"]

    proj_code = args.project or (ctx and ctx.get("proj_code"))
    if proj_code:
        if org_id is None:
            print("--org required when using --project.", file=sys.stderr); sys.exit(1)
        proj = client.find_project(org_id, proj_code)
        if not proj:
            print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)
        project_id = proj["id"]

    if org_id is None and project_id is None:
        print("Specify at least --org or --project.", file=sys.stderr); sys.exit(1)

    include_drafts = getattr(args, "drafts", False)
    if include_drafts and project_id is None:
        print("--drafts requires --project (the org-wide view never includes drafts).", file=sys.stderr); sys.exit(1)

    fetch_all = getattr(args, "all", False)
    try:
        if fetch_all:
            all_items = []
            page = 0
            page_size = 200
            while True:
                data = client.list_findings(
                    org_id=org_id if not project_id else None, project_id=project_id,
                    severity=args.severity, include_drafts=include_drafts,
                    q=args.query, aql=args.aql, page=page, size=page_size,
                )
                items = data.get("items", [])
                all_items.extend(items)
                if len(all_items) >= data.get("total", 0) or not items:
                    break
                page += 1
            print_findings({"items": all_items, "total": len(all_items)}, fmt=args.format)
        else:
            data = client.list_findings(
                org_id=org_id if not project_id else None, project_id=project_id,
                severity=args.severity, include_drafts=include_drafts,
                q=args.query, aql=args.aql, page=args.page, size=args.size,
            )
            print_findings(data, fmt=args.format)
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_scope(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    org_slug = args.org or (ctx and ctx.get("org_slug"))
    if not org_slug:
        print("--org is required.", file=sys.stderr); sys.exit(1)
    proj_code = args.project or (ctx and ctx.get("proj_code"))
    if not proj_code:
        print("--project is required.", file=sys.stderr); sys.exit(1)

    org = client.find_org(org_slug)
    if not org:
        print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
    proj = client.find_project(org["id"], proj_code)
    if not proj:
        print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)

    try:
        detail = client.get_project(proj["id"])
        print_scope(detail.get("scopeEntries", []), fmt=getattr(args, "format", "table"))
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_load(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])

    file_path = Path(args.file)
    if not file_path.exists():
        print(f"File not found: {file_path}", file=sys.stderr); sys.exit(1)

    org_slug  = args.org  or (ctx and ctx.get("org_slug"))
    proj_code = args.project or (ctx and ctx.get("proj_code"))
    if not proj_code:
        print("--project is required for load.", file=sys.stderr); sys.exit(1)
    if not org_slug:
        print("--org is required for load.", file=sys.stderr); sys.exit(1)

    org = client.find_org(org_slug)
    if not org:
        print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
    proj = client.find_project(org["id"], proj_code)
    if not proj:
        print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)

    tool = args.tool
    if not tool:
        name = file_path.name.lower()
        if   "nuclei"     in name:              tool = "nuclei"
        elif "trivy"      in name:              tool = "trivy"
        elif "nmap"       in name:              tool = "nmap"
        elif "burp"       in name:              tool = "burp"
        elif "wpscan"     in name:              tool = "wpscan"
        elif "testssl"    in name:              tool = "testssl"
        elif "pingcastle" in name:              tool = "pingcastle"
        elif "subfinder"  in name:              tool = "subfinder"
        elif "httpx"      in name:              tool = "httpx"
        elif "dnsx"       in name:              tool = "dnsx"
        elif "masscan"    in name:              tool = "masscan"
        elif "naabu"      in name:              tool = "naabu"
        elif name.endswith(".xml"):             tool = "nmap"
        elif name.endswith(".json") or \
             name.endswith(".jsonl"):           tool = "nuclei"
        else:
            tools = client.list_tools(proj["id"])
            print("Available tools:")
            for i, t in enumerate(tools):
                print(f"  {i+1}. {t['displayName']} ({t['toolId']})")
            choice = input("Tool number or ID: ").strip()
            try:
                tool = tools[int(choice)-1]["toolId"]
            except (ValueError, IndexError):
                tool = choice

    print(f"Uploading {file_path.name} as '{tool}' to project {proj['code']}…")
    try:
        started = client.post_file(f"/projects/{proj['id']}/imports", file_path, tool)
    except requests.HTTPError as e:
        print(f"Upload failed: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

    # Processing happens in the background server-side (see ImportService#startImport) — the
    # upload response above is just the "running" row. Poll until it settles so this command's
    # own output still reflects the real outcome, same as before this became non-blocking.
    print("Processing…")
    result = poll_import_until_done(client, proj["id"], started["id"])
    print_import_result(result)
    if result.get("status") == "failed":
        sys.exit(1)

# ── Exploit commands ─────────────────────────────────────────────────────────

def cmd_exploits_ls(args, cfg: dict):
    client = AresClient(cfg["server"], cfg["api_key"])
    try:
        data = client.list_exploits(
            cve=getattr(args, "cve", None),
            q=getattr(args, "query", None),
            source=getattr(args, "source", None),
            page=getattr(args, "page", 0),
            size=getattr(args, "size", 50),
        )
        print_exploits(data, fmt=getattr(args, "format", "table"))
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

def cmd_exploits_pull(args, cfg: dict):
    """Download exploit(s) as zip files. Each target is either an exploit ID or a
    CVE ID (e.g. CVE-2021-44228) — CVE targets are expanded to every matching
    exploit first, so 'search by CVE and pull them' is a single command."""
    client = AresClient(cfg["server"], cfg["api_key"])
    out_dir = Path(getattr(args, "out", None) or ".")
    out_dir.mkdir(parents=True, exist_ok=True)

    targets: list[tuple[str, str]] = []  # (exploit_id, title)
    for token in args.targets:
        if CVE_RE.match(token):
            try:
                data = client.list_exploits(cve=token, size=200)
            except requests.HTTPError as e:
                print(f"Error searching {token}: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
                continue
            items = data.get("content", [])
            if not items:
                print(f"No exploits found for {token}.", file=sys.stderr)
                continue
            for it in items:
                targets.append((it["id"], it.get("title") or it["id"]))
        else:
            targets.append((token, token))

    if not targets:
        print("Nothing to pull.", file=sys.stderr)
        sys.exit(1)

    ok = 0
    for exploit_id, title in targets:
        dest = out_dir / f"{_safe_filename(title)}_{str(exploit_id)[-8:]}.zip"
        print(f"Pulling {title} ({exploit_id})…", file=sys.stderr)
        try:
            r = client.download_exploit_zip_stream(exploit_id)
            with open(dest, "wb") as f:
                for chunk in r.iter_content(chunk_size=65536):
                    f.write(chunk)
        except requests.HTTPError as e:
            print(f"  ✗ Failed: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
            continue
        ok += 1
        print(str(dest))  # stdout: one path per line, pipe-friendly

    if ok < len(targets):
        sys.exit(1)

def cmd_exploits(args, cfg: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    if getattr(args, "exploits_cmd", None) == "pull":
        cmd_exploits_pull(args, cfg)
    else:
        cmd_exploits_ls(args, cfg)

# ── Tag commands ──────────────────────────────────────────────────────────────
# Assets are the first taggable entity wired into the CLI (detections also support
# tags server-side via the same /{id}/tags/{tagId} pattern — extend cmd_tag_* with
# an entity-type flag if/when that's needed).

def _resolve_tag_asset(client: AresClient, args, ctx: dict = None) -> dict:
    org_slug = args.org or (ctx and ctx.get("org_slug"))
    project_id = None
    if args.project or (ctx and ctx.get("proj_code")):
        if not org_slug:
            print("--org is required when using --project.", file=sys.stderr); sys.exit(1)
        org = client.find_org(org_slug)
        if not org:
            print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
        proj_code = args.project or ctx.get("proj_code")
        proj = client.find_project(org["id"], proj_code)
        if not proj:
            print(f"Project '{proj_code}' not found.", file=sys.stderr); sys.exit(1)
        project_id = proj["id"]

    asset = client.find_asset(args.asset, project_id=project_id)
    if not asset:
        hint = "" if args.asset.isdigit() else " (pass --org/--project to resolve by code, or use a numeric asset ID)"
        print(f"Asset '{args.asset}' not found.{hint}", file=sys.stderr); sys.exit(1)
    return asset

def _require_tag_org(client: AresClient, args, ctx: dict = None) -> dict:
    org_slug = args.org or (ctx and ctx.get("org_slug"))
    if not org_slug:
        print("--org is required (tags are scoped to an organization).", file=sys.stderr); sys.exit(1)
    org = client.find_org(org_slug)
    if not org:
        print(f"Organization '{org_slug}' not found.", file=sys.stderr); sys.exit(1)
    return org

def cmd_tag_add(args, cfg: dict, ctx: dict = None):
    client = AresClient(cfg["server"], cfg["api_key"])
    org = _require_tag_org(client, args, ctx)
    asset = _resolve_tag_asset(client, args, ctx)
    try:
        tag = _resolve_or_create_tag(client, org["id"], args.tag, color=getattr(args, "color", None))
        updated = client.assign_asset_tag(asset["id"], tag["id"])
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr); sys.exit(1)
    names = ", ".join(t["name"] for t in updated.get("tags", []))
    print(f"✓ Tagged {asset.get('code', asset['id'])} with '{tag['name']}'. Tags: {names or '(none)'}")

def cmd_tag_rm(args, cfg: dict, ctx: dict = None):
    client = AresClient(cfg["server"], cfg["api_key"])
    org = _require_tag_org(client, args, ctx)
    asset = _resolve_tag_asset(client, args, ctx)
    tag = _resolve_or_create_tag(client, org["id"], args.tag, create=False)
    if not tag:
        print(f"Tag '{args.tag}' not found in {org['slug']}.", file=sys.stderr); sys.exit(1)
    try:
        updated = client.unassign_asset_tag(asset["id"], tag["id"])
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr); sys.exit(1)
    names = ", ".join(t["name"] for t in updated.get("tags", []))
    print(f"✓ Removed '{tag['name']}' from {asset.get('code', asset['id'])}. Tags: {names or '(none)'}")

def cmd_tag_ls(args, cfg: dict, ctx: dict = None):
    client = AresClient(cfg["server"], cfg["api_key"])
    asset = _resolve_tag_asset(client, args, ctx)
    tags = asset.get("tags", [])
    if not tags:
        print("No tags.")
        return
    for t in tags:
        print(t["name"])

def cmd_tag(args, cfg: dict = None, ctx: dict = None):
    if cfg is None: cfg = load_config()
    require_config(cfg)
    dispatch = {"add": cmd_tag_add, "rm": cmd_tag_rm, "ls": cmd_tag_ls}
    fn = dispatch.get(getattr(args, "tag_cmd", None))
    if fn:
        fn(args, cfg, ctx=ctx)
    else:
        print("Usage: ares tag {add,rm,ls} <asset> [<tag>] …\nRun 'ares tag --help' for details.")

# ── Wordlist commands ─────────────────────────────────────────────────────────

def cmd_wl_ls(args, cfg: dict):
    """List wordlists available on the server."""
    client = AresClient(cfg["server"], cfg["api_key"])
    try:
        folders   = client.list_wordlist_folders()
        id_to_path, path_to_id = _build_folder_maps(folders)

        folder_id_filter = None
        if getattr(args, "folder", None):
            folder_id_filter = path_to_id.get(args.folder)
            if folder_id_filter is None:
                print(f"Folder not found: {args.folder}", file=sys.stderr)
                sys.exit(1)

        wordlists = client.list_wordlists(
            folder_id=folder_id_filter,
            q=getattr(args, "query", None),
        )

        if not wordlists:
            print("No wordlists found.")
            return

        fmt = getattr(args, "format", "table")
        if fmt == "json":
            print(json.dumps(wordlists, indent=2))
            return

        # Table output
        PATH_W, SIZE_W, LINES_W = 60, 10, 10
        header = f"{'Path':<{PATH_W}}  {'Size':>{SIZE_W}}  {'Lines':>{LINES_W}}"
        sep    = f"{'-'*PATH_W}  {'-'*SIZE_W}  {'-'*LINES_W}"
        print(header)
        print(sep)
        for w in sorted(wordlists, key=lambda x: x.get("name", "")):
            fp = id_to_path.get(w.get("folderId"), "")
            full = f"{fp}/{w['name']}" if fp else w["name"]
            size_s  = _fmt_size(w.get("sizeBytes"))
            lines_s = str(w["lineCount"]) if w.get("lineCount") is not None else "?"
            trunc = full[:PATH_W] if len(full) <= PATH_W else "…" + full[-(PATH_W-1):]
            print(f"{trunc:<{PATH_W}}  {size_s:>{SIZE_W}}  {lines_s:>{LINES_W}}")
        print(f"\n{len(wordlists)} wordlist(s)")
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)


def cmd_wl_use(args, cfg: dict):
    """Download wordlist to local cache and print the local file path.

    stdout contains ONLY the path — suitable for shell command substitution:
        ffuf -w $(ares wl use SecLists/Passwords/common.txt) …
    Progress/info is written to stderr so it never pollutes the substitution.
    """
    client = AresClient(cfg["server"], cfg["api_key"])
    try:
        wl = _resolve_wl(client, args.path)
    except SystemExit:
        raise
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)

    wl_id  = wl["id"]
    sha256 = wl.get("sha256") or ""
    name   = wl["name"]

    WL_CACHE_DIR.mkdir(parents=True, exist_ok=True)

    # Check cache index
    idx   = _load_wl_index()
    entry = idx.get(str(wl_id))
    if entry and entry.get("sha256") == sha256 and sha256:
        cached = Path(entry["path"])
        if cached.exists():
            # Cache hit — print path only, nothing to stderr
            print(str(cached))
            return

    # Cache miss or stale — download
    safe_name = "".join(c if c.isalnum() or c in "._-" else "_" for c in name)
    key       = sha256[:12] if sha256 else str(wl_id)
    dest      = WL_CACHE_DIR / f"{key}_{safe_name}"

    size_hint = _fmt_size(wl.get("sizeBytes"))
    print(f"Downloading {name} ({size_hint})…", file=sys.stderr)

    try:
        r = client.get_wordlist_content_stream(wl_id)
        with open(dest, "wb") as f:
            for chunk in r.iter_content(chunk_size=65536):
                f.write(chunk)
    except requests.HTTPError as e:
        print(f"Download failed: {e.response.status_code}", file=sys.stderr)
        sys.exit(1)

    # Update cache index
    idx[str(wl_id)] = {"sha256": sha256, "path": str(dest), "name": name}
    _save_wl_index(idx)

    print(f"Cached to {dest}", file=sys.stderr)
    print(str(dest))   # stdout: path only


def cmd_wl_cat(args, cfg: dict):
    """Stream wordlist contents directly to stdout — suitable for piping."""
    client = AresClient(cfg["server"], cfg["api_key"])
    try:
        wl = _resolve_wl(client, args.path)
        r  = client.get_wordlist_content_stream(wl["id"])
        for chunk in r.iter_content(chunk_size=65536):
            sys.stdout.buffer.write(chunk)
        sys.stdout.buffer.flush()
    except requests.HTTPError as e:
        print(f"Error: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)


def cmd_wl_push(args, cfg: dict):
    """Upload a local file (or stdin via '-') as a new wordlist."""
    client = AresClient(cfg["server"], cfg["api_key"])

    # Resolve destination folder (folder path only — e.g. "SecLists/Passwords")
    folder_id = None
    if getattr(args, "folder", None):
        folder_arg = args.folder.rstrip("/")  # normalise trailing slash
        try:
            folders = client.list_wordlist_folders()
        except requests.HTTPError as e:
            print(f"Error fetching folders: {e.response.status_code}", file=sys.stderr)
            sys.exit(1)
        _, path_to_id = _build_folder_maps(folders)
        folder_id = path_to_id.get(folder_arg)
        if folder_id is None:
            print(f"Folder not found: '{folder_arg}'", file=sys.stderr)
            print("Tip: --folder should be the folder path only (e.g. 'SecLists/Passwords'), not the full file path.", file=sys.stderr)
            print("     Folder names are case-sensitive and use '/' as separator.", file=sys.stderr)
            sys.exit(1)

    import datetime as _dt
    tmp_path: Optional[Path] = None
    try:
        explicit_name = getattr(args, "filename", None) or None
        if args.file == "-":
            # Read from stdin into a temporary file
            print("Reading from stdin…", file=sys.stderr)
            with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as tmp:
                tmp.write(sys.stdin.buffer.read())
                tmp_path = Path(tmp.name)
            file_path = tmp_path
            # Default name for stdin: wordlist-YYYYMMDD-HHMMSS.txt
            upload_name = explicit_name or f"wordlist-{_dt.datetime.now().strftime('%Y%m%d-%H%M%S')}.txt"
        else:
            file_path = Path(args.file)
            if not file_path.exists():
                print(f"File not found: {file_path}", file=sys.stderr)
                sys.exit(1)
            upload_name = explicit_name or file_path.name

        print(f"Uploading as '{upload_name}'…", file=sys.stderr)
        result = client.upload_wordlist(
            file_path,
            folder_id=folder_id,
            description=getattr(args, "description", None),
            filename=upload_name,
        )
        lines = result.get("lineCount")
        lines_s = str(lines) if lines is not None else "?"
        size_s  = _fmt_size(result.get("sizeBytes"))
        print(f"✓ Uploaded: {result['name']} ({lines_s} lines, {size_s})")
    except requests.HTTPError as e:
        print(f"Upload failed: {e.response.status_code} {e.response.text[:200]}", file=sys.stderr)
        sys.exit(1)
    finally:
        if tmp_path and tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def cmd_wl(args, cfg: dict = None):
    """Dispatcher for the 'wl' top-level subcommand."""
    if cfg is None: cfg = load_config()
    require_config(cfg)
    dispatch = {
        "ls":   cmd_wl_ls,
        "use":  cmd_wl_use,
        "cat":  cmd_wl_cat,
        "push": cmd_wl_push,
    }
    wl_cmd = getattr(args, "wl_cmd", None)
    fn = dispatch.get(wl_cmd)
    if fn:
        fn(args, cfg)
    else:
        print("Usage: ares wl {ls,use,cat,push} …\nRun 'ares wl --help' for details.")

# ── Interactive CLI ───────────────────────────────────────────────────────────

HELP_TEXT = """
Commands:
  orgs                  List all organizations
  projects              List projects in the current organization
  org   <SLUG>          Set current organization
  proj  <CODE>          Set current project (org must be set first)
  assets [--type TYPE] [-q QUERY] [--aql QUERY] [--size N] [--page N] [--format table|json]
                        List assets in current context
  detections [--severity SEV] [--status ST] [-q QUERY] [--aql QUERY] [--size N] [--page N]
                        List detections in current project
  findings [--severity SEV] [--drafts] [-q QUERY] [--aql QUERY] [--size N] [--page N]
                        List findings in current context
  scope [--format table|json|list]
                        List scope entries for current project
  load  <FILE> [--tool TOOL]
                        Upload scan file to current project
  wl ls [--folder PATH] [-q QUERY] [--format table|json]
                        List wordlists on the server
  wl use  <PATH>        Cache wordlist locally, print local path
  wl cat  <PATH>        Stream wordlist contents to stdout
  wl push <FILE|-> [--folder PATH] [-d DESC]
                        Upload a local file (or stdin) as a wordlist
  exploits [--cve ID] [-q QUERY] [--source SRC] [--format table|json|list]
                        Search exploits/PoCs
  exploits pull <ID|CVE>... [--out DIR]
                        Download exploit(s) as zip — by exploit ID or CVE ID
                        (a CVE ID pulls every matching exploit)
  tag add <ASSET> <NAME> [--color HEX]
                        Tag an asset (creates the tag if it doesn't exist)
  tag rm  <ASSET> <NAME>
                        Remove a tag from an asset
  tag ls  <ASSET>       List tags on an asset
  update [-y]            Check for and install CLI updates
  version                Show the installed CLI version
  context               Show current org/project
  clear                 Clear org/project context
  help                  Show this help
  exit / quit           Exit the CLI
"""

def make_prompt(ctx: dict) -> str:
    parts = []
    if ctx.get("org_slug"):  parts.append(ctx["org_slug"])
    if ctx.get("proj_code"): parts.append(ctx["proj_code"])
    label = "/".join(parts) if parts else ""
    return f"ares [{label}] > " if label else "ares > "

def run_cli_command(line: str, cfg: dict, ctx: dict, client: AresClient):
    tokens = line.strip().split()
    if not tokens: return

    cmd, rest = tokens[0].lower(), tokens[1:]

    if cmd in ("exit", "quit"):
        print("Goodbye.")
        sys.exit(0)

    elif cmd == "help":
        print(HELP_TEXT)

    elif cmd == "orgs":
        p = argparse.ArgumentParser(prog="orgs", add_help=False, exit_on_error=False)
        p.add_argument("--format", default="table", choices=["table", "json"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            a = argparse.Namespace(format="table")
        try:
            cmd_orgs(a, cfg=cfg)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd in ("projects", "projs"):
        p = argparse.ArgumentParser(prog="projects", add_help=False, exit_on_error=False)
        p.add_argument("--format", default="table", choices=["table", "json"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            a = argparse.Namespace(format="table")
        if not ctx.get("org_slug"):
            print("Set an org first: org <SLUG>"); return
        a.org = ctx.get("org_slug")
        try:
            cmd_projects(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "context":
        print(f"  org:     {ctx.get('org_slug') or '(none)'}")
        print(f"  project: {ctx.get('proj_code') or '(none)'}")

    elif cmd == "clear":
        ctx.clear()
        print("Context cleared.")

    elif cmd == "org":
        if not rest: print("Usage: org <SLUG>"); return
        slug = rest[0].upper()
        try:
            org = client.find_org(slug)
            if not org: print(f"Organization '{slug}' not found."); return
            ctx["org_slug"] = org["slug"].upper()
            ctx["org_id"]   = org["id"]
            ctx.pop("proj_code", None)
            ctx.pop("proj_id", None)
            print(f"Organization set: {org['name']}")
        except Exception as e:
            print(f"Error: {e}")

    elif cmd in ("proj", "project"):
        if not rest: print("Usage: proj <CODE>"); return
        if not ctx.get("org_id"): print("Set an org first: org <SLUG>"); return
        code = rest[0].upper()
        try:
            proj = client.find_project(ctx["org_id"], code)
            if not proj: print(f"Project '{code}' not found."); return
            ctx["proj_code"] = proj["code"].upper()
            ctx["proj_id"]   = proj["id"]
            print(f"Project set: {proj['name']} ({proj['code']})")
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "assets":
        p = argparse.ArgumentParser(prog="assets", add_help=False, exit_on_error=False)
        p.add_argument("--type",   default=None)
        p.add_argument("-q", "--query", default=None)
        p.add_argument("--aql",    default=None)
        p.add_argument("--size",   type=int, default=50)
        p.add_argument("--page",   type=int, default=0)
        p.add_argument("--format", default="table", choices=["table", "json", "list"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            print("Usage: assets [--type TYPE] [-q QUERY] [--aql QUERY] [--size N] [--page N] [--format table|json|list]"); return
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_assets(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "detections":
        p = argparse.ArgumentParser(prog="detections", add_help=False, exit_on_error=False)
        p.add_argument("--severity", action="append", default=None)
        p.add_argument("--status",   action="append", default=None)
        p.add_argument("--source",   action="append", default=None)
        p.add_argument("-q", "--query", default=None)
        p.add_argument("--aql",      default=None)
        p.add_argument("--size",     type=int, default=50)
        p.add_argument("--page",     type=int, default=0)
        p.add_argument("--format",   default="table", choices=["table", "json", "list"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            print("Usage: detections [--severity SEV] [--status ST] [-q QUERY] [--aql QUERY] [--size N] [--page N] [--format table|json|list]"); return
        if not ctx.get("org_slug") or not ctx.get("proj_code"):
            print("Set an org and project first: org <SLUG> / proj <CODE>"); return
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_detections(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "findings":
        p = argparse.ArgumentParser(prog="findings", add_help=False, exit_on_error=False)
        p.add_argument("--severity", action="append", default=None)
        p.add_argument("--drafts",   action="store_true")
        p.add_argument("-q", "--query", default=None)
        p.add_argument("--aql",      default=None)
        p.add_argument("--size",     type=int, default=50)
        p.add_argument("--page",     type=int, default=0)
        p.add_argument("--format",   default="table", choices=["table", "json", "list"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            print("Usage: findings [--severity SEV] [--drafts] [-q QUERY] [--aql QUERY] [--size N] [--page N] [--format table|json|list]"); return
        if not ctx.get("org_slug"):
            print("Set an org first: org <SLUG>"); return
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_findings(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "scope":
        p = argparse.ArgumentParser(prog="scope", add_help=False, exit_on_error=False)
        p.add_argument("--format", default="table", choices=["table", "json", "list"])
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            print("Usage: scope [--format table|json|list]"); return
        if not ctx.get("proj_code"):
            print("Set a project first: proj <CODE>"); return
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_scope(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "load":
        if not rest: print("Usage: load <FILE> [--tool TOOL]"); return
        p = argparse.ArgumentParser(prog="load", add_help=False, exit_on_error=False)
        p.add_argument("file")
        p.add_argument("--tool", default=None)
        try:
            a = p.parse_args(rest)
        except (argparse.ArgumentError, SystemExit):
            print("Usage: load <FILE> [--tool TOOL]"); return
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_load(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "exploits":
        if rest and rest[0].lower() == "pull":
            p = argparse.ArgumentParser(prog="exploits pull", add_help=False, exit_on_error=False)
            p.add_argument("targets", nargs="+")
            p.add_argument("--out", default=".")
            try:
                a = p.parse_args(rest[1:])
            except (argparse.ArgumentError, SystemExit):
                print("Usage: exploits pull <ID|CVE>... [--out DIR]"); return
            a.exploits_cmd = "pull"
            try:
                cmd_exploits(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")
        else:
            p = argparse.ArgumentParser(prog="exploits", add_help=False, exit_on_error=False)
            p.add_argument("--cve", default=None)
            p.add_argument("-q", "--query", default=None)
            p.add_argument("--source", action="append", default=None)
            p.add_argument("--size", type=int, default=50)
            p.add_argument("--page", type=int, default=0)
            p.add_argument("--format", default="table", choices=["table", "json", "list"])
            try:
                a = p.parse_args(rest)
            except (argparse.ArgumentError, SystemExit):
                print("Usage: exploits [--cve ID] [-q QUERY] [--source SRC] [--format table|json|list]"); return
            a.exploits_cmd = None
            try:
                cmd_exploits(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")

    elif cmd == "tag":
        if not rest or rest[0].lower() not in ("add", "rm", "ls"):
            print("Usage: tag {add,rm,ls} <asset> [<tag>] …  (type 'help' for details)")
            return
        tag_cmd, sub_rest = rest[0].lower(), rest[1:]
        p = argparse.ArgumentParser(prog=f"tag {tag_cmd}", add_help=False, exit_on_error=False)
        p.add_argument("asset")
        if tag_cmd in ("add", "rm"):
            p.add_argument("tag")
        if tag_cmd == "add":
            p.add_argument("--color", default=None)
        try:
            a = p.parse_args(sub_rest)
        except (argparse.ArgumentError, SystemExit):
            print(f"Usage: tag {tag_cmd} <asset>" + (" <tag>" if tag_cmd in ('add', 'rm') else "")); return
        a.tag_cmd = tag_cmd
        a.org = ctx.get("org_slug"); a.project = ctx.get("proj_code")
        try:
            cmd_tag(a, cfg=cfg, ctx=ctx)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "wl":
        if not rest:
            print("Usage: wl {ls,use,cat,push} <args>  (type 'help' for details)")
            return
        sub_cmd  = rest[0].lower()
        sub_rest = rest[1:]

        if sub_cmd == "ls":
            p = argparse.ArgumentParser(prog="wl ls", add_help=False, exit_on_error=False)
            p.add_argument("--folder", default=None)
            p.add_argument("-q", "--query", default=None)
            p.add_argument("--format", default="table", choices=["table", "json"])
            try:
                a = p.parse_args(sub_rest)
            except (argparse.ArgumentError, SystemExit):
                a = argparse.Namespace(folder=None, query=None, format="table")
            try:
                cmd_wl_ls(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")

        elif sub_cmd == "use":
            if not sub_rest: print("Usage: wl use <path>"); return
            a = argparse.Namespace(path=sub_rest[0])
            try:
                cmd_wl_use(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")

        elif sub_cmd == "cat":
            if not sub_rest: print("Usage: wl cat <path>"); return
            a = argparse.Namespace(path=sub_rest[0])
            try:
                cmd_wl_cat(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")

        elif sub_cmd == "push":
            if not sub_rest: print("Usage: wl push <file|-> [--folder PATH] [-d DESC]"); return
            p = argparse.ArgumentParser(prog="wl push", add_help=False, exit_on_error=False)
            p.add_argument("file")
            p.add_argument("--folder",   default=None)
            p.add_argument("--filename", default=None)
            p.add_argument("-d", "--description", default=None)
            try:
                a = p.parse_args(sub_rest)
            except (argparse.ArgumentError, SystemExit):
                print("Usage: wl push <file|-> [--folder PATH] [--filename NAME] [-d DESC]"); return
            try:
                cmd_wl_push(a, cfg=cfg)
            except SystemExit:
                pass
            except Exception as e:
                print(f"Error: {e}")

        else:
            print(f"Unknown wl subcommand: '{sub_cmd}'. Available: ls, use, cat, push")

    elif cmd == "update":
        a = argparse.Namespace(yes="-y" in rest or "--yes" in rest)
        try:
            cmd_update(a, cfg=cfg)
        except SystemExit:
            pass
        except Exception as e:
            print(f"Error: {e}")

    elif cmd == "version":
        print(f"ares-cli {CLI_VERSION}")

    else:
        print(f"Unknown command: {cmd}. Type 'help' for available commands.")

def cmd_interactive(cfg: dict):
    require_config(cfg)
    client = AresClient(cfg["server"], cfg["api_key"])
    ctx: dict = {}

    print("Ares CLI — type 'help' for commands, 'exit' to quit.")
    try:
        import readline
        readline.set_history_length(200)
    except ImportError:
        pass

    while True:
        try:
            line = input(make_prompt(ctx))
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye.")
            break
        run_cli_command(line, cfg, ctx, client)

# ── Entry point ───────────────────────────────────────────────────────────────

def add_assets_args(p):
    p.add_argument("--org",     help="Organization slug")
    p.add_argument("--project", help="Project code")
    p.add_argument("--type",    help="Filter by asset type (host, ip, service, domain, …)")
    p.add_argument("-q", "--query", default=None, help="Free-text search (identifier/name)")
    p.add_argument("--aql", default=None,
                   help="Ares Query Language filter — overrides --type/-q rather than combining "
                        "with them (e.g. \"type == host AND metadata.osVersion ~= Ubuntu\")")
    p.add_argument("--size",    type=int, default=50, help="Page size (default 50)")
    p.add_argument("--page",    type=int, default=0,  help="Page number (default 0)")
    p.add_argument("--all",     action="store_true",  help="Fetch all pages (useful with --format list)")
    p.add_argument("--format",  default="table", choices=["table", "json", "list"],
                   help="Output format: table (default), json, or list (one identifier per line)")

def add_load_args(p):
    p.add_argument("file",      help="Path to the scan output file")
    p.add_argument("--org",     help="Organization slug")
    p.add_argument("--project", help="Project code (required)")
    p.add_argument("--tool",    help="Tool ID override (nuclei, nmap, burp, …)")

def main():
    parser = argparse.ArgumentParser(
        prog="ares",
        description="Ares ASM CLI",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=textwrap.dedent("""\
            Examples:
              ares --version
              ares configure
              ares cli
              ares assets --org ACME --project ACME-ASSESS-26-1 --type host
              ares scope --org ACME --project ACME-ASSESS-26-1
              ares load scan.jsonl --org ACME --project ACME-ASSESS-26-1
              ares wl ls
              ares wl ls --folder "SecLists/Passwords" -q "common"
              ares wl use "SecLists/Discovery/Web-Content/common.txt"
              ffuf -w $(ares wl use "SecLists/Discovery/Web-Content/common.txt") -u https://example.com/FUZZ
              ares wl cat "SecLists/Passwords/common.txt" | wc -l
              ares wl push ./custom.txt --folder "Custom" -d "My list"
              cat generated.txt | ares wl push - --folder "Custom"
              ares exploits --cve CVE-2021-44228
              ares exploits pull CVE-2021-44228 --out ./poc
              ares tag add ACME-HOST-1 internet-facing --org ACME --project ACME-ASSESS-26-1
              ares tag ls ACME-HOST-1 --org ACME --project ACME-ASSESS-26-1
              ares update
        """),
    )
    parser.add_argument("-V", "--version", action="version", version=f"ares-cli {CLI_VERSION}")
    sub = parser.add_subparsers(dest="command")

    p_configure = sub.add_parser("configure", help="Log in via your browser and save the server URL + API key")
    p_configure.add_argument("--server", help="Server URL (skips the prompt)")
    p_configure.add_argument("--manual", action="store_true", help="Paste an API key manually instead of opening a browser")

    p_orgs = sub.add_parser("orgs", help="List organizations")
    p_orgs.add_argument("--format", default="table", choices=["table", "json"])

    p_proj = sub.add_parser("projects", help="List projects in an organization")
    p_proj.add_argument("--org",    required=True, help="Organization slug")
    p_proj.add_argument("--format", default="table", choices=["table", "json"])

    add_assets_args(sub.add_parser("assets", help="List assets"))

    # ── detections subcommand ────────────────────────────────────────────────
    p_detections = sub.add_parser("detections", help="List detections in a project")
    p_detections.add_argument("--org",     required=True, help="Organization slug")
    p_detections.add_argument("--project", required=True, help="Project code")
    p_detections.add_argument("--severity", action="append", default=None,
                              help="Filter by severity (repeatable): critical, high, medium, low, info")
    p_detections.add_argument("--status", action="append", default=None,
                              help="Filter by status (repeatable): new, affected, not_affected, "
                                   "ignored, out_of_scope, fixed, reopened, archived")
    p_detections.add_argument("--source", action="append", default=None, help="Filter by source type (repeatable)")
    p_detections.add_argument("-q", "--query", default=None, help="Free-text search (title/description)")
    p_detections.add_argument("--aql", default=None,
                              help="Ares Query Language filter — overrides --severity/--status/--source/-q "
                                   "rather than combining with them (e.g. \"priority == P0 AND status == new\")")
    p_detections.add_argument("--size", type=int, default=50, help="Page size (default 50)")
    p_detections.add_argument("--page", type=int, default=0,  help="Page number (default 0)")
    p_detections.add_argument("--all",  action="store_true",  help="Fetch all pages (useful with --format list)")
    p_detections.add_argument("--format", default="table", choices=["table", "json", "list"],
                              help="Output format: table (default), json, or list (one title per line)")

    # ── findings subcommand ──────────────────────────────────────────────────
    p_findings = sub.add_parser("findings", help="List findings")
    p_findings.add_argument("--org",     help="Organization slug")
    p_findings.add_argument("--project", help="Project code")
    p_findings.add_argument("--severity", action="append", default=None,
                            help="Filter by severity (repeatable): critical, high, medium, low, info")
    p_findings.add_argument("--drafts", action="store_true",
                            help="Include draft findings (requires --project — the org-wide view never includes drafts)")
    p_findings.add_argument("-q", "--query", default=None, help="Free-text search (title/code)")
    p_findings.add_argument("--aql", default=None,
                            help="Ares Query Language filter — overrides --severity/-q rather than "
                                 "combining with them (e.g. \"priority == P0 AND status == abierto\")")
    p_findings.add_argument("--size", type=int, default=50, help="Page size (default 50)")
    p_findings.add_argument("--page", type=int, default=0,  help="Page number (default 0)")
    p_findings.add_argument("--all",  action="store_true",  help="Fetch all pages (useful with --format list)")
    p_findings.add_argument("--format", default="table", choices=["table", "json", "list"],
                            help="Output format: table (default), json, or list (one code per line)")

    p_scope = sub.add_parser("scope", help="List a project's scope entries")
    p_scope.add_argument("--org",     required=True, help="Organization slug")
    p_scope.add_argument("--project", required=True, help="Project code")
    p_scope.add_argument("--format",  default="table", choices=["table", "json", "list"],
                         help="Output format: table (default), json, or list (one value per line)")

    add_load_args  (sub.add_parser("load",   help="Upload a scan file"))

    # ── exploits subcommand ─────────────────────────────────────────────────
    # NOTE: --cve/-q/--source are discrete named filters, not a raw query string —
    # kept that way deliberately so a future `--aql "..."` flag (Ares Query
    # Language) can be added as an alternative filtering mode later without
    # having to redesign this. Same convention as 'assets' --type/-q.
    p_exploits = sub.add_parser("exploits", help="Search exploits/PoCs, or pull them locally")
    p_exploits.add_argument("--cve",    default=None, help="Filter by CVE ID (e.g. CVE-2021-44228)")
    p_exploits.add_argument("-q", "--query", default=None, help="Free-text search (title/description)")
    p_exploits.add_argument("--source", action="append", default=None,
                            help="Filter by source (repeatable): manual_git, manual_upload, exploitdb, vulncheck_xdb")
    p_exploits.add_argument("--size",   type=int, default=50, help="Page size (default 50)")
    p_exploits.add_argument("--page",   type=int, default=0,  help="Page number (default 0)")
    p_exploits.add_argument("--format", default="table", choices=["table", "json", "list"])
    exploits_sub = p_exploits.add_subparsers(dest="exploits_cmd")
    p_exploits_pull = exploits_sub.add_parser(
        "pull",
        help="Download exploit(s) as zip — by exploit ID or CVE ID",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Examples:\n"
               "  ares exploits pull CVE-2021-44228          # pulls every exploit for this CVE\n"
               "  ares exploits pull 65f1a2b3c4d5e6f7 --out ./poc",
    )
    p_exploits_pull.add_argument("targets", nargs="+", help="Exploit ID(s) and/or CVE ID(s)")
    p_exploits_pull.add_argument("--out", default=".", help="Output directory (default: current directory)")

    # ── tag subcommand ───────────────────────────────────────────────────────
    p_tag = sub.add_parser("tag", help="Manage tags on assets")
    tag_sub = p_tag.add_subparsers(dest="tag_cmd")

    p_tag_add = tag_sub.add_parser("add", help="Tag an asset (creates the tag if it doesn't exist yet)")
    p_tag_add.add_argument("asset", help="Asset code (needs --org/--project) or numeric asset ID")
    p_tag_add.add_argument("tag",   help="Tag name")
    p_tag_add.add_argument("--org",     help="Organization slug")
    p_tag_add.add_argument("--project", help="Project code")
    p_tag_add.add_argument("--color",   default=None, help="Hex color for a newly-created tag (e.g. #FF5733)")

    p_tag_rm = tag_sub.add_parser("rm", help="Remove a tag from an asset")
    p_tag_rm.add_argument("asset", help="Asset code (needs --org/--project) or numeric asset ID")
    p_tag_rm.add_argument("tag",   help="Tag name")
    p_tag_rm.add_argument("--org",     help="Organization slug")
    p_tag_rm.add_argument("--project", help="Project code")

    p_tag_ls = tag_sub.add_parser("ls", help="List tags on an asset")
    p_tag_ls.add_argument("asset", help="Asset code (needs --org/--project) or numeric asset ID")
    p_tag_ls.add_argument("--org",     help="Organization slug")
    p_tag_ls.add_argument("--project", help="Project code")

    # ── wl subcommand ────────────────────────────────────────────────────────
    p_wl  = sub.add_parser("wl", help="Wordlist management (ls, use, cat, push)")
    wl_sub = p_wl.add_subparsers(dest="wl_cmd")

    # wl ls
    p_wl_ls = wl_sub.add_parser("ls", help="List wordlists on the server")
    p_wl_ls.add_argument("--folder",  default=None, help="Filter by folder path (e.g. SecLists/Passwords)")
    p_wl_ls.add_argument("-q", "--query", default=None, help="Search wordlists by name")
    p_wl_ls.add_argument("--format",  default="table", choices=["table", "json"])

    # wl use
    p_wl_use = wl_sub.add_parser(
        "use",
        help="Download wordlist to local cache and print path (for command substitution)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Example:\n  ffuf -w $(ares wl use SecLists/Passwords/common.txt) -u https://example.com/FUZZ",
    )
    p_wl_use.add_argument("path", help="Server path, e.g. SecLists/Passwords/common.txt")

    # wl cat
    p_wl_cat = wl_sub.add_parser("cat", help="Stream wordlist contents to stdout")
    p_wl_cat.add_argument("path", help="Server path, e.g. SecLists/Passwords/common.txt")

    # wl push
    p_wl_push = wl_sub.add_parser("push", help="Upload a local file (or stdin via '-') as a wordlist")
    p_wl_push.add_argument("file", help="Local file path, or '-' to read from stdin")
    p_wl_push.add_argument("--folder",   default=None, help="Destination folder path on the server (folder name/path only, e.g. 'SecLists/Passwords' — do not include the filename)")
    p_wl_push.add_argument("--filename", default=None, help="Override the filename stored on the server (required for stdin to get a meaningful name)")
    p_wl_push.add_argument("-d", "--description", default=None, help="Wordlist description")

    p_update = sub.add_parser("update", help="Check for and install CLI updates")
    p_update.add_argument("-y", "--yes", action="store_true", help="Don't prompt for confirmation")

    sub.add_parser("version", help="Show the installed CLI version")

    sub.add_parser("cli", help="Start interactive shell")

    args = parser.parse_args()
    if not args.command:
        parser.print_help(); sys.exit(0)

    cfg = load_config()

    # Skipped for 'configure' (nothing configured yet), 'update' (already does its own
    # explicit, unthrottled version check), and 'version' (meant to be instant/local).
    if args.command not in ("configure", "update", "version"):
        check_for_update(cfg)

    if   args.command == "configure": cmd_configure(args)
    elif args.command == "orgs":      cmd_orgs(args, cfg=cfg)
    elif args.command == "projects":  cmd_projects(args, cfg=cfg)
    elif args.command == "assets":    cmd_assets(args, cfg=cfg)
    elif args.command == "detections": cmd_detections(args, cfg=cfg)
    elif args.command == "findings":  cmd_findings(args, cfg=cfg)
    elif args.command == "scope":     cmd_scope(args, cfg=cfg)
    elif args.command == "load":      cmd_load(args, cfg=cfg)
    elif args.command == "exploits":  cmd_exploits(args, cfg=cfg)
    elif args.command == "tag":       cmd_tag(args, cfg=cfg)
    elif args.command == "wl":        cmd_wl(args, cfg=cfg)
    elif args.command == "update":    cmd_update(args, cfg=cfg)
    elif args.command == "version":   print(f"ares-cli {CLI_VERSION}")
    elif args.command == "cli":       cmd_interactive(cfg)

if __name__ == "__main__":
    main()
