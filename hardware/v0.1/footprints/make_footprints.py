"""Helix's own footprints, written into Helix.pretty/.

    python3 footprints/make_footprints.py      # from the version's folder

The Helix Port is the 10-contact face where a module meets the Core. Seen from above the Core's top
face (through the board), the contacts sit on a 2 x 5 grid, 2.54 mm apart:

    1 VCHG   3 GND   5 USB_DP   7 USB_DM   9 INT
    2 3V0    4 GND   6 SDA      8 SCL     10 VMOD

- HelixPort_Host_2x05_P2.54mm: the Core's side, 10 flat gold pads 1.5 mm across. It is placed on
  the back face, where KiCad mirrors a footprint top to bottom, so its rows are written swapped.
- HelixPort_Module_2x05_P2.54mm: a module's side, on the face that meets the Core: 10 lands for
  surface-mount spring pins, in the grid as it is seen above.

Placed at the same board coordinates, pad N of one lies over pad N of the other; helix.py checks it.
"""

from __future__ import annotations

import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
NS = uuid.UUID("5b0e6f1c-7a34-4d2e-9c10-3f8a2b6d4e71")
PITCH = 2.54
HOST_PAD, MODULE_PAD = 1.5, 1.6


def uid(key: str) -> str:
    return str(uuid.uuid5(NS, key))


def fmt(v: float) -> str:
    s = f"{v:.4f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def grid(pin: int) -> tuple[float, float]:
    """Where contact `pin` sits, seen from above the Core's top face, from the port's middle."""
    col, row = (pin - 1) // 2, (pin - 1) % 2
    return ((col - 2) * PITCH, (row - 0.5) * PITCH)


def text(kind: str, value: str, y: float, layer: str, key: str) -> str:
    return (
        f'\t(property "{kind}" "{value}"\n\t\t(at 0 {fmt(y)} 0)\n\t\t(layer "{layer}")\n\t\t(hide yes)\n'
        f'\t\t(uuid "{uid(key)}")\n\t\t(effects\n\t\t\t(font\n\t\t\t\t(size 0.6 0.6)\n\t\t\t\t(thickness 0.1)\n\t\t\t)\n\t\t)\n\t)\n'
    )


def rect(layer: str, x0: float, y0: float, x1: float, y1: float, width: float, key: str) -> str:
    return (
        f"\t(fp_rect\n\t\t(start {fmt(x0)} {fmt(y0)})\n\t\t(end {fmt(x1)} {fmt(y1)})\n"
        f"\t\t(stroke\n\t\t\t(width {fmt(width)})\n\t\t\t(type solid)\n\t\t)\n\t\t(fill no)\n"
        f'\t\t(layer "{layer}")\n\t\t(uuid "{uid(key)}")\n\t)\n'
    )


def port(side: str) -> tuple[str, str]:
    host = side == "Host"
    name = f"HelixPort_{side}_2x05_P{PITCH}mm"
    size = HOST_PAD if host else MODULE_PAD
    pads = []
    for pin in range(1, 11):
        x, y = grid(pin)
        y = -y if host else y  # the back face mirrors a footprint top to bottom
        paste = "" if host else ' "F.Paste"'
        pads.append(
            f'\t(pad "{pin}" smd circle\n\t\t(at {fmt(x)} {fmt(y)})\n\t\t(size {fmt(size)} {fmt(size)})\n'
            f'\t\t(layers "F.Cu" "F.Mask"{paste})\n\t\t(uuid "{uid(f"{side}/{pin}")}")\n\t)'
        )
    cx, cy = 2 * PITCH + size / 2 + 0.2, PITCH / 2 + size / 2 + 0.2
    what = (
        "10 flat ENIG contact pads a module's spring pins land on"
        if host
        else "10 lands for surface-mount spring pins that meet the Core's pads"
    )
    return name, (
        f'(footprint "{name}"\n\t(version 20241229)\n\t(generator "make_footprints")\n\t(layer "F.Cu")\n'
        f'\t(descr "Helix Port, the {side.lower()} side: {what}, 2 x 5 on a {PITCH} mm grid")\n'
        '\t(tags "helix port pogo magnetic module")\n'
        + text("Reference", "J**", -cy - 0.8, "F.SilkS", f"{side}/ref")
        + text("Value", name, cy + 0.8, "F.Fab", f"{side}/value")
        + f"\t(attr smd{' exclude_from_pos_files exclude_from_bom' if host else ''})\n"
        + rect("F.CrtYd", -cx, -cy, cx, cy, 0.05, f"{side}/court")
        + rect("F.Fab", -cx + 0.1, -cy + 0.1, cx - 0.1, cy - 0.1, 0.1, f"{side}/fab")
        # The assembly drawing names the part by this text.
        + '\t(fp_text user "${REFERENCE}"\n\t\t(at 0 0 0)\n\t\t(layer "F.Fab")\n'
        + f'\t\t(uuid "{uid(f"{side}/fabref")}")\n'
        + "\t\t(effects\n\t\t\t(font\n\t\t\t\t(size 0.6 0.6)\n\t\t\t\t(thickness 0.1)\n\t\t\t)\n\t\t)\n\t)\n"
        + "\n".join(pads)
        + "\n)\n"
    )


def main() -> None:
    lib = HERE / "Helix.pretty"
    lib.mkdir(exist_ok=True)
    for side in ("Host", "Module"):
        name, body = port(side)
        (lib / f"{name}.kicad_mod").write_text(body, encoding="utf-8", newline="\n")
        print(f"wrote {name}")


if __name__ == "__main__":
    main()
