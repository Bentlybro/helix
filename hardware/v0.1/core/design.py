"""Helix Core v0.1: the part of Helix that is worn behind the ear. 13 x 38 mm, 4 layers, 0.8 mm.

One board replaces the Seeed XIAO nRF52840 Sense of the first prototypes and merges the IMU and Mic
modules: the same radio chip (nRF52840, here in Raytac's MDBT50Q module with its own antenna), the
same IMU (LSM6DS3) on the same I2C address, and a PDM microphone, so the firmware of both modules
carries over with a new pin map.

From the antenna's end (top, y = 100) to the button's end:

- The antenna, over bare board: no copper on any layer under it.
- U1, the radio module. On the back face under it, J1: the Helix Port's 10 pads, with a TVS diode on
  each line a dock drives (VCHG, USB D+, USB D-), and 3 pads for SWD.
- U2, the IMU; U4, a 3.0 V regulator from the cell; U3, the charger, 50 mA from VCHG.
- U5, a load switch: the cell to the port's VMOD, on only when the firmware asks, with a soft start
  so a module that snaps on does not dip the cell.
- BT1 and BT2, the cell's 2 pads. The cell carries its own protection circuit.
- On the back face, MK1, the microphone: its sound port is a hole through the board to the top face.
- D4, a light, and SW1, the one button.

    workcenter design core/design.py --route     # from the version's folder
"""

from __future__ import annotations

import math
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import helix  # noqa: E402
from helix import C0402, R0402, SOT23_5, SOT23_6, WIDTH, X0, Y0, at  # noqa: E402

LENGTH = 38.0
ANTENNA_END = 4.5  # the antenna's strip of bare board, from the top edge
U1_AT = at(6.5, 8.75)
PORT_AT = at(6.5, 10.3)


def ground_plane(d) -> list[tuple[float, float]]:
    """The board less the antenna's strip, 0.3 mm inside the edge, the bottom corners rounded."""
    e, r = d.edge_clearance, 4.0 - d.edge_clearance
    x1, x2, y1, y2 = X0 + e, X0 + WIDTH - e, Y0 + ANTENNA_END + 0.3, Y0 + LENGTH - e
    right = [(x2 - r + r * math.cos(a), y2 - r + r * math.sin(a)) for a in (math.radians(k * 15) for k in range(7))]
    left = [(x1 + r + r * math.cos(a), y2 - r + r * math.sin(a)) for a in (math.radians(90 + k * 15) for k in range(7))]
    return [(x1, y1), (x2, y1), *[(round(x, 4), round(y, 4)) for x, y in right + left]]


