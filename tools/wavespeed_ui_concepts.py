"""Generate Penumbra UI concepts with Nano Banana 2.1 on WaveSpeed.

Reads the API key from the WAVESPEED_API_KEY environment variable only.
Usage: python -I wavespeed_concepts.py <out_dir>
"""
import json, os, sys, time, urllib.request

KEY = os.environ.get("WAVESPEED_API_KEY")
if not KEY:
    sys.exit("WAVESPEED_API_KEY is not set")

OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
SUBMIT = "https://api.wavespeed.ai/api/v3/google/nano-banana-2.1/text-to-image"
RESULT = "https://api.wavespeed.ai/api/v3/predictions/{}/result"

BRAND = (
    "App name 'Penumbra', a game-streaming client for Android gaming handhelds. "
    "Brand: deep indigo/near-black background (#050418), luminous violet-to-cyan eclipse crescent logo, "
    "soft glows, rounded shapes. Landscape 16:9 handheld screen UI mockup, flat screenshot, no device frame, "
    "no real game logos or trademarks (use invented game titles and abstract cover art), crisp readable UI text, "
    "controller-first design with on-screen button hints."
)

CONCEPTS = {
    "concept-1-dual-shelf": (
        "Home screen inspired by the cozy dual-screen handheld console home menus of the 2010s: "
        "a large top 'hero' area showing the focused game's wide banner art, title 'Starfall Odyssey', "
        "playtime and 'Resume on multivac' chip; below it a single horizontal row of rounded-square game icons "
        "with the focused icon enlarged and outlined in a glowing cyan ring; small host switcher pill top-left "
        "('multivac  ·  online  ·  3 ms'), battery and Wi-Fi top-right; bottom bar hints: (A) Play  (X) Resume  "
        "(Y) Options  (≡) Settings. Playful but premium, soft depth and subtle bubbly reflections."
    ),
    "concept-2-cover-grid": (
        "Home screen in the style of modern Android handheld frontends: left vertical rail with icons "
        "(Home, Library, Hosts, Settings) and the Penumbra crescent at the top; main area is a dense grid of "
        "tall portrait game covers (3 rows) with the focused cover scaled up, glowing violet border and a "
        "floating info card (title 'Neon Drift', last played, HDR and 120 fps badges); a translucent top bar "
        "with host status ('multivac  ·  RTX 4080  ·  HDR ready') and clock; background is a blurred, darkened "
        "version of the focused cover art; bottom hints (A) Stream  (Y) Details  (L1/R1) Tabs."
    ),
    "concept-3-console-tiles": (
        "Console-dashboard style home: big horizontal carousel of large wide tiles with parallax depth, "
        "focused tile in the centre showing hero art and a 'Continue streaming' button, neighbours fading and "
        "tilting away; above it a row of category chips (Recent, Playnite, Steam, Desktop, Remote Input); "
        "below the carousel a compact connection panel showing the host 'multivac', bitrate 40 Mbps, "
        "1080p 120 Hz, HDR on, latency 3 ms as small pill indicators; bottom hints (A) Play  (B) Back  "
        "(Start) Quick menu. Calm, cinematic, eclipse-light gradient sweeping across the background."
    ),
}


def call(url, body=None):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
                                 method="POST" if body is not None else "GET")
    with urllib.request.urlopen(req, timeout=60) as r:
        j = json.loads(r.read())
    return j.get("data", j)


jobs = {}
for name, desc in CONCEPTS.items():
    d = call(SUBMIT, {"prompt": f"{desc} {BRAND}", "aspect_ratio": "16:9", "resolution": "2k",
                      "output_format": "png", "enable_web_search": False, "enable_image_search": False})
    jobs[name] = d["id"]
    print("submitted", name, d["id"], flush=True)

pending = dict(jobs)
deadline = time.time() + 600
while pending and time.time() < deadline:
    time.sleep(3)
    for name, pid in list(pending.items()):
        d = call(RESULT.format(pid))
        st = d.get("status")
        if st == "completed":
            url = d["outputs"][0]
            path = os.path.join(OUT, f"{name}.png")
            urllib.request.urlretrieve(url, path)
            print("saved", path, flush=True)
            del pending[name]
        elif st in ("failed", "cancelled", "timeout", "deleted"):
            print("FAILED", name, st, d.get("error"), flush=True)
            del pending[name]
for name in pending:
    print("TIMED OUT", name)
