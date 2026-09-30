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
                 AND LEFT can't reach the rail either (an item you can land on but
                 never leave). A screen with a single focusable (Local Storage) that
                 escapes LEFT to the rail is fine, not a trap.
  * CRASH        the app died (FATAL EXCEPTION in the crash log / process gone)

Zone is decided by testTag first (rail_* / miniplayer), geometry as fallback,
so it still works on screens whose leaf focusables are untagged.

Usage:
  tools/focuscheck.py screen [name]   crawl the screen currently on display
  tools/focuscheck.py tour            visit every rail item, crawl each
  tools/focuscheck.py smoke           fast rail + Home regression gate
  tools/focuscheck.py homeshelf       UI-1: scroll each Home shelf past card 0,
                                      then re-enter it with Up/Down (crash repro)
  tools/focuscheck.py empty           UI-19: commit Playlists / Now Playing and
                                      check focus lands in content, not the rail
                                      (true empty state needs no playlists / no
                                      saved session, e.g. a fresh install)
Screenshots land in tools/shots/. Report prints to stdout.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.dink.player"  # applicationId; namespace below is the old one
ACT = f"{PKG}/com.example.dink_smb_player.MainActivity"
SERIAL = os.environ.get("DINK_SERIAL", "192.168.255.81:5555")
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
    def box(n):
        b = re.findall(r"\d+", n.get("bounds", ""))
        return tuple(int(x) for x in b[:4]) if len(b) >= 4 else (0, 0, 0, 0)

    for n in root.iter("node"):
        if n.get("focused") == "true":
            bounds = box(n)
            # What the focused item SHOWS: the text of every node drawn inside it. A
            # scrolling list keeps the focused item at the same screen position, so
            # bounds alone can't tell "moved to the next row" from "didn't move".
            x1, y1, x2, y2 = bounds
            shown = tuple(
                m.get("text", "") for m in root.iter("node")
                if m.get("text") and x1 <= box(m)[0] <= x2 and y1 <= box(m)[1] <= y2
            )
            return {
                "id": n.get("resource-id", ""),
                "cls": (n.get("class", "") or "").split(".")[-1],
                "text": n.get("text", ""),
                "desc": n.get("content-desc", ""),
                "bounds": bounds,
                "shown": shown,
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


def same_item(a, b):
    """Same focused item: same node, same place, showing the same thing."""
    return bool(a and b and a["bounds"] == b["bounds"] and a["id"] == b["id"]
                and a.get("shown") == b.get("shown"))


def left_escapes(before):
    """True if LEFT gets the user out of [before]: either onto the rail, or onto a
    sibling (a grid's right-hand column — LEFT walks back along the row, which is not a
    trap). Restores focus afterwards (RIGHT, else re-commit via CENTER)."""
    press("LEFT")
    after = dump()
    ok = zone(after) == "RAIL" or not same_item(before, after)
    if ok:
        press("RIGHT")
        if zone(dump()) != "CONTENT":
            goto_content()
    return ok


def clear_crash_log():
    adb("logcat", "-b", "crash", "-c", capture=False)


def crashed():
    """Crash summary for our package since clear_crash_log(), '' if none. A missing
    process also counts (a crash can race the log flush)."""
    log = adb("logcat", "-b", "crash", "-d")
    if "FATAL EXCEPTION" in log and PKG in log:
        # Exception line + the next (Compose puts the message on its own line).
        m = re.search(r"[\w.$]+(?:Exception|Error):[^\n]*(?:\n[^\n]*)?", log)
        return " ".join(m.group(0).split()) if m else "FATAL EXCEPTION"
    if not adb("shell", "pidof", PKG).strip():
        return "process not running"
    return ""


def crawl(name):
    """Crawl the on-screen content. Assumes focus is already in content."""
    issues, rows = [], []
    prev = dump()
    rows.append(("start", zone(prev), label(prev)))
    shot(f"{name}_00_start")
    stuck = 0
    escape_checked = False  # LEFT-escape probe runs at most once per crawl
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
        if same_item(cur, prev):
            stuck += 1
            if stuck >= 4:
                # One focusable and nowhere else to go is legitimate as long as LEFT
                # still reaches the rail; only flag when the user truly can't leave.
                if escape_checked or zone(cur) != "CONTENT":
                    pass
                elif left_escapes(cur):
                    rows.append(("LEFT", "-", "LEFT leaves this item (rail or sibling) — not a trap"))
                    cur = dump()
                else:
                    issues.append(f"TRAP        focus stuck at {label(cur)} for {stuck} presses "
                                  f"and LEFT does not leave it")
                escape_checked = True
                stuck = 0
        else:
            stuck = 0
        prev = cur
    return rows, issues


def goto_content():
    """From a rail-focused state, commit into the current screen's content."""
    press("CENTER")
    time.sleep(0.7)  # commitNav retries focus into content over ~600ms
    # Home's hero is gated on session restore (~10 s after a cold launch); until it
    # composes there is nothing to commit into and focus stays on the rail. Wait for
    # content before crawling, or the first DOWN is misreported as a DRAWER_LEAK.
    deadline = time.time() + 15
    while zone(dump()) == "RAIL" and time.time() < deadline:
        time.sleep(1.0)
        press("RIGHT")
        time.sleep(0.5)


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


def home_shelf():
    """UI-1 regression: Home shelves route Up/Down/focus-enter to each shelf's FIRST
    card. Scrolling a shelf right past card 0 disposes that card; routing focus to its
    requester then crashed with 'FocusRequester is not initialized'. Walk every
    route into a scrolled shelf and fail on crash / lost focus."""
    issues, rows = [], []
    launch()
    clear_crash_log()
    goto_content()  # hero "Continue Playing"
    right8 = [("RIGHT", "scroll shelf")] * 8
    steps = (
        [("DOWN", "hero -> Recently played")] + right8 +
        [("UP", "Recently (scrolled) -> hero"),
         ("DOWN", "hero -> Recently: card 0 disposed"),
         ("DOWN", "Recently -> New in library")] + right8 +
        [("UP", "New (scrolled) -> Recently (scrolled)"),
         ("DOWN", "Recently -> New: card 0 disposed"),
         ("DOWN", "New -> Across your sources")] + right8 +
        [("UP", "Across -> New (scrolled)"),
         ("DOWN", "New -> Across: card 0 disposed"),
         ("DOWN", "Across (scrolled) -> mini player"),
         ("UP", "mini player -> Across: shelf focus-enter")]
    )
    for i, (key, why) in enumerate(steps, 1):
        press(key)
        c = crashed()
        if c:
            issues.append(f"CRASH       after {key} (step {i}: {why}) — {c}")
            shot(f"homeshelf_{i:02d}_CRASH")
            break
        cur = dump()
        rows.append((key, zone(cur), f"{label(cur)}  [{why}]"))
        if cur is None:
            issues.append(f"FOCUS_LOST  after {key} (step {i}: {why})")
        elif zone(cur) == "RAIL":
            issues.append(f"DRAWER_LEAK {key} (step {i}: {why}) opened the rail")
    shot("homeshelf_end")
    return report("home shelves (UI-1)", rows, issues)


def empty_states():
    """UI-19 regression: committing a screen from the rail must land focus in its
    content. Empty Playlists / Now Playing used to have no focusable, so focus stayed
    in the rail and the drawer stayed open. Checks the invariant whatever the data;
    the focused label shows whether the empty-state CTA ("Browse songs") was hit."""
    total = 0
    for target in ("rail_playlists", "rail_nowplaying"):
        issues, rows = [], []
        launch()
        reached, _ = nav_down_to(target)
        if not reached:
            issues.append(f"UNREACHABLE {target}")
        else:
            goto_content()
            cur = dump()
            rows.append(("CENTER", zone(cur), label(cur)))
            shot(f"empty_{target}")
            if zone(cur) != "CONTENT":
                issues.append("CENTER left focus in the rail — screen has no focus target")
            elif not left_escapes(cur):
                issues.append(f"TRAP        LEFT from {label(cur)} does not leave it")
        total += report(f"{target} (UI-19)", rows, issues)
    return total


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "screen"
    if adb("get-state").strip() != "device":
        adb("connect", SERIAL)
    total = 0
    if mode == "smoke":
        total += smoke()
        print(f"\nTOTAL ISSUES: {total}  | screenshots: tools/shots/")
        return 1 if total else 0
    if mode in ("homeshelf", "empty"):
        total += home_shelf() if mode == "homeshelf" else empty_states()
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
