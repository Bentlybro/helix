"""Helix Health v0.1: the first module for the Helix Port. 13 x 16 mm, 4 layers, 0.8 mm.

It sits between the Core and the skin behind the ear, where the bone is close under thin skin and
hearing-aid makers already read a pulse. The face that meets the Core carries J1, the port's 10
spring pins, and the supply; the face on the skin carries the 2 sensors:

- U1, MAX30102: red and infrared light into the skin and a photodiode, for pulse and blood oxygen.
- U2, TMP117: skin temperature to 0.1 C.

Everything runs from VMOD, the supply the Core switches: the module draws nothing until the firmware
turns it on. U3 makes the sensors' 1.8 V from it; the MAX30102's LEDs take VMOD itself (3.1 V at
the least, MAX30102 datasheet, so the firmware reads no pulse with the cell under 3.2 V). Both
sensors' I2C pins take the Core's 3.0 V bus with their own supply at 1.8 V or off. The MAX30102's
interrupt, open drain, is MOD_INT; the TMP117 is read on a timer and its ALERT stays open.

I2C addresses: MAX30102 0x57, TMP117 0x48 (ADD0 to ground).

    workcenter design health/design.py --route     # from the version's folder
"""

from __future__ import annotations

import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import helix  # noqa: E402
from helix import C0402, SOT23_5, at  # noqa: E402

LENGTH = 16.0
USED = ("GND", "SDA", "SCL", "MOD_INT", "VMOD")  # the port's contacts this module uses


def build():
    d = helix.board("health", uuid.UUID("b83d5a0e-9c47-4f6b-8d12-7e0c3a9f5b64"), LENGTH, 2.5, sheet="A4")
    d.power_nets = {"GND": "GND", "VMOD": "VDD", "1V8": "+1V8"}
    d.flagged_nets = ["GND", "VMOD"]  # both arrive through the port; U3's output drives 1V8

    def add(ref, lib, value, fp, nets, where, rot=0.0, layer="F.Cu", descr=""):
        return d.add(ref, lib, value, fp, sch_at=(0, 0), pcb_at=where, pcb_rot=rot, pcb_layer=layer, nets=nets,
                     descr=descr)  # fmt: skip

    def c(ref, value, a, where, rot=0.0, layer="F.Cu"):
        return add(ref, "Device:C", value, C0402, {"1": a, "2": "GND"}, where, rot, layer)

    # VCHG, 3V0 and USB stay open on this module.
    helix.port(d, "module", at(6.5, 3.6), {k: v for k, v in helix.PORT.items() if v in USED})
    d.model("J1", "${KIPRJMOD}/../models/port_pins.wrl")

    # ---- the face that meets the Core: the 1.8 V supply.
    add("U3", "Regulator_Linear:TLV75518PDBV", "TLV75518", SOT23_5,
        {"1": "VMOD", "2": "GND", "3": "VMOD", "5": "1V8"}, at(3.2, 9.2), descr="1.8 V 500 mA regulator, TI")  # fmt: skip
    c("C1", "1u", "VMOD", at(3.2, 6.75))  # U3's input
    # The 3 capacitors of 1V8 stand in a row, their 1V8 ends level with U3's output.
    c("C2", "1u", "1V8", at(6.6, 9.2), 270)  # U3's output
    c("C3", "100n", "1V8", at(7.9, 9.2), 270)  # U1's VDD
    c("C5", "100n", "1V8", at(9.2, 9.2), 270)  # U2's V+
    c("C4", "4.7u", "VMOD", at(11.4, 9.0), 270)  # U1's LED supply: its pulses are up to 50 mA
    d.track("1V8", [d.pad_at("U3", "5"), d.pad_at("C2", "1"), d.pad_at("C3", "1"), d.pad_at("C5", "1")], "F.Cu")

    # ---- the face on the skin.
    add("U1", "Sensor:MAX30102", "MAX30102", "OptoDevice:Maxim_OLGA-14_3.3x5.6mm_P0.8mm",
        {"2": "SCL", "3": "SDA", "4": "GND", "9": "VMOD", "10": "VMOD", "11": "1V8", "12": "GND", "13": "MOD_INT"},
        at(6.5, 8.6), 90, "B.Cu", "Pulse oximeter and heart-rate sensor, Analog Devices")  # fmt: skip
    d.model("U1", "${KIPRJMOD}/../models/max30102.wrl", replace=True)
    add("U2", "Sensor_Temperature:TMP117xxDRV", "TMP117", "Package_SON:WSON-6-1EP_2x2mm_P0.65mm_EP1x1.6mm",
        {"1": "SCL", "2": "GND", "4": "GND", "5": "1V8", "6": "SDA"},
        at(6.5, 13.6), 0, "B.Cu", "Temperature sensor, 0.1 C, TI")  # fmt: skip

    # U3's input (1) to its enable (3), round its ground pin on the outside, by hand.
    (x1, y1), (x3, y3) = d.pad_at("U3", "1"), d.pad_at("U3", "3")
    d.track("VMOD", [(x1, y1), (x1 - 1.0, y1), (x1 - 1.0, y3), (x3, y3)], "F.Cu")

    d.zone("GND", layers=("In1.Cu",), connect="solid")
    # No via under the name: the legend stays whole.
    d.keepout([at(1.6, 11.3), at(11.4, 11.3), at(11.4, 12.7), at(1.6, 12.7)], layers=("F.Cu",), name="name")
    d.defined_nets = {
        "SDA": "4.7k to 3V0 on the Core (R1), through the port",
        "SCL": "4.7k to 3V0 on the Core (R2), through the port",
        "MOD_INT": "10k to 3V0 on the Core (R4), through the port",
    }
    d.check_pulls = True
    helix.finish(d, [("HEALTH v0.1", *at(6.5, 12.0), "F.SilkS", 1.0)])
    return d
