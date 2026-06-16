#!/usr/bin/env python3
"""D-pad focus crawler for Dink on a real Android TV.

Drives the remote via adb, reads the focused node after every key press, and
flags the navigation bugs that are otherwise invisible until a human picks up
the remote:

  * DRAWER_LEAK  focus jumped CONTENT->RAIL on a non-Left key (the "menu opens
                 while I'm navigating a screen" bug)
  * FOCUS_LOST   no focused node after a press (focus fell into the void)
  * OFFSCREEN    focused node sits outside the display
  * TRAP         focus did not move for several presses in different directions
                 (an item you can land on but never leave, or never reach past)

Zone is decided by testTag first (rail_* / miniplayer), geometry as fallback,
so it still works on screens whose leaf focusables are untagged.

Usage:
  tools/focuscheck.py screen [name]   crawl the screen currently on display
  tools/focuscheck.py tour            visit every rail item, crawl each
Screenshots land in tools/shots/. Report prints to stdout.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.example.dink_smb_player"
ACT = f"{PKG}/.MainActivity"
SERIAL = os.environ.get("DINK_SERIAL", "192.168.138.95:5555")
SHOTS = os.path.join(os.path.dirname(__file__), "shots")
W, H = 1920, 1080  # override resolution from `wm size`

KEY = {"UP": 19, "DOWN": 20, "LEFT": 21, "RIGHT": 22, "CENTER": 23, "BACK": 4}
# Keys that legitimately move focus into the rail. Reaching RAIL via anything
# else is the drawer-leak bug.
RAIL_OK_KEYS = {"LEFT", "BACK"}


def adb(*args, capture=True):
    cmd = ["adb", "-s", SERIAL, *args]
    if capture:
        return subprocess.run(cmd, capture_output=True, text=True).stdout
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return ""


def press(key):
    adb("shell", "input", "keyevent", str(KEY[key]), capture=False)
    time.sleep(0.45)


def dump():
    """Return focused node dict {id,cls,text,desc,bounds:(x1,y1,x2,y2)} or None."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", capture=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    m = re.search(r"<\?xml.*", xml, re.S)
    if not m:
        return None
    try:
        root = ET.fromstring(m.group(0))
    except ET.ParseError:
        return None
    for n in root.iter("node"):
        if n.get("focused") == "true":
            b = re.findall(r"\d+", n.get("bounds", ""))
            bounds = tuple(int(x) for x in b[:4]) if len(b) >= 4 else (0, 0, 0, 0)
            return {
                "id": n.get("resource-id", ""),
                "cls": (n.get("class", "") or "").split(".")[-1],
                "text": n.get("text", ""),
                "desc": n.get("content-desc", ""),
                "bounds": bounds,
            }
    return None


def zone(node):
    if node is None:
        return "NONE"
    rid = node["id"]
    # RAIL is decided ONLY by testTag — geometry can't tell the drawer from
    # left-column content (when the drawer collapses, content shifts left too),
    # which produced false DRAWER_LEAK flags. A real leak lands focus on a
    # rail_* node, so the id catches it exactly.
    if "rail_" in rid:
        return "RAIL"
    if "miniplayer" in rid:
        return "MINIPLAYER"
    x1, y1, x2, y2 = node["bounds"]
    if y1 > H * 0.85:               # transport buttons live in the bottom strip
        return "MINIPLAYER"
    return "CONTENT"


def label(node):
    if node is None:
        return "—(no focus)"
    name = node["id"].split("/")[-1] or node["text"] or node["desc"] or node["cls"]
    return f"{name}{node['bounds']}"


def offscreen(node):
    if node is None:
        return False
    x1, y1, x2, y2 = node["bounds"]
    return x1 < 0 or y1 < 0 or x2 > W or y2 > H or x2 <= x1 or y2 <= y1


def shot(tag):
    os.makedirs(SHOTS, exist_ok=True)
    path = os.path.join(SHOTS, f"{tag}.png")
    with open(path, "wb") as f:
        f.write(subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"],
                               capture_output=True).stdout)
    return path


SEQ = ["DOWN", "DOWN", "DOWN", "RIGHT", "RIGHT", "DOWN",
       "UP", "UP", "RIGHT", "DOWN", "DOWN", "UP"]


def crawl(name):
    """Crawl the on-screen content. Assumes focus is already in content."""
    issues, rows = [], []
    prev = dump()
    rows.append(("start", zone(prev), label(prev)))
    shot(f"{name}_00_start")
    stuck = 0
    for i, key in enumerate(SEQ, 1):
        press(key)
        cur = dump()
        z, pz = zone(cur), zone(prev)
        rows.append((key, z, label(cur)))
        shot(f"{name}_{i:02d}_{key}")
        if cur is None:
            issues.append(f"FOCUS_LOST  after {key} (step {i}) — focus vanished")
        elif offscreen(cur):
            issues.append(f"OFFSCREEN   {label(cur)} after {key} (step {i})")
        if pz == "CONTENT" and z == "RAIL" and key not in RAIL_OK_KEYS:
            issues.append(f"DRAWER_LEAK {key} (step {i}) opened the rail from content")
        # movement tracking for trap detection
        if cur and prev and cur["bounds"] == prev["bounds"] and cur["id"] == prev["id"]:
            stuck += 1
            if stuck >= 4:
                issues.append(f"TRAP        focus stuck at {label(cur)} for {stuck} presses")
                stuck = 0
        else:
            stuck = 0
        prev = cur
    return rows, issues


