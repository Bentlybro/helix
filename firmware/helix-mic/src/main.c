/*
 * Helix-Mic - Main Entry Point
 * 
 * BLE audio streamer for XIAO nRF52840 Sense
 * - PDM mic capture at 16kHz 16-bit mono
 * - Raw PCM streaming over BLE (Opus fallback)
 * - Battery reporting
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(main, CONFIG_LOG_DEFAULT_LEVEL);

#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/devicetree.h>
#include <zephyr/dt-bindings/adc/nrf-saadc.h>
#include <zephyr/sys/ring_buffer.h>

#include "mic.h"
#include "codec.h"
#include "transport.h"
#include "led.h"

/* ─── Battery ADC (matches Tjoms99/xiao_sense_nrf52840_battery_lib) ─── */
#define BATTERY_ADC_CHANNEL  7
#define BATTERY_ADC_GAIN     ADC_GAIN_1_6    /* 0.6V ref × 6 = 3.6V range */
#define BATTERY_ADC_REF      ADC_REF_INTERNAL
#define BATTERY_ADC_RES      12
#define BATTERY_R1           1037  /* kΩ (calibrated from nominal 1MΩ) */
#define BATTERY_R2           510   /* kΩ */
#define BATTERY_MIN_MV       3300
#define BATTERY_MAX_MV       4200

static const struct device *adc_dev = DEVICE_DT_GET(DT_NODELABEL(adc));

/* P0.14 read-enable: ACTIVE_LOW per Seeed schematic */
static const struct gpio_dt_spec bat_read_en = {
    .port = DEVICE_DT_GET(DT_NODELABEL(gpio0)),
    .pin = 14,
    .dt_flags = GPIO_ACTIVE_LOW,
};

static struct adc_channel_cfg adc_cfg = {
    .gain             = BATTERY_ADC_GAIN,
    .reference        = BATTERY_ADC_REF,
    .acquisition_time = ADC_ACQ_TIME(ADC_ACQ_TIME_MICROSECONDS, 40),
    .channel_id       = BATTERY_ADC_CHANNEL,
#if defined(CONFIG_ADC_NRFX_SAADC)
    .input_positive   = NRF_SAADC_AIN7,
#endif
};

static int16_t adc_buffer;
static struct adc_sequence adc_seq = {
    .channels    = BIT(BATTERY_ADC_CHANNEL),
    .buffer      = &adc_buffer,
    .buffer_size = sizeof(adc_buffer),
    .resolution  = BATTERY_ADC_RES,
};

/* Forward declarations */
static void audio_frame_handler(int16_t *buf, size_t num_samples);
static void connection_changed(bool connected);
static void on_connect_start_mic(void);
static int init_battery_adc(void);
/* Reads the battery: returns percent (0-100) or 0xFF on failure.
 * Writes the measured millivolts into *out_mv on success (0 on failure). */
static uint8_t read_battery_level(uint16_t *out_mv);

/* Track mic state for fallback start */
static volatile bool mic_running = false;

/* Ring buffer for decoupling PDM callback from BLE sends */
#define RING_BUF_PACKETS 16  /* ~1.6s buffer at 100ms frames */
#define PCM_FRAME_BYTES  (1600 * sizeof(int16_t))  /* 3200 bytes per 100ms frame */

static uint8_t ring_buf_data[RING_BUF_PACKETS * PCM_FRAME_BYTES];
static struct ring_buf audio_ring_buf;

/* Pusher thread — drains ring buffer into BLE notifications */
K_THREAD_STACK_DEFINE(pusher_stack, 4096);
static struct k_thread pusher_thread;

static uint16_t pusher_seq = 0;
#define CHUNK_SIZE 240

