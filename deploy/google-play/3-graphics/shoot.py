#!/usr/bin/env python3
"""Take the Play Store screenshots, and the website's, from the debug app.

    python3 deploy/google-play/3-graphics/shoot.py [--serial emulator-5554] [--apk PATH]

Needs a running emulator (or a phone you are happy to wipe the app on) and a
debug build: only debug builds carry the demo seeder. It installs the app
fresh, seeds the made-up demo data, sets the screen to 1080x2160 (2:1, the
tallest Play accepts), puts the status bar in demo mode, and taps through the
app by the text on screen. Light-theme shots go to screenshots/ (Play); light
and dark ones for the website go to ../../../site/assets/screens/.
"""

import argparse
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
APP = "com.saferendering.hubidubitask"
MAIN = APP + "/com.hubidubitask.mobile.MainActivity"
SEEDER = APP + "/com.hubidubitask.mobile.demo.DemoSeed"


class Phone:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, capture=False):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, text=not capture)
        return r.stdout

    def sh(self, *args):
        return self.adb("shell", *args)

    def nodes(self):
        # A dump fails while the screen animates and leaves the last file
        # behind; remove it first so a failed dump reads as nothing, not as
        # the previous screen.
        self.sh("rm", "-f", "/sdcard/ui.xml")
        self.sh("uiautomator", "dump", "/sdcard/ui.xml")
        xml = self.sh("cat", "/sdcard/ui.xml 2>/dev/null")
        out = []
        for m in re.finditer(r"<node [^>]*>", xml):
            n = m.group(0)
            text = (re.search(r' text="([^"]*)"', n) or [None, ""])[1]
            desc = (re.search(r'content-desc="([^"]*)"', n) or [None, ""])[1]
            b = list(map(int, re.findall(r"\d+", re.search(r'bounds="([^"]*)"', n).group(1))))
            out.append((text or desc, b))
        return out

    def find(self, label, below=0, tries=6):
        for _ in range(tries):
            for t, b in self.nodes():
                if t == label and b[1] >= below:
                    return b
            time.sleep(0.8)
        sys.exit("not on screen: %r" % label)

    def tap(self, label, below=0, hold=False, wait=1.4):
        b = self.find(label, below)
        x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        if hold:
            self.sh("input", "swipe", str(x), str(y), str(x), str(y), "900")
        else:
            self.sh("input", "tap", str(x), str(y))
        time.sleep(wait)

    def back(self):
        self.sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.2)

    def shot(self, path):
        png = self.adb("exec-out", "screencap", "-p", capture=True)
        tmp = path + ".png"
        with open(tmp, "wb") as f:
            f.write(png)
        subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "90", tmp, "--out", path],
                       capture_output=True, check=True)
        os.remove(tmp)
        print("  " + os.path.relpath(path, REPO))


# What each tab shows once it is open, to know the tap landed.
LANDMARK = {"Incoming": "Day", "Projects": "New project or task", "Feeds": "New feed", "Settings": "AI AGENT"}


def tab(p, name):
    """Open a tab; the tabs sit at the bottom, where text above them can carry the same word."""
    for _ in range(3):
        p.tap(name, below=1800)
        if any(t == LANDMARK[name] for t, _ in p.nodes()):
            return
    sys.exit("the %s tab did not open" % name)


def clean_status_bar(p):
    """9:30, full battery and Wi-Fi, no notifications. A theme switch resets it."""
    p.sh("settings", "put", "global", "sysui_demo_allowed", "1")
    for cmd in (["enter"], ["clock", "-e", "hhmm", "0930"], ["battery", "-e", "level", "100", "-e", "plugged", "false"],
                ["network", "-e", "wifi", "show", "-e", "level", "4"], ["network", "-e", "mobile", "hide"],
                ["notifications", "-e", "visible", "false"]):
        p.sh("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", *cmd)


def prepare(p, apk):
    p.adb("install", "-r", apk)
    p.sh("pm", "clear", APP)
    p.sh("wm", "size", "1080x2160")
    p.sh("am", "start", "-n", MAIN)
    time.sleep(4)
    p.sh("am", "broadcast", "-n", SEEDER)
    time.sleep(6)
    p.sh("am", "force-stop", APP)


