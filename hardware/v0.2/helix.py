"""Helix v0.2: what the boards of the platform share.

Helix is a hook round the ear and a pod that snaps onto the hook's spine with magnets. The pod holds
one board, built with Via (`workcenter design`). v0.2 has one pod, the Core.

The schematic layout below follows the one in Via's Signet example (examples/ring-mouse, MIT).
"""

from __future__ import annotations

import uuid

import kicadgen

X0, Y0 = 100.0, 100.0  # every board's top left corner
WIDTH = 13.0  # every board's width
T = 0.8  # every board's thickness

R0402 = "Resistor_SMD:R_0402_1005Metric"
C0402 = "Capacitor_SMD:C_0402_1005Metric"
SOT23_5 = "Package_TO_SOT_SMD:SOT-23-5"
SOD523 = "Diode_SMD:D_SOD-523"


def board(name: str, namespace: uuid.UUID, length: float, corner_radius, sheet: str = "A3") -> kicadgen.Design:
    """A Helix board: 13 mm wide and `length` long, 4 layers 0.8 mm thick with ground on In1, matte
    black with a white legend and gold pads. The caller adds its parts, its plane, and calls `finish`."""
    d = kicadgen.Design(
        name=f"helix_{name}",
        namespace=namespace,
        outline=(X0, Y0, X0 + WIDTH, Y0 + length),
        corner_radius=corner_radius,
        thickness=T,
        copper_layers=4,
        plane_layers=("In1.Cu",),
        sheet=sheet,
        track_width=0.15,
        clearance=0.13,
        via_size=0.5,  # a 0.2 mm hole in a 0.15 mm ring: PCBWay's least
        via_drill=0.2,
        mask_color="#0B0C0EFF",
        silk_color="White",
        copper_finish="ENIG",
    )
    d.fab = "pcbway-standard"  # held to PCBWay's published limits for a standard rigid board
    d.rules = {"min_through_hole_diameter": 0.2, "min_via_diameter": 0.5}
    return d


def at(dx: float, dy: float) -> tuple[float, float]:
    """A point on a board given from its top left corner."""
    return (round(X0 + dx, 4), round(Y0 + dy, 4))


def finish(d: kicadgen.Design, words: list[tuple[str, float, float, str, float]]) -> None:
    """Every board's last step: its words, no designators on the legend (the parts are too close for
    them), and the schematic in rows."""
    for text, x, y, layer, size in words:
        d.text(text, x, y, layer, size, 0.15)
    for part in d.parts:
        part.hide_ref = True
    lay_out_schematic(d)


def lay_out_schematic(d: kicadgen.Design) -> None:
    """The connector at the sheet's left, the chips in a row, the small parts in rows under them;
    every net wired."""
    g = 2.54
    parts = {p.ref: p for p in d.parts}

    def size(p) -> tuple[int, int]:
        xs = [pin.x for pin in p.symbol.pins] or [0]
        ys = [pin.y for pin in p.symbol.pins] or [0]
        return round((max(xs) - min(xs)) / g) + 6, round((max(ys) - min(ys)) / g) + 6

    def small_part(ref: str) -> bool:
        return ref[0] in "RCD" or ref.startswith(("SW", "TP", "BT", "LED"))

    big = [p for r, p in parts.items() if r != "J1" and not small_part(r)]
    small = [p for r, p in parts.items() if small_part(r)]
    # The chips in rows of at most 110 grid steps, the small parts in rows under the last of them.
    x, y, tall = 44, 14, 20
    for p in big:
        w, h = size(p)
        if x + w > 150:
            x, y, tall = 44, y + tall + 12, 20
        p.sch_at, p.sch_rot = ((x + w // 2) * g, (y + h // 2) * g), 0
        x += w + 12
        tall = max(tall, h)
    per_row = 12
    for k, p in enumerate(small):
        p.sch_at, p.sch_rot = ((30 + 9 * (k % per_row)) * g, (y + tall + 10 + 10 * (k // per_row)) * g), 0
    if "J1" in parts:
        parts["J1"].sch_at, parts["J1"].sch_rot = (16 * g, 28 * g), 0
    d.sch_wires = True