static void pusher_entry(void *p1, void *p2, void *p3)
{
    ARG_UNUSED(p1);
    ARG_UNUSED(p2);
    ARG_UNUSED(p3);

    uint8_t chunk[CHUNK_SIZE];

    while (1) {
        if (!transport_is_connected() || ring_buf_size_get(&audio_ring_buf) == 0) {
            k_sleep(K_MSEC(5));
            continue;
        }

        size_t got = ring_buf_get(&audio_ring_buf, chunk, CHUNK_SIZE);
        if (got > 0) {
            int ret;
            int retries = 0;
            do {
                ret = transport_send_audio(chunk, got, pusher_seq, 0x01);
                if (ret == -ENOBUFS || ret == -ENOMEM) {
                    k_sleep(K_MSEC(2));
                    retries++;
                }
            } while ((ret == -ENOBUFS || ret == -ENOMEM) && retries < 20);

            if (ret == 0) {
                pusher_seq++;
                if (pusher_seq % 500 == 1) {
                    LOG_INF("Audio pusher: sent %u packets, ring=%u",
                            pusher_seq, ring_buf_size_get(&audio_ring_buf));
                }
                /* Pace: ~7ms between 240-byte chunks = ~140 chunks/sec
                 * 13 chunks per 100ms frame = exactly real-time */
                k_sleep(K_MSEC(7));
            } else if (pusher_seq < 5) {
                LOG_WRN("Audio send failed: %d (seq=%u)", ret, pusher_seq);
            }
        }
    }
}

/* Main thread */
int main(void)
{
    int ret;
    
    LOG_INF("Helix-Mic starting...");
    
    /* Initialize LED first for boot indication */
    ret = led_init();
    if (ret < 0) {
        LOG_WRN("LED init failed: %d", ret);
    } else {
        led_set_state(LED_STATE_BOOT);
    }
    
    /* Initialize subsystems */
    ret = mic_init();
    if (ret < 0) {
        LOG_ERR("Mic init failed: %d", ret);
        return ret;
    }
    
    ret = codec_init();
    if (ret < 0) {
        LOG_ERR("Codec init failed: %d", ret);
        return ret;
    }
    
    ret = transport_init();
    if (ret < 0) {
        LOG_ERR("Transport init failed: %d", ret);
        return ret;
    }
    
    ret = init_battery_adc();
    if (ret < 0) {
        LOG_WRN("Battery ADC init failed: %d", ret);
    }
    
    /* Initialize audio ring buffer and pusher thread */
    ring_buf_init(&audio_ring_buf, sizeof(ring_buf_data), ring_buf_data);
    k_thread_create(&pusher_thread, pusher_stack,
                    K_THREAD_STACK_SIZEOF(pusher_stack),
                    pusher_entry, NULL, NULL, NULL,
                    K_PRIO_PREEMPT(7), 0, K_NO_WAIT);
    k_thread_name_set(&pusher_thread, "pusher");

    /* Set callbacks */
    mic_set_frame_callback(audio_frame_handler);
    transport_set_conn_callback(connection_changed);
    transport_set_mic_start(on_connect_start_mic);
    
    /* Start BLE advertising */
    ret = transport_start_advertising();
    if (ret < 0) {
        LOG_ERR("Advertising start failed: %d", ret);
        return ret;
    }
    
    /* Boot blinks should be done by now, switch to advertising */
    led_set_state(LED_STATE_ADVERTISING);
    
    LOG_INF("Helix-Mic ready - waiting for connection");
    
    /* Main loop - monitor battery and handle state */
    bool was_connected = false;
    while (1) {
        bool now_connected = transport_is_connected();

        if (now_connected) {
            /* Update battery level periodically */
            uint16_t mv = 0;
            uint8_t level = read_battery_level(&mv);
            transport_update_battery(level, mv);
            if (level == 0xFF) {
                LOG_WRN("Battery read failed");
            } else {
                LOG_INF("Battery: %u%% (%u mV)", level, mv);
            }
        } else if (was_connected) {
            /* Just disconnected — give red flash time, then advertising LED */
            k_sleep(K_MSEC(300));
            led_set_state(LED_STATE_ADVERTISING);
        }

        was_connected = now_connected;
        k_sleep(K_SECONDS(10));
    }
    
    return 0;
}

static void audio_frame_handler(int16_t *buf, size_t num_samples)
{
    if (!transport_is_connected()) {
        return;
    }

    led_notify_audio();

    size_t bytes = num_samples * sizeof(int16_t);
    int written = ring_buf_put(&audio_ring_buf, (uint8_t *)buf, bytes);
    if (written != (int)bytes) {
        LOG_WRN("Ring buf full, dropped %zu bytes", bytes - (size_t)written);
    }
}