def build():
    d = helix.board("core", uuid.UUID("0f6f8c0a-51c9-4e55-9a3e-6f5b0a4d7c21"), LENGTH, (2.5, 2.5, 4.0, 4.0))
    d.power_nets = {"GND": "GND", "3V0": "+3V0", "VBAT": "+BATT", "VCHG": "VBUS"}
    # GND arrives at the cell's pad and VCHG at the port: no pin drives either. The charger's output
    # drives VBAT and the regulator's 3V0.
    d.flagged_nets = ["GND", "VCHG"]

    def add(ref, lib, value, fp, nets, where, rot=0.0, layer="F.Cu", descr=""):
        return d.add(ref, lib, value, fp, sch_at=(0, 0), pcb_at=where, pcb_rot=rot, pcb_layer=layer, nets=nets,
                     descr=descr)  # fmt: skip

    def r(ref, value, a, b, where, rot=0.0, layer="F.Cu"):
        return add(ref, "Device:R", value, R0402, {"1": a, "2": b}, where, rot, layer)

    def c(ref, value, a, where, rot=0.0, layer="F.Cu"):
        return add(ref, "Device:C", value, C0402, {"1": a, "2": "GND"}, where, rot, layer)

    # ---- the radio. Pin numbers from KiCad's symbol (kicadgen.py pins RF_Module:MDBT50Q-1MV2).
    # Normal voltage mode: VDD and VDDH together on 3V0, DCCH open. Every signal is on a pin Nordic
    # rates for full speed; the pins beside the radio (P0.02, P0.03, P0.28 to P0.31, P1.01 to P1.07,
    # P1.10 to P1.15) are left open.
    radio = {
        "1": "GND", "2": "GND", "15": "GND", "33": "GND", "55": "GND",
        "28": "3V0", "30": "3V0",  # VDD, VDDH
        "32": "VCHG",  # VBUS: USB wakes when a dock is on
        "34": "USB_DM", "35": "USB_DP",
        "19": "SDA", "16": "SCL",  # P0.26, P0.27
        "22": "PDM_CLK", "24": "PDM_DATA",  # P0.06, P0.08
        "20": "BAT_SENSE",  # P0.04, AIN2
        "21": "IMU_INT",  # P0.05
        "23": "MOD_INT",  # P0.07
        "25": "MOD_EN",  # P1.08
        "26": "CHG_STAT",  # P1.09
        "27": "BTN",  # P0.11
        "29": "LED",  # P0.12
        "40": "NRESET",  # P0.18
        "51": "SWDIO", "53": "SWDCLK",
    }  # fmt: skip
    add("U1", "RF_Module:MDBT50Q-1MV2", "MDBT50Q-1MV2", "RF_Module:Raytac_MDBT50Q", radio, U1_AT,
        descr="nRF52840 Bluetooth LE module with a chip antenna, Raytac")  # fmt: skip
    d.model("U1", "${KIPRJMOD}/../models/mdbt50q.wrl", replace=True)
    c("C1", "4.7u", "3V0", at(6.2, 18.1))
    c("C2", "100n", "3V0", at(8.1, 18.1))
    c("C3", "4.7u", "VCHG", at(10.0, 18.1))

    # ---- motion: SA0 low (R9), address 0x6A, as on the XIAO; CS high for I2C.
    add("U2", "Sensor_Motion:LSM6DS3", "LSM6DS3TR-C", "Package_LGA:LGA-14_3x2.5mm_P0.5mm_LayoutBorder3x4y",
        {"1": "IMU_SA0", "2": "IMU_SA0", "3": "GND", "4": "IMU_INT", "5": "3V0", "6": "GND", "7": "GND", "8": "3V0",
         "12": "3V0", "13": "SCL", "14": "SDA"}, at(2.6, 21.0), descr="6-axis IMU, ST")  # fmt: skip
    c("C4", "100n", "3V0", at(2.6, 23.3))
    r("R9", "10k", "IMU_SA0", "GND", at(2.6, 18.6))  # SA0 low; the unused aux bus's SDx with it
    r("R1", "4.7k", "SDA", "3V0", at(1.6, 24.7))
    r("R2", "4.7k", "SCL", "3V0", at(3.6, 24.7))

    # ---- power. U3 charges at 1000 V / 20k = 50 mA (MCP73831 datasheet, section 5.1.2): half an
    # hour's rate for a 100 mAh cell. U4 holds 3.0 V until the cell is nearly empty.
    add("U3", "Battery_Management:MCP73831-2-OT", "MCP73831", SOT23_5,
        {"1": "CHG_STAT", "2": "GND", "3": "VBAT", "4": "VCHG", "5": "PROG"}, at(10.6, 21.4), 90,
        descr="Single-cell Li-ion charger, Microchip")  # fmt: skip
    add("U4", "Regulator_Linear:TLV75530PDBV", "TLV75530", SOT23_5,
        {"1": "VBAT", "2": "GND", "3": "VBAT", "5": "3V0"}, at(6.9, 21.4), 90,
        descr="3.0 V 500 mA regulator, TI")  # fmt: skip
    add("U5", "Power_Management:TPS22917DBV", "TPS22917", SOT23_6,
        {"1": "VBAT", "2": "GND", "3": "MOD_EN", "4": "MOD_CT", "5": "MOD_QOD", "6": "VMOD"}, at(10.6, 26.1), 270,
        descr="Load switch with slew rate control and output discharge, TI")  # fmt: skip
    c("C5", "4.7u", "VBAT", at(5.6, 24.7))
    c("C6", "1u", "3V0", at(7.6, 24.7))
    r("R3", "20k", "PROG", "GND", at(5.6, 25.9))
    c("C7", "1u", "VMOD", at(7.6, 25.9))
    r("R4", "10k", "MOD_INT", "3V0", at(1.6, 25.9))
    r("R5", "100k", "MOD_EN", "GND", at(3.6, 25.9))  # modules stay off until the firmware asks
    # The cell's voltage, halved for the ADC: 2 uA from the cell.
    r("R6", "1M", "VBAT", "BAT_SENSE", at(1.6, 27.1))
    r("R7", "1M", "BAT_SENSE", "GND", at(3.6, 27.1))
    c("C8", "100n", "BAT_SENSE", at(5.6, 27.1))
    c("C9", "1n", "MOD_CT", at(7.6, 27.1))  # VMOD rises in about 0.5 ms
    r("R10", "220", "MOD_QOD", "VMOD", at(7.6, 28.3))  # VMOD falls through it when the switch opens
    add("BT1", "Connector:TestPoint", "CELL+", "TestPoint:TestPoint_Pad_1.5x1.5mm", {"1": "VBAT"}, at(2.0, 29.9))
    add("BT2", "Connector:TestPoint", "CELL-", "TestPoint:TestPoint_Pad_1.5x1.5mm", {"1": "GND"}, at(11.0, 29.9))

    # ---- the wearer's side: a light and a button.
    add("D4", "Device:LED", "WHITE", "LED_SMD:LED_0402_1005Metric", {"1": "LED_K", "2": "3V0"}, at(2.2, 32.2))
    r("R8", "1k", "LED_K", "LED", at(2.2, 33.4))
    add("SW1", "Switch:SW_Push", "B3U-1000P", "Button_Switch_SMD:SW_SPST_B3U-1000P", {"1": "BTN", "2": "GND"},
        at(6.5, 35.0), descr="Tactile switch, Omron")  # fmt: skip

    # ---- the back face. The microphone's port is the hole its footprint drills through the board.
    add("MK1", "Sensor_Audio:SPH0641LU4H-1", "SPH0641LU4H-1", "Sensor_Audio:Knowles_LGA-5_3.5x2.65mm",
        {"1": "PDM_DATA", "2": "GND", "3": "GND", "4": "PDM_CLK", "5": "3V0"}, at(6.5, 30.3), layer="B.Cu",
        descr="PDM MEMS microphone, bottom port, Knowles")  # fmt: skip
    d.model("MK1", "${KIPRJMOD}/../models/sph0641.wrl", replace=True)
    c("C10", "100n", "3V0", at(3.0, 31.6), layer="B.Cu")
    helix.port(d, "host", PORT_AT)
    for ref, net, dx in (("D1", "VCHG", 2.5), ("D2", "USB_DP", 6.5), ("D3", "USB_DM", 10.5)):
        add(ref, "Power_Protection:TPD1E05U06DYA", "TPD1E05U06", "Diode_SMD:D_SOD-523", {"1": net, "2": "GND"},
            at(dx, 6.6), layer="B.Cu", descr="TVS diode, 0.45 pF, TI")  # fmt: skip
    for ref, net, dx in (("TP1", "SWDIO", 3.5), ("TP2", "SWDCLK", 6.5), ("TP3", "NRESET", 9.5)):
        add(ref, "Connector:TestPoint", net, "TestPoint:TestPoint_Pad_D1.0mm", {"1": net}, at(dx, 15.2), layer="B.Cu")

    # ---- copper: nothing under the antenna, ground on In1 everywhere else.
    strip = [at(0, 0), at(WIDTH, 0), at(WIDTH, ANTENNA_END), at(0, ANTENNA_END)]
    d.keepout(strip, layers=("F.Cu", "In1.Cu", "In2.Cu", "B.Cu"), name="antenna")
    d.zone("GND", layers=("In1.Cu",), polygon=ground_plane(d), connect="solid")

    # VMOD, from the switch's output to the port's pad 10 at the board's other end: down the back face
    # along the right edge, by hand, since the router leaves it for last and then finds no way.
    (px, py), (ux, uy) = d.pad_at("J1", "10"), d.pad_at("U5", "6")
    lane = X0 + WIDTH - 0.8
    d.track("VMOD", [(px, py), (lane, py + (lane - px)), (lane, uy)], "B.Cu", width=0.25)
    d.via("VMOD", lane, uy)
    d.track("VMOD", [(lane, uy), (ux, uy)], "F.Cu", width=0.25)

    # No via under the 2 names: the legend stays whole.
    d.keepout([at(3.9, 30.9), at(9.1, 30.9), at(9.1, 32.3), at(3.9, 32.3)], layers=("F.Cu",), name="name, front")
    d.keepout([at(2.5, 34.9), at(10.5, 34.9), at(10.5, 36.3), at(2.5, 36.3)], layers=("B.Cu",), name="name, back")

    d.defined_nets = {
        "BTN": "P0.11's internal pull-up, set in firmware",
        "CHG_STAT": "P1.09's internal pull-up, set in firmware (the charger's STAT is open drain)",
        "IMU_INT": "the LSM6DS3 drives INT1 push-pull",
        "SWDIO": "the nRF52840's own pull-up on SWDIO",
        "SWDCLK": "the nRF52840's own pull-down on SWDCLK",
        "NRESET": "the nRF52840's own pull-up on P0.18 as nRESET",
        "PDM_CLK": "P0.06 drives the clock; the microphone sleeps when it stops",
        "USB_DP": "driven by a dock's host; the pads are open when worn",
        "USB_DM": "driven by a dock's host; the pads are open when worn",
    }
    d.check_pulls = True
    helix.finish(d, [("HELIX", *at(6.5, 31.6), "F.SilkS", 1.0), ("CORE v0.1", *at(6.5, 35.6), "B.SilkS", 1.0)])
    return d
