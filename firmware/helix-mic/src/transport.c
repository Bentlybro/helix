/*
 * Helix-Mic - BLE Transport Implementation
 *
 * Rewritten to fix hard-fault crash ~5s after BLE connection.
 * Root cause: missing PHY update, data length extension, and MTU exchange
 * on connect, plus insufficient stack sizes for BLE operations.
 *
 * This version uses dynamic GATT service registration and negotiates
 * PHY 2M, max data length, and MTU immediately on connection.
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(transport, CONFIG_LOG_DEFAULT_LEVEL);

#include "transport.h"

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/bluetooth/gatt.h>
#include <string.h>

/* ─── UUIDs ─────────────────────────────────────────────────────────── */

#define HELIX_SVC_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649A0, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)
#define HELIX_AUDIO_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649B0, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)
#define HELIX_BATTERY_UUID_VAL \
    BT_UUID_128_ENCODE(0x917649A2, 0xD98E, 0x11E5, 0x9EEC, 0x0002A5D5C51B)

static struct bt_uuid_128 helix_svc_uuid    = BT_UUID_INIT_128(HELIX_SVC_UUID_VAL);
static struct bt_uuid_128 helix_audio_uuid   = BT_UUID_INIT_128(HELIX_AUDIO_UUID_VAL);
static struct bt_uuid_128 helix_battery_uuid = BT_UUID_INIT_128(HELIX_BATTERY_UUID_VAL);

/* ─── State ─────────────────────────────────────────────────────────── */

static struct bt_conn *current_conn;
static volatile bool is_connected;
static transport_conn_cb_t conn_callback;
static void (*mic_start_fn)(void);

/* Audio packet buffer: 3-byte header + up to 700 bytes payload */
static uint8_t audio_pkt[703];
static uint8_t battery_level = 100;

/* Negotiated MTU (default minimum, updated after exchange) */
static uint16_t current_mtu = 23;

/* ─── CCC callbacks ─────────────────────────────────────────────────── */

static void audio_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    LOG_INF("Audio notifications %s", value ? "enabled" : "disabled");
    if (value == BT_GATT_CCC_NOTIFY) {
        if (conn_callback) {
            conn_callback(true);
        }
    } else {
        if (conn_callback) {
            conn_callback(false);
        }
    }
}

static void battery_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
    LOG_INF("Battery notifications %s", value ? "enabled" : "disabled");
}

/* ─── Battery read handler ──────────────────────────────────────────── */

static ssize_t read_battery(struct bt_conn *conn,
                            const struct bt_gatt_attr *attr,
                            void *buf, uint16_t len, uint16_t offset)
{
    return bt_gatt_attr_read(conn, attr, buf, len, offset,
                             &battery_level, sizeof(battery_level));
}

/* ─── Dynamic GATT service ──────────────────────────────────────────── */

/* Attribute table for the Helix service (registered dynamically) */
static struct bt_gatt_attr helix_attrs[] = {
    /* [0] Primary Service */
    BT_GATT_PRIMARY_SERVICE(&helix_svc_uuid),

    /* [1] Audio Characteristic Declaration */
    BT_GATT_CHARACTERISTIC(&helix_audio_uuid.uuid,
                           BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_NONE,
                           NULL, NULL, NULL),
    /* [2] is the auto-generated value attr from the macro above */

    /* [3] Audio CCC */
    BT_GATT_CCC(audio_ccc_changed,
                 BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),

    /* [4] Battery Characteristic Declaration */
    BT_GATT_CHARACTERISTIC(&helix_battery_uuid.uuid,
                           BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
                           BT_GATT_PERM_READ,
                           read_battery, NULL, NULL),
    /* [5] is the auto-generated value attr */

