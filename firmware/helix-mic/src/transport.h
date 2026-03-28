/*
 * Helix-Mic - BLE Transport Layer
 */

#ifndef TRANSPORT_H
#define TRANSPORT_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stddef.h>

/* Initialize BLE transport */
int transport_init(void);

/* Start advertising */
int transport_start_advertising(void);

/* Send audio data */
int transport_send_audio(const uint8_t *data, size_t len, uint16_t seq, uint8_t flags);

/* Update battery level */
int transport_update_battery(uint8_t level);

/* Check if connected */
bool transport_is_connected(void);

/* Connection state callback */
typedef void (*transport_conn_cb_t)(bool connected);
void transport_set_conn_callback(transport_conn_cb_t cb);

#endif /* TRANSPORT_H */
