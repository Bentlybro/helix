"""The 3 files PCBWay's order form takes, written beside this file from the Core's fabrication export.

    python order/make_order.py        # from the version's folder, after `workcenter gates core`

- helix_core_gerbers.zip    the copper, mask, paste, legend and outline layers and the drill file
- helix_core_bom.csv        1 line per part value: designators, quantity, maker and maker's part number
- helix_core_centroid.csv   where each part sits, for the pick-and-place machine

The maker's part numbers are this file's own table. None has been checked against a distributor's
stock: PCBWay's assembly quote is the check.
"""

from __future__ import annotations

import csv
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
FAB = HERE.parent / "core" / "build" / "fab"
NAME = "helix_core"
LAYERS = (".gtl", ".g1", ".g2", ".gbl", ".gts", ".gbs", ".gtp", ".gbp", ".gto", ".gbo", ".gm1", ".drl", ".gbrjob")
NOT_FITTED = ("TP", "BT")  # bare pads: nothing to place

# (value, footprint's last word) -> maker, maker's part number, what it is
PARTS = {
    ("4.7u", "C_0402_1005Metric"): ("Murata", "GRM155R60J475ME47D", "Capacitor 4.7 uF 6.3 V X5R 0402"),
    ("1u", "C_0402_1005Metric"): ("Murata", "GRM155R61A105KE15D", "Capacitor 1 uF 10 V X5R 0402"),
    ("100n", "C_0402_1005Metric"): ("Murata", "GRM155R71C104KA88D", "Capacitor 100 nF 16 V X7R 0402"),
    ("1k", "R_0402_1005Metric"): ("Yageo", "RC0402FR-071KL", "Resistor 1 k 1 % 0402"),
    ("4.7k", "R_0402_1005Metric"): ("Yageo", "RC0402FR-074K7L", "Resistor 4.7 k 1 % 0402"),
    ("5.1k", "R_0402_1005Metric"): ("Yageo", "RC0402FR-075K1L", "Resistor 5.1 k 1 % 0402"),
    ("10k", "R_0402_1005Metric"): ("Yageo", "RC0402FR-0710KL", "Resistor 10 k 1 % 0402"),
    ("20k", "R_0402_1005Metric"): ("Yageo", "RC0402FR-0720KL", "Resistor 20 k 1 % 0402"),
    ("1M", "R_0402_1005Metric"): ("Yageo", "RC0402FR-071ML", "Resistor 1 M 1 % 0402"),
    ("MDBT50Q-1MV2", "Raytac_MDBT50Q"): ("Raytac", "MDBT50Q-1MV2", "nRF52840 Bluetooth module, chip antenna"),
    ("LSM6DS3TR-C", "LGA-14_3x2.5mm_P0.5mm_LayoutBorder3x4y"): ("STMicroelectronics", "LSM6DS3TR-C", "6-axis IMU, LGA-14"),
    ("MCP73831", "SOT-23-5"): ("Microchip", "MCP73831T-2ACI/OT", "Li-ion charger 4.2 V, SOT-23-5"),
    ("TLV75530", "SOT-23-5"): ("Texas Instruments", "TLV75530PDBVR", "Regulator 3.0 V 500 mA, SOT-23-5"),
    ("SPH0641LU4H-1", "Knowles_LGA-5_3.5x2.65mm"): ("Knowles", "SPH0641LU4H-1", "PDM MEMS microphone, bottom port"),
    ("TPD1E05U06", "D_SOD-523"): ("Texas Instruments", "TPD1E05U06DYAR", "TVS diode 5.5 V 0.45 pF, SOD-523"),
    ("BAT54XV2", "D_SOD-523"): ("onsemi", "BAT54XV2T1G", "Schottky diode 30 V 200 mA, SOD-523"),
    ("WHITE", "LED_0402_1005Metric"): ("any", "white LED, 0402, 5 mA", "Status light: PCBWay to propose a stocked part"),
    ("EVQ-PUJ02K", "Panasonic_EVQPUJ_EVQPUA"): ("Panasonic", "EVQ-PUJ02K", "Side-push tactile switch 4.7 x 3.5 x 1.65 mm"),
    ("USB-C", "USB_C_Receptacle_HRO_TYPE-C-31-M-12"): ("HRO (Korean Hroparts Elec)", "TYPE-C-31-M-12", "USB Type-C receptacle, 16 pin"),
}


def fitted(ref: str) -> bool:
    return not ref.startswith(NOT_FITTED)


def main() -> int:
    if not (FAB / "bom.csv").is_file():
        raise SystemExit(f"{FAB} holds no bom.csv: run `workcenter gates core` first")
    with zipfile.ZipFile(HERE / f"{NAME}_gerbers.zip", "w", zipfile.ZIP_DEFLATED) as z:
        files = sorted(f for f in (FAB / "gerbers").iterdir() if f.name.endswith(LAYERS))
        for f in files:
            z.write(f, f.name)
    print(f"{NAME}_gerbers.zip: {len(files)} files")

    groups: dict[tuple[str, str], list[str]] = {}
    for row in csv.DictReader((FAB / "bom.csv").open(encoding="utf-8")):
        for ref in row["Refs"].split(","):
            if fitted(ref.strip()):
                groups.setdefault((row["Value"], row["Footprint"].split(":")[-1]), []).append(ref.strip())
    missing = [k for k in groups if k not in PARTS]
    if missing:
        raise SystemExit(f"no maker's part number for {missing}: add each to PARTS")
    with (HERE / f"{NAME}_bom.csv").open("w", newline="", encoding="utf-8") as f:
        out = csv.writer(f)
        out.writerow(["Item", "Designator", "Qty", "Manufacturer", "Mfg Part #", "Description", "Package", "Type"])
        for n, (key, refs) in enumerate(sorted(groups.items(), key=lambda kv: kv[1][0]), 1):
            maker, mpn, what = PARTS[key]
            kind = "Thru-hole + SMD" if key[1].startswith("USB_C") else "SMD"
            out.writerow([n, ",".join(refs), len(refs), maker, mpn, what, key[1], kind])
    print(f"{NAME}_bom.csv: {len(groups)} lines, {sum(len(r) for r in groups.values())} parts")

    rows = [r for r in csv.DictReader((FAB / "placement.csv").open(encoding="utf-8")) if fitted(r["Ref"])]
    with (HERE / f"{NAME}_centroid.csv").open("w", newline="", encoding="utf-8") as f:
        out = csv.writer(f)
        out.writerow(["Designator", "Value", "Package", "Mid X (mm)", "Mid Y (mm)", "Rotation", "Layer"])
        for r in rows:
            out.writerow([r["Ref"], r["Val"], r["Package"], r["PosX"], r["PosY"], r["Rot"], r["Side"]])
    top = sum(r["Side"] == "top" for r in rows)
    print(f"{NAME}_centroid.csv: {len(rows)} parts, {top} on the top face, {len(rows) - top} on the back")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
