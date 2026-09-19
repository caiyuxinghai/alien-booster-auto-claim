#!/usr/bin/env python3
"""Minimal adb helpers to drive an Android phone from a script or AI agent.

Battle-tested on Windows (Git Bash) + Honor MagicOS / Android 17.
No third-party deps except Pillow for scripts/find_region.py.
"""
import os
import re
import subprocess
import time

ADB = ["adb"]


def adb(*args, binary=False):
    """Run adb. Returns CompletedProcess. binary=True keeps raw bytes stdout."""
    cmd = ADB + [str(a) for a in args]
    if binary:
        return subprocess.run(cmd, capture_output=True)
    return subprocess.run(cmd, capture_output=True, encoding="utf-8", errors="replace")


# ---------- device ----------

def devices():
    return adb("devices").stdout or ""


def _first_device(require_authorized=False):
    """Serial of the first attached device, or None."""
    for line in devices().splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2:
            if require_authorized and parts[1] != "device":
                continue
            return parts[0]
    return None


def ensure_ready(require_authorized=True, timeout=10):
    """Make sure this machine can actually see the phone.

    Some Windows setups cannot claim the phone's ADB interface with adb's
    default USB backend: `adb devices` stays EMPTY even though the phone is
    plugged in and USB debugging is on, which is easily misread as "bad cable
    / driver missing / phone not connected". Retrying with ADB_LIBUSB=1 fixes
    it. Detect that case once and switch automatically.

    Returns the serial, or None if still nothing after both backends.
    """
    for backend in (None, "1"):
        if backend is not None:
            if os.environ.get("ADB_LIBUSB") == "1":
                break
            os.environ["ADB_LIBUSB"] = "1"
            adb("kill-server")
            adb("start-server")
        end = time.time() + timeout
        while time.time() < end:
            s = _first_device(require_authorized)
            if s:
                return s
            time.sleep(1)
    return None


def wait_for_device(timeout=60):
    """Block until a device is in `device` state. Returns the serial or None."""
    end = time.time() + timeout
    while time.time() < end:
        s = _first_device(require_authorized=True)
        if s:
            return s
        time.sleep(2)
    return None


def keep_awake():
    """Prevent the screen from sleeping while USB is connected."""
    adb("shell", "svc", "power", "stayon", "true")


def wake():
    adb("shell", "input", "keyevent", "224")  # KEYCODE_WAKEUP


# ---------- input ----------

def tap(x, y):
    adb("shell", "input", "tap", x, y)


def swipe(x1, y1, x2, y2, ms=200):
    adb("shell", "input", "swipe", x1, y1, x2, y2, ms)


def keyevent(code):
    adb("shell", "input", "keyevent", code)


def text(s):
    adb("shell", "input", "text", s)


# ---------- screen ----------

def screencap(path):
    """Save a PNG screenshot to `path`. NOTE: on Windows, do not write to /tmp
    if a different tool (e.g. an AI Read tool) must read it back."""
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p", binary=True).stdout)


# ---------- state (cheap) ----------

def focus():
    """Return (currentFocus, focusedApp). ~0.1s via dumpsys window.

    Far cheaper than `uiautomator dump` (~7s) - poll with this.
    """
    o = adb("shell", "dumpsys", "window").stdout or ""
    cur = re.search(r'mCurrentFocus=Window\{[^}]*?\s+([\w.]+)/([\w.$]+)\}', o)
    app = re.search(r'mFocusedApp=ActivityRecord\{[^}]*?\s+([\w.]+)/([\w.$]+)', o)
    return (cur.group(1) + "/" + cur.group(2) if cur else "",
            app.group(1) + "/" + app.group(2) if app else "")


def dump_texts():
    """Return the uiautomator XML. EXPENSIVE (~7s). Use sparingly."""
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return adb("shell", "cat", "/sdcard/ui.xml").stdout or ""


def find_bounds(xml, text):
    """Center (x, y) of the first node whose text equals `text`, else None."""
    for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if m.group(1) == text:
            x1, y1, x2, y2 = map(int, m.groups()[1:])
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


# ---------- app lifecycle (cheap) ----------

def bring_front(component):
    """`am start -n pkg/.Activity` - brings an app forward WITHOUT a cold start
    (~0.1s vs ~5s for force-stop + monkey)."""
    adb("shell", "am", "start", "-n", component)


def force_stop(pkg):
    adb("shell", "am", "force-stop", pkg)


# ---------- Honor / MagicOS security dialog ----------

def deny_honor_jump(xml=None):
    """Honor blocks apps from auto-jumping to another app with a dialog
    ("... 想要打开 XXX，是否允许？"). Tap 拒绝 (deny) if present."""
    if xml is None:
        xml = dump_texts()
    if "拒绝" in xml and "想要打开" in xml:
        b = find_bounds(xml, "拒绝") or (370, 2519)
        tap(*b)
        return True
    return False
