/*
 * Helix-Mic - BLE Transport Layer
 *
 * Rewritten to fix BLE connection crash on nRF52840.
 * Uses dynamic service registration, PHY/DLE/MTU negotiation on connect.
 */

#ifndef TRANSPORT_H
#define TRANSPORT_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stddef.h>

/* Initialize BLE transport (enables BT, registers GATT service) */
int transport_init(void);

/* Start advertising */
int transport_start_advertising(void);

/* Send audio data notification */
int transport_send_audio(const uint8_t *data, size_t len, uint16_t seq, uint8_t flags);

/* Update battery state: percent (0-100, or 0xFF = unknown) + millivolts.
 * Sends a 3-byte notification [percent, mv_lo, mv_hi] if subscribed. */
int transport_update_battery(uint8_t level, uint16_t millivolts);

/* Check if a client is connected */
bool transport_is_connected(void);

/* Connection state callback type */
typedef void (*transport_conn_cb_t)(bool connected);
void transport_set_conn_callback(transport_conn_cb_t cb);

/* Set mic start function (called on connect for compatibility) */
void transport_set_mic_start(void (*fn)(void));

#endif /* TRANSPORT_H */
