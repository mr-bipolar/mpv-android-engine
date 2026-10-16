
#!/usr/bin/env python3
import os
import re
import shutil
import subprocess
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent
NAMES_FILE = SCRIPT_DIR / "names.txt"

MPV_SOURCE_DIR = Path(
    os.environ.get(
        "MPV_SOURCE_DIR",
        str(REPO_ROOT.parent / "ref-mpv")
    )
).expanduser().resolve()

DOCS_DIR = MPV_SOURCE_DIR / "DOCS" / "man"

if not NAMES_FILE.is_file():
    raise SystemExit(f"names.txt not found: {NAMES_FILE}")

if not DOCS_DIR.is_dir():
    raise SystemExit(
        f"MPV documentation not found: {DOCS_DIR}\n"
        "Set MPV_SOURCE_DIR to your MPV source checkout."
    )

if shutil.which("mpv") is None:
    raise SystemExit("Desktop mpv was not found on PATH.")

def read_doc(name):
    path = DOCS_DIR / name
    if not path.is_file():
        raise SystemExit(f"Documentation file not found: {path}")
    return path.read_text(encoding="utf-8", errors="replace")

opts = read_doc("options.rst")
inp = read_doc("input.rst")
commands_doc = read_doc("commands.rst")

def run_mpv(args):
    result = subprocess.run(
        ["mpv", "--no-config", *args],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SystemExit(
            f"mpv command failed: {' '.join(args)}\n"
            f"{result.stderr.strip()}"
        )
    return result.stdout

desktop_options = run_mpv(["--list-options"])
desktop_properties = set(run_mpv(["--list-properties"]).split())
option_names = set(
    re.findall(r"^\s*--([a-z0-9-]+)", desktop_options, re.M)
)

rows = []

with NAMES_FILE.open(encoding="utf-8") as file:
    for line_no, line in enumerate(file, 1):
        line = line.strip()

        if not line or line.startswith("#"):
            continue

        parts = line.split()
        if len(parts) != 2:
            raise SystemExit(
                f"{NAMES_FILE}:{line_no}: expected 'kind name'"
            )

        kind, name = parts

        if kind == "opt":
            master = (
                f"``--{name}" in opts
                or f"--{name}=" in opts
            )
            local = name in option_names

        elif kind == "prop":
            base_name = name.split("/", 1)[0]
            master = (
                f"``{name}``" in inp
                or bool(re.search(
                    rf"^``{re.escape(name)}(?:/|``| )",
                    inp,
                    re.M,
                ))
                or bool(re.search(
                    rf"^``{re.escape(base_name)}/",
                    inp,
                    re.M,
                ))
            )
            local = (
                name in desktop_properties
                or name in option_names
            )

        elif kind in ("cmd", "command"):
            master = (
                f"``{name}``" in inp
                or f"``{name}``" in commands_doc
                or bool(re.search(
                    rf"^``{re.escape(name)} +",
                    inp,
                    re.M,
                ))
            )
            local = None

        else:
            raise SystemExit(
                f"{NAMES_FILE}:{line_no}: unknown kind {kind!r}"
            )

        rows.append((kind, name, master, local))

if not rows:
    raise SystemExit(f"No names found in {NAMES_FILE}")

width = max(len(row[1]) for row in rows)

print(f"{'kind':8} {'name':{width}}  master-docs  desktop-mpv")
print("-" * (width + 33))

for kind, name, master, local in rows:
    local_label = "-" if local is None else (
        "yes" if local else "NO"
    )
    print(
        f"{kind:8} {name:{width}}  "
        f"{'yes' if master else 'NO ':11}  {local_label}"
    )

print(
    "\nMISSING in master docs:",
    [name for _, name, master, _ in rows if not master],
)
print(
    "MISSING in installed desktop MPV:",
    [name for _, name, _, local in rows if local is False],
)

print(
    "\nNote: documentation matching is heuristic. "
    "A missing match does not necessarily mean the API is invalid."
)
