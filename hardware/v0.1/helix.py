"""Helix v0.1: what every board of the platform shares.

A Helix device is a Core (radio, motion, microphone, power) worn behind the ear, and modules that
meet it at the Helix Port: 10 contacts on a 2 x 5 grid, held by magnets in the 2 shells. Each board
is a folder beside this file with its own `design.py`, built with Via (`workcenter design`).

The schematic layout below follows the one in Via's Signet example (examples/ring-mouse, MIT).
"""

from __future__ import annotations

import uuid
from pathlib import Path

import kicadgen

HERE = Path(__file__).resolve().parent

X0, Y0 = 100.0, 100.0  # every board's top left corner
WIDTH = 13.0  # every board's width: the Core's, so a module sits flush on it
T = 0.8  # every board's thickness

# The Helix Port, pin by pin (footprints/make_footprints.py has the grid).
PORT = {
    "1": "VCHG",  # 5 V in, from a dock: charges the cell and wakes USB
    "2": "3V0",  # the Core's rail out: logic supply and I2C reference for a module, 150 mA at most
    "3": "GND",
    "4": "GND",
    "5": "USB_DP",  # the nRF52840's USB, for a dock that flashes and logs
    "6": "SDA",  # I2C, pulled up to 3V0 on the Core
    "7": "USB_DM",
    "8": "SCL",
    "9": "MOD_INT",  # open drain, active low, pulled up to 3V0 on the Core
    "10": "VMOD",  # the cell through a load switch the Core turns on: 3.0 to 4.2 V for a module's own loads
}
PORT_HOST = "Helix:HelixPort_Host_2x05_P2.54mm"
PORT_MODULE = "Helix:HelixPort_Module_2x05_P2.54mm"

R0402 = "Resistor_SMD:R_0402_1005Metric"
C0402 = "Capacitor_SMD:C_0402_1005Metric"
SOT23_5 = "Package_TO_SOT_SMD:SOT-23-5"
SOT23_6 = "Package_TO_SOT_SMD:SOT-23-6"


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
        via_size=0.45,
        via_drill=0.2,
        mask_color="#0B0C0EFF",
        silk_color="White",
        copper_finish="ENIG",
    )
    d.footprint_dirs = {"Helix": HERE / "footprints" / "Helix.pretty"}
    d.rules = {"min_through_hole_diameter": 0.2, "min_via_diameter": 0.45}
    return d


def at(dx: float, dy: float) -> tuple[float, float]:
    """A point on a board given from its top left corner."""
    return (round(X0 + dx, 4), round(Y0 + dy, 4))


def port(d: kicadgen.Design, side: str, where: tuple[float, float], nets: dict[str, str] | None = None):
    """The Helix Port as J1: `side` is "host" (the Core, on its back face) or "module" (on the face
    that meets the Core). `nets` leaves out or renames contacts a board does not use."""
    host = side == "host"
    return d.add(
        "J1",
        "Connector_Generic:Conn_02x05_Odd_Even",
        "HELIX PORT",
        PORT_HOST if host else PORT_MODULE,
        sch_at=(0, 0),
        pcb_at=where,
        pcb_layer="B.Cu" if host else "F.Cu",
        nets=dict(PORT if nets is None else nets),
        descr="Helix Port: " + ("10 contact pads" if host else "10 spring pins, Mill-Max 0906 series"),
    )


def finish(d: kicadgen.Design, words: list[tuple[str, float, float, str, float]]) -> None:
    """Every board's last step: its words, no designators on the legend (the parts are too close for
    them), and the schematic in rows."""
    for text, x, y, layer, size in words:
        d.text(text, x, y, layer, size, 0.15)
    for part in d.parts:
        part.hide_ref = True
    lay_out_schematic(d)


def lay_out_schematic(d: kicadgen.Design) -> None:
    """The port at the sheet's left, the chips in a row, the small parts in rows under them; every
    net wired."""
    g = 2.54
    parts = {p.ref: p for p in d.parts}

    def size(p) -> tuple[int, int]:
        xs = [pin.x for pin in p.symbol.pins] or [0]
        ys = [pin.y for pin in p.symbol.pins] or [0]
        return round((max(xs) - min(xs)) / g) + 6, round((max(ys) - min(ys)) / g) + 6

    def small_part(ref: str) -> bool:
        return ref[0] in "RCD" or ref.startswith(("SW", "TP", "BT", "LED"))

    big = [p for r, p in parts.items() if r != "J1" and not small_part(r)]
    small = [p for r, p in parts.items() if r != "J1" and small_part(r)]
    x, tall = 30, 20
    for p in big:
        w, h = size(p)
        p.sch_at, p.sch_rot = ((x + w // 2) * g, (14 + h // 2) * g), 0
        x += w + 8
        tall = max(tall, h)
    per_row = 10
    for k, p in enumerate(small):
        p.sch_at, p.sch_rot = ((30 + 9 * (k % per_row)) * g, (14 + tall + 10 + 10 * (k // per_row)) * g), 0
    if "J1" in parts:
        parts["J1"].sch_at, parts["J1"].sch_rot = (14 * g, (14 + tall // 2) * g), 0
    d.sch_wires = True
