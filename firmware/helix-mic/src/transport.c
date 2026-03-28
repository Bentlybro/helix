/*
 * Helix-Mic - BLE Transport Implementation
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(transport, CONFIG_LOG_DEFAULT_LEVEL);

#include "transport.h"
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/gatt.h>

/* Connection state */
static struct bt_conn *current_conn;
static volatile bool is_connected = false;
static transport_conn_cb_t conn_callback = NULL;

/* Notification data - 700 bytes to hold 20ms PCM frame (640 bytes) + header */
static uint8_t audio_data[700];
static uint8_t battery_level = 100;

/* Mic start function pointer for direct call on connect */
static void (*mic_start_fn)(void) = NULL;

/* Define UUIDs using BT_UUID_128_ENCODE macro */
#define HELIX_SVC_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649A0, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)
#define HELIX_AUDIO_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649B0, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)
#define HELIX_BATTERY_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649A2, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)

static struct bt_uuid_128 helix_svc_uuid = BT_UUID_INIT_128(HELIX_SVC_UUID_VAL);
static struct bt_uuid_128 helix_audio_uuid = BT_UUID_INIT_128(HELIX_AUDIO_UUID_VAL);
static struct bt_uuid_128 helix_battery_uuid = BT_UUID_INIT_128(HELIX_BATTERY_UUID_VAL);

/* CCC configuration callbacks */
static void audio_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    LOG_INF("Audio notifications %s", value ? "enabled" : "disabled");
    if (value == BT_GATT_CCC_NOTIFY) {
        /* App has subscribed — now safe to start streaming */
        if (conn_callback) {
            conn_callback(true);
        }
    } else {
        /* App unsubscribed — stop streaming */
        if (conn_callback) {
            conn_callback(false);
        }
    }
}

static void battery_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    LOG_INF("Battery notifications %s", value ? "enabled" : "disabled");
}

/* Battery read callback */
static ssize_t read_battery(struct bt_conn *conn, const struct bt_gatt_attr *attr,
                            void *buf, uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset, 
                             &battery_level, sizeof(battery_level));
}

/* Custom service definition */
BT_GATT_SERVICE_DEFINE(helix_svc,
    /* Helix Service */
    BT_GATT_PRIMARY_SERVICE(&helix_svc_uuid),
    
    /* Audio Characteristic - NOTIFY only */
    BT_GATT_CHARACTERISTIC(&helix_audio_uuid,
                           BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_NONE,
                           NULL, NULL, NULL),
    BT_GATT_CCC(audio_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
    
    /* Battery Characteristic - READ + NOTIFY */
    BT_GATT_CHARACTERISTIC(&helix_battery_uuid,
                           BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_READ,
                           read_battery, NULL, NULL),
    BT_GATT_CCC(battery_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* Advertising data */
static const struct bt_data ad[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
    BT_DATA(BT_DATA_NAME_COMPLETE, "Helix-Mic", 9),
};

/* Scan response - include service UUID */
static const struct bt_data sd[] = {
    BT_DATA_BYTES(BT_DATA_UUID128_ALL, HELIX_SVC_UUID_VAL),
};

static void connected(struct bt_conn *conn, uint8_t err)
{
    if (err) {
        LOG_ERR("Connection failed (err %u)", err);
        return;
    }
    
    current_conn = bt_conn_ref(conn);
    is_connected = true;
    
    LOG_INF("Connected — waiting for app to subscribe before streaming");
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    LOG_INF("Disconnected (reason %u)", reason);
    
    if (current_conn) {
        bt_conn_unref(current_conn);
        current_conn = NULL;
    }
    
    is_connected = false;
    
    if (conn_callback) {
        conn_callback(false);
    }
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected = connected,
    .disconnected = disconnected,
};

int transport_init(void)
{
    int err;
    
    err = bt_enable(NULL);
    if (err) {
        LOG_ERR("Bluetooth enable failed: %d", err);
        return err;
    }
    
    LOG_INF("Bluetooth initialized");
    return 0;
}

int transport_start_advertising(void)
{
    int err;
    
    err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
    if (err) {
        LOG_ERR("Advertising failed: %d", err);
        return err;
    }
    
    LOG_INF("Advertising started");
    return 0;
}

int transport_send_audio(const uint8_t *data, size_t len, uint16_t seq, uint8_t flags)
{
    if (!is_connected || !current_conn) {
        return -ENOTCONN;
    }
    
    /* Build packet: [seq_num:2][flags:1][data] */
    size_t total_len = 3 + len;
    if (total_len > sizeof(audio_data)) {
        total_len = sizeof(audio_data);
        len = total_len - 3;
    }
    
    audio_data[0] = seq & 0xFF;
    audio_data[1] = (seq >> 8) & 0xFF;
    audio_data[2] = flags;
    memcpy(&audio_data[3], data, len);
    
    return bt_gatt_notify(current_conn, &helix_svc.attrs[2], audio_data, total_len);
}

int transport_update_battery(uint8_t level)
{
    battery_level = level;
    
    if (is_connected && current_conn) {
        return bt_gatt_notify(current_conn, &helix_svc.attrs[5], &battery_level, 1);
    }
    
    return 0;
}

bool transport_is_connected(void)
{
    return is_connected;
}

void transport_set_conn_callback(transport_conn_cb_t cb)
{
    conn_callback = cb;
}

void transport_set_mic_start(void (*fn)(void))
{
    mic_start_fn = fn;
}
