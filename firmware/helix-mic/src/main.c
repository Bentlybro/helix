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


#include "mic.h"
#include "codec.h"
#include "transport.h"
#include "led.h"

/* Battery ADC configuration - use node label directly */
#define BATTERY_ADC_NODE DT_NODELABEL(adc)

/* Battery voltage divider: full charge ~4.2V, empty ~3.3V */
/* ADC reference 3.6V, 12-bit resolution (0-4095) */
/* Divider typically 1/2 on XIAO nRF52840 Sense */
#define BATTERY_ADC_CHANNEL   7   /* AIN7 = P0.31 on nRF52840 */
#define BATTERY_ADC_MAX       4095
#define BATTERY_VREF_MV       3600
#define BATTERY_DIVIDER       2
#define BATTERY_MIN_MV        3300
#define BATTERY_MAX_MV        4200

static const struct device *adc_dev;
static struct gpio_dt_spec bat_en = GPIO_DT_SPEC_GET_OR(DT_NODELABEL(bat_en_pin), gpios, {0});

static int16_t adc_buffer;
static struct adc_sequence adc_seq = {
    .buffer = &adc_buffer,
    .buffer_size = sizeof(adc_buffer),
};

static struct adc_channel_cfg adc_cfg = {
    .gain = ADC_GAIN_1,
    .reference = ADC_REF_INTERNAL,
    .acquisition_time = ADC_ACQ_TIME_DEFAULT,
    .channel_id = BATTERY_ADC_CHANNEL,
    .differential = 0,
};

/* Forward declarations */
static void audio_frame_handler(int16_t *buf, size_t num_samples);
static void connection_changed(bool connected);
static void on_connect_start_mic(void);
static int init_battery_adc(void);
static uint8_t read_battery_level(void);

/* Track mic state for fallback start */
static volatile bool mic_running = false;

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
            /* Fallback: if connected but mic not running, start it */
            if (!mic_running) {
                LOG_WRN("Mic not running on connect - starting now (fallback)");
                on_connect_start_mic();
            }
            
            /* Update battery level periodically */
            uint8_t level = read_battery_level();
            transport_update_battery(level);
            LOG_DBG("Battery: %u%%", level);
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

/* Max PCM bytes per BLE packet (MTU 247 - 3 ATT - 3 header = 241, round to 240) */
#define BLE_AUDIO_CHUNK_BYTES 240

static uint16_t audio_seq = 0;

static void audio_frame_handler(int16_t *buf, size_t num_samples)
{
    if (!transport_is_connected()) {
        return;
    }
    
    led_notify_audio();
    
    /* Send PCM in BLE-sized chunks */
    const uint8_t *pcm = (const uint8_t *)buf;
    size_t total_bytes = num_samples * sizeof(int16_t);
    size_t offset = 0;
    
    while (offset < total_bytes) {
        size_t chunk = total_bytes - offset;
        if (chunk > BLE_AUDIO_CHUNK_BYTES) {
            chunk = BLE_AUDIO_CHUNK_BYTES;
        }
        
        int ret = transport_send_audio(pcm + offset, chunk, audio_seq++, 0x01);
        if (ret < 0) {
            LOG_DBG("Send failed at offset %zu: %d", offset, ret);
            break;
        }
        offset += chunk;
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
    
    adc_dev = DEVICE_DT_GET(BATTERY_ADC_NODE);
    if (!device_is_ready(adc_dev)) {
        LOG_ERR("ADC device not ready");
        return -ENODEV;
    }
    
    /* Enable battery measurement circuit if pin defined */
    if (bat_en.port != NULL) {
        ret = gpio_pin_configure_dt(&bat_en, GPIO_OUTPUT_ACTIVE);
        if (ret < 0) {
            LOG_WRN("Battery enable pin config failed");
        }
    }
    
    /* Configure ADC channel */
    ret = adc_channel_setup(adc_dev, &adc_cfg);
    if (ret < 0) {
        LOG_ERR("ADC channel setup failed: %d", ret);
        return ret;
    }
    
    adc_seq.channels = BIT(BATTERY_ADC_CHANNEL);
    adc_seq.resolution = 12;  /* 12-bit ADC */
    
    return 0;
}

static uint8_t read_battery_level(void)
{
    int ret;
    int32_t mv;
    uint8_t percent;
    
    if (!device_is_ready(adc_dev)) {
        return 100;  /* Default if ADC not available */
    }
    
    ret = adc_read(adc_dev, &adc_seq);
    if (ret < 0) {
        return 100;
    }
    
    /* Convert ADC value to millivolts */
    mv = adc_buffer;
    mv = (mv * BATTERY_VREF_MV * BATTERY_DIVIDER) / BATTERY_ADC_MAX;
    
    /* Convert to percentage */
    if (mv <= BATTERY_MIN_MV) {
        percent = 0;
    } else if (mv >= BATTERY_MAX_MV) {
        percent = 100;
    } else {
        percent = ((mv - BATTERY_MIN_MV) * 100) / (BATTERY_MAX_MV - BATTERY_MIN_MV);
    }
    
    return percent;
}
