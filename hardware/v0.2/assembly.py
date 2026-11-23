#!/usr/bin/env python3
"""Helix v0.2 assembly: the Core in its pod, on the hook.

The hook is the printed one (`3D models/Helix Ear Clip.stl`), unchanged: a C round the ear, 8 mm
thick, with 3 magnet pockets 6.1 mm across on the outer face of its spine. The pod meets that face
edge-on with 3 magnets of its own; that line is where Helix splits in 2.

The Core is the scene's board. It stands in the pod with its long side along the spine, 3.2 mm in
from the spine's face: 2 mm of magnet and a wall. The cell, an LP401230 with its protection circuit (4 x 12 x 33 mm,
100 mAh), lies over the radio module's can and every part beside it, from the antenna's end to the
USB-C socket. The pod's shell is drawn as glass, 17 x 48 x 9.9 mm outside; the socket's mouth is at
its bottom end and the button on its rear edge.

    workcenter design core/design.py --route      # the routed board this reads
    workcenter pictures .                         # runs this with Via's tools/ on PYTHONPATH

Writes build/assembly/: the scene's bodies, helix_assembly.kicad_pcb, 4 stills and a turning GIF.
"""

from __future__ import annotations

import struct
import sys
from pathlib import Path

import scene
import wrlgen as w

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import helix  # noqa: E402

HOOK_STL = HERE.parent.parent / "3D models" / "Helix Ear Clip.stl"
SPINE_X = 30.66  # the hook's own frame: the outer face of its spine
MAGNETS_Z = (9.75, -0.57, -10.9)  # the 3 pockets' middles along the spine
MAGNET = (6.0, 2.0)  # a magnet's diameter and thickness
HOOK_MID_Y = -4.0  # the middle of the hook's 8 mm

INSET = 3.2  # from the spine's face to the board's edge
WALL = 1.0
CELL = (12.0, 33.0, 4.0)  # across the board, along it, its thickness
MODULE_TOP = 2.1  # the radio module's height above the board's top face: the cell rests on its can
BACK = 1.0  # the tallest part on the back face: the microphone
LENGTH = 46.0  # core/design.py
# The pod, in the scene's frame (x right, y down the board, z up from the board's top face).
POD_X = (helix.X0 - INSET, helix.X0 + helix.WIDTH + WALL - 0.2)
POD_Y = (helix.Y0 - WALL, helix.Y0 + LENGTH + WALL)
POD_Z = (-(helix.T + BACK + WALL), MODULE_TOP + CELL[2] + WALL)
MID_Z = (POD_Z[0] + POD_Z[1]) / 2
HOOK_TOP_Z = 23.72 - WALL  # the hook's height that the board's top edge stands at: the old box's top, less a wall

KAPTON = (0.85, 0.50, 0.10)
HOOK = (0.13, 0.13, 0.14)
SHELL = (0.55, 0.60, 0.66)
NICKEL = (0.80, 0.80, 0.78)
PIVOT = "-0.9,0,0.2"
VIEWS = {
    "assembly-iso": {"rotate": "-50,0,-30", "perspective": True, "zoom": 0.55, "pivot": PIVOT},
    "assembly-back": {"rotate": "-130,0,-30", "perspective": True, "zoom": 0.55, "pivot": PIVOT},
    "assembly-side": {"rotate": "0,0,0", "zoom": 0.55, "pivot": PIVOT},  # as it lies on the head
    "assembly-edge": {"rotate": "-90,0,0", "zoom": 0.55, "pivot": PIVOT},  # from below: its thickness
}


def to_scene(x: float, y: float, z: float) -> tuple[float, float, float]:
    """A point of the hook's frame (x back from the ear, y through its thickness, z up) in a body's
    frame at the board's top left corner: x right, y up the board, z out of its top face."""
    return (x - SPINE_X - INSET, z - HOOK_TOP_Z, y - HOOK_MID_Y + MID_Z)


def hook() -> w.Mesh:
    m = w.Mesh(HOOK)
    data = HOOK_STL.read_bytes()
    (n,) = struct.unpack("<I", data[80:84])
    for k in range(n):
        v = struct.unpack("<9f", data[84 + k * 50 + 12 : 84 + k * 50 + 48])
        m.face(*(m.vertex(*to_scene(*v[i : i + 3])) for i in (0, 3, 6)))
    return m


def magnets() -> w.Mesh:
    """3 pairs: 1 in the spine, 1 in the pod's edge, face to face."""
    m = w.Mesh(NICKEL)
    d, t = MAGNET
    for z in MAGNETS_Z:
        for x in (SPINE_X - t, SPINE_X + 0.05):
            cx, cy, cz = to_scene(x + t / 2, HOOK_MID_Y, z)
            w.box(m, cx, cy, cz - d / 2, t - 0.05, d, d)
    return m


def shell() -> w.Mesh:
    m = w.Mesh(SHELL, transparency=0.72)
    sx, sy = POD_X[1] - POD_X[0], POD_Y[1] - POD_Y[0]
    outline = [(x + (POD_X[0] - helix.X0) + sx / 2, y - (POD_Y[0] - helix.Y0) - sy / 2)
               for x, y in w.rounded_rect(sx, sy, 2.5)]  # fmt: skip
    w.prism(m, outline, POD_Z[0], POD_Z[1])
    return m


def cell() -> list:
    """The pouch, its origin on the face it rests on, under its middle: foil with a Kapton band."""
    cx, cy, cz = CELL
    foil = w.Mesh(w.FOIL)
    w.prism(foil, w.rounded_rect(cx, cy, 1.0), 0.05, cz)
    band = w.Mesh(KAPTON, transparency=0.15)
    w.prism(band, w.rounded_rect(cx + 0.12, 5.0, 0.3), 0.04, cz + 0.06)
    return [foil, band]


def main() -> int:
    s = scene.Scene("core/helix_core.kicad_pcb", out="build/assembly", name="helix_assembly")
    corner = (helix.X0, helix.Y0, 0.0)
    s.place(s.write("hook.wrl", [hook()], "the printed ear hook"), corner)
    s.place(s.write("magnets.wrl", [magnets()], "6 magnets, 6 x 2 mm"), corner)
    s.place(s.write("pouch.wrl", cell(), "LP401230 LiPo pouch with its PCM"),
            (helix.X0 + helix.WIDTH / 2, helix.Y0 + 5.0 + CELL[1] / 2, MODULE_TOP))  # fmt: skip
    s.place(s.write("shell.wrl", [shell()], "the pod's shell, as glass"), corner)
    s.stills(VIEWS)
    s.spin("assembly-spin.gif", tilt=-58, zoom=0.45)  # no pivot: one that starts with a minus is read as a flag
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
