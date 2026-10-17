"""Emits the slice of the survey network the scoring function needs.

The function resolves matched Valhalla edges to segments, which takes three
fields per segment and nothing else: the id to award against, the OSM way ids
that narrow the candidates, and the geometry that chooses between them. The
app's asset carries propensity, history, litter indices and POIs besides --
none of which the join reads, all of which would ship in every function
deploy.

Generated, not hand-copied: a network edited in one place and transcribed to
another drifts, and drift here means points awarded against a segment id the
app no longer has.

    python tools/build_function_network.py
"""

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SRC = REPO / "app/src/main/assets/terrell_heights.json"
DST = REPO / "functions/src/network.json"


def main():
    data = json.loads(SRC.read_text(encoding="utf-8"))
    segments = [
        {
            "id": s["id"],
            "name": s["name"],
            "osmWayIds": s.get("osmWayIds") or [],
            "coords": s["coords"],
        }
        for s in data["segments"]
    ]
    out = {
        "generatedFrom": SRC.name,
        "neighborhood": data["meta"].get("neighborhood", ""),
        "segments": segments,
    }
    DST.write_text(json.dumps(out, separators=(",", ":")), encoding="utf-8")
    kb = DST.stat().st_size / 1024
    print(f"{len(segments)} segments -> {DST.relative_to(REPO)} ({kb:.0f} KB)")


if __name__ == "__main__":
    main()
