#!/usr/bin/env python3
"""Checks the logs of a lagoon shared across devices (see share-e2e.sh).

Every device writes one JSON line per event to files/net-log.jsonl. From all
of them together, for every creature: who simulated it when, in shared time
(a device starts at "took" and stops at "released"; the host simulates its
whole lagoon to begin with). Then:

  exactly once   no two devices ever simulate a creature at the same moment;
                 between owners a creature goes unsimulated only for as long
                 as changing hands takes; at the end each has one owner.
  food           what was dropped was eaten, decomposed or is in the water,
                 wherever it drifted.
  the frenzy     fish came over to the middle screen for the food.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

TOLERANCE_S = 0.05  # the clocks agree to a few ms between emulators; allow more
# Changing hands takes a round trip to the host; an offer nobody takes runs out after 0.5 s.
MAX_GAP_S = 1.5


def events(path):
    out = []
    p = Path(path)
    if not p.exists():
        return out
    for line in p.read_text(errors="replace").splitlines():
        i = line.find('{"ev"')
        if i < 0:
            continue
        try:
            out.append(json.loads(line[i:]))
        except json.JSONDecodeError:
            pass
    return out


def main(out_dir, serials):
    out = Path(out_dir)
    logs = {s: events(out / f"{s}.log") for s in serials}
    failures = []

    def check(ok, what):
        print(("  ok    " if ok else "  FAIL  ") + what)
        if not ok:
            failures.append(what)

    slot = {}
    for s, ev in logs.items():
        share = [e for e in ev if e["ev"] == "share"]
        if share:
            slot[s] = share[-1]["self"]
    print("Devices: " + ", ".join(f"{s} = slot {slot.get(s, '?')}" for s in serials))
    check(len(slot) == len(serials), f"all {len(serials)} devices are in the lagoon")
    host = next((s for s, v in slot.items() if v == 0), None)
    check(host is not None, "one of them is the host")
    if host is None:
        print("FAIL")
        return 1

    statuses = {s: [e for e in ev if e["ev"] == "status"] for s, ev in logs.items()}
    check(all(any(st["devices"] == len(serials) for st in statuses[s]) for s in serials),
          "every device saw every screen on the table")

    # ---- exactly once, the whole time
    scene = next((e for e in logs[host] if e["ev"] == "scene"), None)
    ids = set(scene["ids"]) if scene else set()
    # The logs are read one device after another while the lagoon swims on: each is cut where
    # the first one read ends, so none has news the others' logs could not have had yet.
    log_end = min(max(e["t"] for e in ev if "t" in e) for ev in logs.values() if any("t" in e for e in ev))
    intervals = defaultdict(list)  # id -> [(start, end, slot)]
    for s, ev in logs.items():
        if s not in slot:
            continue
        open_at = {}
        if s == host:
            for cid in ids:
                open_at[cid] = float("-inf")
        for e in sorted((e for e in ev if e["ev"] in ("took", "released") and e["t"] <= log_end), key=lambda e: e["t"]):
            cid = e["id"]
            ids.add(cid)
            if e["ev"] == "took":
                open_at.setdefault(cid, e["t"])
            elif cid in open_at:
                intervals[cid].append((open_at.pop(cid), e["t"], slot[s]))
        for cid, st in open_at.items():
            intervals[cid].append((st, float("inf"), slot[s]))
    overlaps, gaps, lost = [], [], []
    for cid in sorted(ids):
        ivs = sorted(intervals.get(cid, []))
        for a, b in zip(ivs, ivs[1:]):
            if b[0] < a[1] - TOLERANCE_S:
                overlaps.append((cid, a, b))
            elif b[0] - a[1] > MAX_GAP_S:
                gaps.append((cid, a, b))
        last = ivs[-1] if ivs else None
        # At the end each is simulated somewhere, unless it was changing hands right then.
        if last is None or (last[1] != float("inf") and log_end - last[1] > MAX_GAP_S):
            lost.append((cid, last))
    took = sum(1 for ev in logs.values() for e in ev if e["ev"] == "took")
    print(f"  {took} hand-overs; {len(ids)} creatures; {sum(len(v) for v in intervals.values())} stretches of ownership")
    check(took >= 10, "fish swam from screen to screen (at least 10 hand-overs)")
    check(not overlaps, "no creature was ever simulated by two devices at once" +
          (f" ({len(overlaps)}, first {overlaps[0]})" if overlaps else ""))
    check(not gaps, f"no creature went unsimulated longer than {MAX_GAP_S}s while changing hands" +
          (f" ({len(gaps)}, first {gaps[0]})" if gaps else ""))
    check(not lost, "every creature is simulated by one device at the end" +
          (f" ({len(lost)} not: {lost[:3]})" if lost else ""))

    # ---- food
    last = {s: st[-1] for s, st in statuses.items() if st}
    keys = ("dropped", "eaten", "decomposed", "sent", "received", "water")
    total = {k: sum(st.get(k, 0.0) for st in last.values()) for k in keys}
    print("  food (mg): " + ", ".join(f"{k} {total[k]:.2f}" for k in keys))
    check(total["dropped"] > 1.0, "food was dropped")
    check(total["eaten"] > 0.5, "food was eaten")
    in_flight = total["sent"] - total["received"]
    accounted = total["eaten"] + total["decomposed"] + total["water"] + in_flight
    # The devices' last statuses are up to 2 s apart, so allow what moves in that time.
    check(abs(total["dropped"] - accounted) <= max(1.0, 0.05 * total["dropped"]),
          f"no food lost or counted twice (dropped {total['dropped']:.2f}, accounted for {accounted:.2f})")
    sent = sum(1 for ev in logs.values() for e in ev if e["ev"] == "food_sent")
    print(f"  {sent} pieces of food drifted onto another screen")

    # ---- the frenzy drew fish onto the middle screen
    mid = serials[len(serials) // 2]
    arrived = sum(1 for e in logs[mid] if e["ev"] == "took")
    check(arrived >= 5, f"fish came over to the middle screen for the food ({arrived})")

    print("PASS" if not failures else f"FAIL: {len(failures)} check(s)")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2:]))