def start(p, night):
    # A theme switch restarts what is on screen, a little later; let it.
    p.sh("cmd", "uimode", "night", "yes" if night else "no")
    time.sleep(5)
    clean_status_bar(p)
    p.sh("am", "force-stop", APP)
    p.sh("am", "start", "-n", MAIN)
    # Incoming opens first; it has an overdue task once the items have loaded.
    # Seen twice, so the shot is not taken while the screen is being redrawn.
    p.find("OVERDUE", tries=20)
    time.sleep(2.5)
    p.find("OVERDUE", tries=20)
    time.sleep(1)


def play_set(p, out):
    """Eight light screens for the listing, in listing order."""
    start(p, night=False)
    p.shot(os.path.join(out, "1-next-day.jpg"))
    tab(p, "Projects")
    p.tap("New project or task")
    p.shot(os.path.join(out, "2-new.jpg"))
    p.back()
    p.tap("Kitchen renovation")
    p.tap("Trip to Lisbon")
    p.shot(os.path.join(out, "3-projects.jpg"))
    p.tap("Order the worktop")
    p.shot(os.path.join(out, "4-item.jpg"))
    p.back()
    # A project's long press: its summary under the title, then what can be done.
    p.tap("Kitchen renovation", hold=True)
    p.shot(os.path.join(out, "5-review-with-ai.jpg"))
    p.back()
    tab(p, "Feeds")
    p.shot(os.path.join(out, "6-feeds.jpg"))
    tab(p, "Incoming")
    p.tap("Week")
    p.shot(os.path.join(out, "7-next-week.jpg"))
    tab(p, "Settings")
    # It opens on the AI agent, the devices, the due date and the language.
    p.find("SelfHostedAI")
    p.shot(os.path.join(out, "8-settings-ai.jpg"))


def web_set(p, out, night):
    """The four the website shows, in the theme it is showing."""
    suffix = "-dark" if night else ""
    start(p, night)
    p.shot(os.path.join(out, "next-day%s.jpg" % suffix))
    tab(p, "Projects")
    p.tap("New project or task")
    p.shot(os.path.join(out, "new%s.jpg" % suffix))
    p.back()
    p.tap("Kitchen renovation")
    p.tap("Trip to Lisbon")
    p.shot(os.path.join(out, "projects%s.jpg" % suffix))
    p.tap("Kitchen renovation", hold=True)
    p.shot(os.path.join(out, "review%s.jpg" % suffix))
    p.back()
    tab(p, "Feeds")
    p.shot(os.path.join(out, "feeds%s.jpg" % suffix))
    tab(p, "Incoming")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default="emulator-5554")
    ap.add_argument("--apk", default=os.path.join(REPO, "src/apps/android/build/outputs/apk/debug/android-debug.apk"))
    ap.add_argument("--only", choices=["play", "web"], help="take one set only")
    a = ap.parse_args()
    p = Phone(a.serial)
    print("preparing %s" % a.serial)
    prepare(p, a.apk)
    play = os.path.join(HERE, "screenshots")
    web = os.path.join(REPO, "site", "assets", "screens")
    os.makedirs(play, exist_ok=True)
    os.makedirs(web, exist_ok=True)
    if a.only != "web":
        print("Play (light):")
        play_set(p, play)
    if a.only != "play":
        print("website (light, dark):")
        web_set(p, web, night=False)
        web_set(p, web, night=True)
    # Back to how the emulator was.
    p.sh("cmd", "uimode", "night", "no")
    p.sh("am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
    p.sh("wm", "size", "reset")
    # The website shows them small: 540 px wide is sharp on a 2x screen.
    for f in os.listdir(web) if a.only != "play" else []:
        if f.endswith(".jpg"):
            subprocess.run(["sips", "--resampleWidth", "540", "-s", "formatOptions", "82",
                            os.path.join(web, f)], capture_output=True, check=True)


if __name__ == "__main__":
    main()
