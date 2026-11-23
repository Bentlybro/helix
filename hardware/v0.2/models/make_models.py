"""3D bodies for the parts KiCad's library draws without one, written beside this file.

    python3 models/make_models.py      # from the version's folder

Each body is a few boxes, sized from the part's outline drawing. A .wrl file is in units of
0.1 inch (KiCad scales VRML by 2.54), x to the right, y away from the viewer's bottom edge, z up.
"""

from __future__ import annotations

from pathlib import Path

HERE = Path(__file__).resolve().parent

SILVER, GOLD = (0.78, 0.78, 0.80), (0.83, 0.69, 0.22)
BLACK, BOARD, WHITE, GLASS = (0.07, 0.07, 0.08), (0.10, 0.22, 0.14), (0.92, 0.92, 0.90), (0.25, 0.05, 0.05)


def box(size, at, colour, shine=0.3) -> str:
    """A box `size` mm, its middle at `at` mm."""
    s = " ".join(f"{v / 2.54:.4f}" for v in size)
    t = " ".join(f"{v / 2.54:.4f}" for v in at)
    c = " ".join(f"{v:.2f}" for v in colour)
    return (
        f"Transform {{ translation {t} children [ Shape {{ appearance Appearance {{ material Material {{ "
        f"diffuseColor {c} specularColor 0.4 0.4 0.4 shininess {shine} }} }} geometry Box {{ size {s} }} }} ] }}\n"
    )


def write(name: str, boxes: list[str]) -> None:
    (HERE / name).write_text("#VRML V2.0 utf8\n" + "".join(boxes), encoding="utf-8", newline="\n")
    print(f"wrote {name}")


def main() -> None:
    # Raytac MDBT50Q-1MV2: 10.5 x 15.5 x 2.05 mm. A 0.5 mm substrate, a shield can over the chip, the
    # chip antenna on the bare end (the footprint's top, +y here).
    write("mdbt50q.wrl", [
        box((10.5, 15.5, 0.5), (0, 0, 0.25), BOARD, 0.1),
        box((9.7, 10.4, 1.5), (0, -2.2, 1.25), SILVER, 0.6),
        box((5.0, 1.2, 1.0), (0, 6.3, 1.0), WHITE, 0.2),
    ])  # fmt: skip
    # Knowles SPH0641LU4H-1: a 3.5 x 2.65 x 0.98 mm can on its substrate; the port is underneath.
    write("sph0641.wrl", [
        box((2.65, 3.5, 0.3), (0, 0, 0.15), BOARD, 0.1),
        box((2.35, 3.2, 0.68), (0, 0, 0.64), SILVER, 0.6),
    ])  # fmt: skip
    # Maxim MAX30102: 3.3 x 5.6 x 1.55 mm, black, with a window over its LEDs and one over its diode.
    write("max30102.wrl", [
        box((3.3, 5.6, 1.5), (0, 0, 0.75), BLACK, 0.2),
        box((2.0, 1.4, 0.06), (0, 1.5, 1.53), GLASS, 0.9),
        box((2.0, 1.8, 0.06), (0, -1.2, 1.53), GLASS, 0.9),
    ])  # fmt: skip
    # HRO TYPE-C-31-M-12, a 16-pin USB-C socket: a steel shell 8.94 x 7.35 x 3.26 mm with a dark mouth.
    # The footprint's origin is 2.6 mm inside the board's edge, and the shell's mouth 1 mm outside it:
    # flush with the outside of a 1 mm wall. Sized from the outline, not yet from the maker's drawing.
    write("usb_c.wrl", [
        box((8.94, 7.35, 3.26), (0, -0.075, 1.63), SILVER, 0.7),
        box((7.6, 0.12, 2.2), (0, -3.77, 1.63), BLACK, 0.1),
    ])  # fmt: skip


if __name__ == "__main__":
    main()