    /* [6] Battery CCC */
    BT_GATT_CCC(battery_ccc_changed,
                 BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
};

static struct bt_gatt_service helix_svc = BT_GATT_SERVICE(helix_attrs);

/*
 * Attribute indices (after macro expansion):
 *   [0] Primary Service
 *   [1] Audio Char Declaration
 *   [2] Audio Char Value       <-- notify target for audio
 *   [3] Audio CCC
 *   [4] Battery Char Declaration
 *   [5] Battery Char Value     <-- notify target for battery
 *   [6] Battery CCC
 */
#define AUDIO_ATTR_IDX   2
#define BATTERY_ATTR_IDX 5

/* ─── Connection parameter negotiation ──────────────────────────────── */

static void update_phy(struct bt_conn *conn)
{
    int err = bt_conn_le_phy_update(conn, BT_CONN_LE_PHY_PARAM_2M);
    if (err) {
        LOG_WRN("PHY update request failed: %d", err);
    }
}

static void update_data_length(struct bt_conn *conn)
{
    struct bt_conn_le_data_len_param param = {
        .tx_max_len  = BT_GAP_DATA_LEN_MAX,
        .tx_max_time = BT_GAP_DATA_TIME_MAX,
    };
    int err = bt_conn_le_data_len_update(conn, &param);
    if (err) {
        LOG_WRN("Data length update failed: %d", err);
    }
}

static struct bt_gatt_exchange_params exchange_params;

static void exchange_func(struct bt_conn *conn, uint8_t att_err,
                          struct bt_gatt_exchange_params *params)
{
    if (!att_err) {
        current_mtu = bt_gatt_get_mtu(conn);
        LOG_INF("MTU exchanged: %u", current_mtu);
    } else {
        LOG_WRN("MTU exchange error: %u", att_err);
    }
}

static void update_mtu(struct bt_conn *conn)
{
    exchange_params.func = exchange_func;
    int err = bt_gatt_exchange_mtu(conn, &exchange_params);
    if (err) {
        LOG_WRN("MTU exchange request failed: %d", err);
    }
}

/* ─── Advertising ───────────────────────────────────────────────────── */

static const struct bt_data ad[] = {
    BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
    BT_DATA(BT_DATA_NAME_COMPLETE, "Helix-Mic", 9),
};

static const struct bt_data sd[] = {
    BT_DATA_BYTES(BT_DATA_UUID128_ALL, HELIX_SVC_UUID_VAL),
};

/* ─── Connection callbacks ──────────────────────────────────────────── */

static void connected(struct bt_conn *conn, uint8_t err)
{
    if (err) {
        LOG_ERR("Connection failed (err %u)", err);
        return;
    }

    LOG_INF("Connected");

    current_conn = bt_conn_ref(conn);
    is_connected = true;

    /* Negotiate optimal link parameters immediately */
    update_phy(conn);
    update_data_length(conn);
    update_mtu(conn);

    LOG_INF("Waiting for client to subscribe before streaming");
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
    LOG_INF("Disconnected (reason %u)", reason);

    if (current_conn) {
        bt_conn_unref(current_conn);
        current_conn = NULL;
    }

    is_connected = false;
    current_mtu = 23;

    if (conn_callback) {
        conn_callback(false);
    }

    /* Restart advertising automatically */
    int err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad),
                              sd, ARRAY_SIZE(sd));
    if (err) {
        LOG_ERR("Re-advertising failed: %d", err);
    } else {
        LOG_INF("Advertising restarted");
    }
}

static void le_phy_updated(struct bt_conn *conn,
                           struct bt_conn_le_phy_info *param)
{
    LOG_INF("PHY updated: TX=%u RX=%u", param->tx_phy, param->rx_phy);
}

static void le_data_len_updated(struct bt_conn *conn,
                                struct bt_conn_le_data_len_info *info)
{
    LOG_INF("Data length updated: TX len=%u time=%u, RX len=%u time=%u",
            info->tx_max_len, info->tx_max_time,
            info->rx_max_len, info->rx_max_time);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
    .connected        = connected,
    .disconnected     = disconnected,
    .le_phy_updated   = le_phy_updated,
    .le_data_len_updated = le_data_len_updated,
};

/* ─── Public API ────────────────────────────────────────────────────── */

int transport_init(void)
{
    int err;

    err = bt_enable(NULL);
    if (err) {
        LOG_ERR("Bluetooth enable failed: %d", err);
        return err;
    }

    LOG_INF("Bluetooth initialized");

    /* Register GATT service dynamically */
    err = bt_gatt_service_register(&helix_svc);
    if (err) {
        LOG_ERR("GATT service register failed: %d", err);
        return err;
    }

    LOG_INF("Helix GATT service registered");
    return 0;
}

int transport_start_advertising(void)
{
    int err = bt_le_adv_start(BT_LE_ADV_CONN, ad, ARRAY_SIZE(ad),
                              sd, ARRAY_SIZE(sd));
    if (err) {
        LOG_ERR("Advertising failed: %d", err);
        return err;
    }

    LOG_INF("Advertising started");
    return 0;
}

int transport_send_audio(const uint8_t *data, size_t len,
                         uint16_t seq, uint8_t flags)
{
    if (!is_connected || !current_conn) {
        return -ENOTCONN;
    }

    /* Build packet: [seq_num:2][flags:1][payload] */
    size_t total = 3 + len;
    if (total > sizeof(audio_pkt)) {
        total = sizeof(audio_pkt);
        len = total - 3;
    }

    /* Clamp to negotiated MTU (ATT header = 3 bytes) */
    uint16_t max_payload = current_mtu - 3;
    if (total > max_payload) {
        total = max_payload;
        len = total - 3;
    }

    audio_pkt[0] = seq & 0xFF;
    audio_pkt[1] = (seq >> 8) & 0xFF;
    audio_pkt[2] = flags;
    memcpy(&audio_pkt[3], data, len);

    struct bt_gatt_notify_params params = {
        .attr = &helix_attrs[AUDIO_ATTR_IDX],
        .data = audio_pkt,
        .len  = total,
    };

    return bt_gatt_notify_cb(current_conn, &params);
}

int transport_update_battery(uint8_t level)
{
    battery_level = level;

    if (!is_connected || !current_conn) {
        return 0;
    }

    struct bt_gatt_notify_params params = {
        .attr = &helix_attrs[BATTERY_ATTR_IDX],
        .data = &battery_level,
        .len  = sizeof(battery_level),
    };

    return bt_gatt_notify_cb(current_conn, &params);
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