def goto_content():
    """From a rail-focused state, commit into the current screen's content."""
    press("CENTER")
    time.sleep(0.7)  # commitNav retries focus into content over ~600ms


def rail_ids():
    """Ordered list of rail_* item ids from the full hierarchy."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", capture=False)
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    return re.findall(r'resource-id="[^"]*?(rail_\w+)"', xml)


def focused_tail():
    n = dump()
    return n["id"].split("/")[-1] if n else ""


def nav_down_to(target, tries=25):
    """Press DOWN until `target` rail item holds focus. Tolerates dropped/eaten
    keypresses (re-presses) and detects truly unreachable items (returns False).
    Returns (reached, presses_used)."""
    for i in range(tries):
        if focused_tail() == target:
            return True, i
        press("DOWN")
    return focused_tail() == target, tries


def launch():
    adb("shell", "am", "force-stop", PKG, capture=False)
    adb("shell", "am", "start", "-n", ACT, capture=False)
    time.sleep(4)


def report(name, rows, issues):
    print(f"\n=== FOCUS CRAWL: {name} ===")
    for k, z, lbl in rows:
        print(f"  {k:<7} {z:<11} {lbl}")
    if issues:
        print(f"  ISSUES ({len(issues)}):")
        for x in issues:
            print(f"    ⚠ {x}")
    else:
        print("  OK — no focus anomalies")
    return len(issues)


def smoke():
    """Fast regression gate (~20s): rail advances cleanly under D-pad + the
    launch screen has no focus anomalies. Run automatically after a deploy that
    touched a UI/screen file — catches the rail-stability and drawer-leak
    regressions without the cost of a full tour."""
    issues = []
    launch()
    seq = [focused_tail()]
    for i in range(6):
        press("DOWN")
        f = focused_tail()
        seq.append(f)
        if not f:
            issues.append(f"rail step {i + 1}: focus LOST")
        elif "rail_" not in f:
            issues.append(f"rail step {i + 1}: focus left the rail -> {f}")
    for a, b in zip(seq, seq[1:]):
        if a and a == b:
            issues.append(f"rail stuck at {a.replace('rail_','')} — a DOWN press did not advance (dropped?)")
            break
    # content crawl of the launch screen (Home)
    launch()
    goto_content()
    if zone(dump()) != "CONTENT":
        issues.append("CENTER did not enter Home content")
    else:
        _, crawl_issues = crawl("smoke_home")
        issues += crawl_issues
    print("=== FOCUS SMOKE ===")
    print("  rail: " + " -> ".join((s.replace("rail_", "") if s else "∅") for s in seq))
    if issues:
        for x in issues:
            print(f"  ⚠ {x}")
    else:
        print("  OK — rail advances cleanly, launch screen has no focus anomalies")
    return len(issues)


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "screen"
    if adb("get-state").strip() != "device":
        adb("connect", SERIAL)
    total = 0
    if mode == "smoke":
        total += smoke()
        print(f"\nTOTAL ISSUES: {total}  | screenshots: tools/shots/")
        return 1 if total else 0
    if mode == "screen":
        name = sys.argv[2] if len(sys.argv) > 2 else "current"
        rows, issues = crawl(name)
        total += report(name, rows, issues)
    elif mode == "tour":
        # Feedback-driven: discover rail items, then for each relaunch fresh and
        # press DOWN until that item actually holds focus (tolerates the dropped
        # keypresses the rail's nav-on-focus causes). An item we can never focus
        # is reported UNREACHABLE — a real finding, not a crawler limitation.
        launch()
        targets = []
        for t in rail_ids():
            if t not in targets:
                targets.append(t)
        print(f"rail items: {', '.join(targets)}")
        for target in targets:
            launch()
            reached, presses = nav_down_to(target)
            if not reached:
                print(f"\n=== {target} ===\n  ⚠ UNREACHABLE — DOWN never lands focus here")
                total += 1
                continue
            if presses > targets.index(target) + 2:
                print(f"  note: {target} took {presses} DOWN presses "
                      f"(expected ~{targets.index(target)}) — dropped/eaten keypresses")
            goto_content()
            if zone(dump()) != "CONTENT":
                print(f"\n=== {target} ===\n  ⚠ CENTER did not enter content (focus stayed in rail)")
                total += 1
                continue
            rows, issues = crawl(target.replace("rail_", ""))
            total += report(target, rows, issues)
    else:
        print(f"unknown mode: {mode}", file=sys.stderr)
        return 2
    print(f"\nTOTAL ISSUES: {total}  | screenshots: tools/shots/")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
