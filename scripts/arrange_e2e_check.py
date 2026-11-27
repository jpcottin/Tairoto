#!/usr/bin/env python3
"""Checks a swipe across two emulators laid them side by side (see arrange-e2e.sh)."""
import json
import sys
from pathlib import Path


def events(path):
    out = []
    for line in Path(path).read_text(errors="replace").splitlines():
        i = line.find('{"ev"')
        if i >= 0:
            try:
                out.append(json.loads(line[i:]))
            except json.JSONDecodeError:
                pass
    return out


def main(out_dir, host, guest):
    out = Path(out_dir)
    logs = {s: events(out / f"{s}.log") for s in (host, guest)}
    failures = []

    def check(ok, what):
        print(("  ok    " if ok else "  FAIL  ") + what)
        if not ok:
            failures.append(what)

    swipes = {s: [e for e in ev if e["ev"] == "swipe"] for s, ev in logs.items()}
    check(any(not e["entering"] and e["edge"] == "RIGHT" for e in swipes[guest]), "the guest saw a finger leave over its right edge")
    check(any(e["entering"] and e["edge"] == "LEFT" for e in swipes[host]), "the host saw a finger come on over its left edge")
    stitched = [e for e in logs[host] if e["ev"] == "stitched"]
    check(len(stitched) == 1 and stitched[0]["from"] == 1 and stitched[0]["to"] == 0, f"the host laid itself beside the guest: {stitched}")

    def last_view(s):
        views = [e for e in logs[s] if e["ev"] == "view"]
        return views[-1] if views else None

    hv, gv = last_view(host), last_view(guest)
    check(hv is not None and gv is not None, "both drew the table")
    if hv and gv:
        hr = hv["rect"]  # x, y, w, h in mm
        gr = gv["rect"]
        print(f"        guest {gr}, host {hr}")
        check(abs(hr[0] - (gr[0] + gr[2])) < 0.05, "the host's screen begins where the guest's ends (emulators: no frames)")
        out_a = next((e["along"] for e in swipes[guest] if not e["entering"]), None)
        in_a = next((e["along"] for e in swipes[host] if e["entering"]), None)
        if out_a is None or in_a is None:
            check(False, "the finger's path runs level across (a side of the swipe was not seen)")
        else:
            out_y, in_y = gr[1] + out_a, hr[1] + in_a
            check(abs(out_y - in_y) < 0.5, f"the finger's path runs level across: left at {out_y:.2f} mm, came on at {in_y:.2f} mm")
        same = lambda a, b: len(a) == len(b) and all(abs(x - y) < 0.01 for x, y in zip(a, b))
        check(same(hv["tank"], gv["tank"]) and same(hv["origin"], gv["origin"]), "both see the same tank around the same parallax point")
        check(hv["turns"] == 0 and gv["turns"] == 0, "neither turned")
    # The guest's own lagoon came along: its creatures are in the host's lagoon, simulated on the guest.
    scenes = [e for e in logs[guest] if e["ev"] == "scene"]
    host_scene = [e for e in logs[host] if e["ev"] == "scene"]
    if scenes and host_scene:
        g_ids = set(scenes[-1]["ids"])
        h_ids = set(host_scene[-1]["ids"])
        brought = g_ids - h_ids
        check(len(brought) > 0, f"the guest brought {len(brought)} creatures of its own")
    # The most it simulated at once: a guest out of touch for a moment (fenced) simulates nothing
    # until the host answers again, so the last word alone may be a pause, on a slow machine.
    status = [e for e in logs[guest] if e["ev"] == "status"]
    most = max((e.get("owned", 0) for e in status), default=0)
    check(most > 0, f"the guest simulates creatures (at most {most} at once)")
    print("PASS" if not failures else f"FAIL ({len(failures)})")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