static void on_connect_start_mic(void)
{
    int ret;
    
    if (mic_running) {
        LOG_INF("Mic already running, skipping start");
        return;
    }
    
    LOG_INF("Starting audio stream...");
    ret = mic_start();
    if (ret < 0) {
        LOG_ERR("=== MIC START FAILED === error code: %d", ret);
        switch (ret) {
        case -ENODEV:
            LOG_ERR("  Reason: PDM device not found/not ready");
            break;
        case -EINVAL:
            LOG_ERR("  Reason: Invalid PDM configuration");
            break;
        case -EIO:
            LOG_ERR("  Reason: I/O error during PDM start");
            break;
        case -EBUSY:
            LOG_ERR("  Reason: PDM device busy");
            break;
        default:
            LOG_ERR("  Reason: Unknown (check nrfx_pdm error codes)");
            break;
        }
    } else {
        mic_running = true;
        LOG_INF("Mic started successfully");
    }
}

static void connection_changed(bool connected)
{
    if (connected) {
        LOG_INF("=== CONNECTION ESTABLISHED ===");
        /* Start in connected-idle; audio handler will switch to audio state */
        led_set_state(LED_STATE_CONNECTED_IDLE);
    } else {
        LOG_INF("=== CLIENT DISCONNECTED === stopping audio stream");
        int ret = mic_stop();
        if (ret < 0) {
            LOG_ERR("Mic stop failed: %d", ret);
        } else {
            LOG_INF("Mic stopped cleanly");
        }
        mic_running = false;
        
        /* Brief red flash — LED timer auto-transitions to OFF after 200ms.
         * Don't k_sleep() here: this may run on the system workqueue.
         * The main loop will set advertising LED when it sees disconnected. */
        led_set_state(LED_STATE_DISCONNECTED);
        
        /* Restart advertising indicator after a brief delay using k_work */
        LOG_INF("Restarting advertising after disconnect...");
        /* Let main loop handle LED transition to advertising */
    }
}

static int init_battery_adc(void)
{
    int ret;

    if (!device_is_ready(adc_dev)) {
        LOG_ERR("ADC device not ready");
        return -ENODEV;
    }

    ret = adc_channel_setup(adc_dev, &adc_cfg);
    if (ret < 0) {
        LOG_ERR("ADC channel setup failed: %d", ret);
        return ret;
    }
    LOG_INF("ADC channel 7 configured");

    /* Enable battery voltage divider — GPIO_ACTIVE_LOW so logical 1 = pin LOW */
    if (!device_is_ready(bat_read_en.port)) {
        LOG_ERR("GPIO0 not ready for battery enable");
        return -ENODEV;
    }

    ret = gpio_pin_configure_dt(&bat_read_en, GPIO_OUTPUT_ACTIVE);
    if (ret < 0) {
        LOG_ERR("Battery read-enable pin config failed: %d", ret);
        return ret;
    }

    return 0;
}

static uint8_t read_battery_level(uint16_t *out_mv)
{
    if (out_mv) *out_mv = 0;

    int ret = adc_read(adc_dev, &adc_seq);
    if (ret < 0) {
        LOG_WRN("adc_read failed: %d", ret);
        return 0xFF;
    }

    /* Convert raw → millivolts at the ADC pin */
    int32_t adc_mv = adc_buffer;
    ret = adc_raw_to_millivolts(adc_ref_internal(adc_dev),
                                BATTERY_ADC_GAIN, BATTERY_ADC_RES, &adc_mv);
    if (ret < 0) {
        return 0xFF;
    }

    /* Scale through voltage divider to get actual battery voltage */
    uint32_t bat_mv = (uint32_t)adc_mv * (BATTERY_R1 + BATTERY_R2) / BATTERY_R2;

    if (out_mv) *out_mv = (uint16_t)bat_mv;

    if (bat_mv <= BATTERY_MIN_MV) return 0;
    if (bat_mv >= BATTERY_MAX_MV) return 100;
    return (uint8_t)(((bat_mv - BATTERY_MIN_MV) * 100) / (BATTERY_MAX_MV - BATTERY_MIN_MV));
}
