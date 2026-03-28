/*
 * Helix-Mic - LED Status Indicators
 *
 * RGB LED on XIAO nRF52840 Sense (active-low):
 *   led0 = Red   (P0.26)
 *   led1 = Green (P0.30)
 *   led2 = Blue  (P0.06)
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(led, CONFIG_LOG_DEFAULT_LEVEL);

#include "led.h"
#include <zephyr/drivers/gpio.h>

/* LED GPIO specs from devicetree aliases */
static const struct gpio_dt_spec led_red   = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec led_green = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static const struct gpio_dt_spec led_blue  = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);

static enum led_state current_state = LED_STATE_OFF;
static int64_t last_audio_time;

/* Timer for blink/pulse patterns */
static void led_timer_handler(struct k_timer *timer);
K_TIMER_DEFINE(led_timer, led_timer_handler, NULL);

/* Boot animation state */
static uint8_t boot_blink_count;

/* ---- Low-level helpers ---- */

static inline void all_off(void)
{
    gpio_pin_set_dt(&led_red, 0);
    gpio_pin_set_dt(&led_green, 0);
    gpio_pin_set_dt(&led_blue, 0);
}

static inline void set_red(int on)   { gpio_pin_set_dt(&led_red, on); }
static inline void set_green(int on) { gpio_pin_set_dt(&led_green, on); }
static inline void set_blue(int on)  { gpio_pin_set_dt(&led_blue, on); }

/* ---- Timer callback (runs in ISR context) ---- */

static bool toggle;

static void led_timer_handler(struct k_timer *timer)
{
    toggle = !toggle;

    switch (current_state) {
    case LED_STATE_BOOT:
        /* 2 quick blue blinks: on 100ms, off 100ms x2, then done */
        if (boot_blink_count < 4) {
            set_blue(boot_blink_count % 2 == 0 ? 1 : 0);
            boot_blink_count++;
        } else {
            all_off();
            /* Boot done — transition to advertising */
            current_state = LED_STATE_ADVERTISING;
            k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
        }
        break;

    case LED_STATE_ADVERTISING:
        /* Slow blue pulse: 500ms on, 500ms off */
        set_blue(toggle ? 1 : 0);
        break;

    case LED_STATE_CONNECTED_IDLE:
        /* Slow green blink: 500ms on, 500ms off */
        set_green(toggle ? 1 : 0);
        break;

    case LED_STATE_CONNECTED_AUDIO:
        /* Solid green — check if audio stopped (>300ms no frames) */
        if (k_uptime_get() - last_audio_time > 300) {
            current_state = LED_STATE_CONNECTED_IDLE;
            all_off();
            /* Timer already running at 500ms for idle blink */
            k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
        }
        break;

    case LED_STATE_DISCONNECTED:
        /* Red was flashed — turn off and stop */
        all_off();
        current_state = LED_STATE_OFF;
        k_timer_stop(&led_timer);
        break;

    default:
        break;
    }
}

/* ---- Public API ---- */

int led_init(void)
{
    int ret;

    if (!gpio_is_ready_dt(&led_red) ||
        !gpio_is_ready_dt(&led_green) ||
        !gpio_is_ready_dt(&led_blue)) {
        LOG_ERR("LED GPIO not ready");
        return -ENODEV;
    }

    ret = gpio_pin_configure_dt(&led_red, GPIO_OUTPUT_INACTIVE);
    if (ret < 0) return ret;
    ret = gpio_pin_configure_dt(&led_green, GPIO_OUTPUT_INACTIVE);
    if (ret < 0) return ret;
    ret = gpio_pin_configure_dt(&led_blue, GPIO_OUTPUT_INACTIVE);
    if (ret < 0) return ret;

    all_off();
    LOG_INF("LED initialized");
    return 0;
}

void led_set_state(enum led_state state)
{
    k_timer_stop(&led_timer);
    all_off();
    toggle = false;
    current_state = state;

    switch (state) {
    case LED_STATE_OFF:
        /* Nothing to do */
        break;

    case LED_STATE_BOOT:
        /* 2 quick blue blinks: 100ms intervals */
        boot_blink_count = 0;
        set_blue(1);
        boot_blink_count = 1;
        k_timer_start(&led_timer, K_MSEC(100), K_MSEC(100));
        break;

    case LED_STATE_ADVERTISING:
        set_blue(1);
        k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
        break;

    case LED_STATE_CONNECTED_AUDIO:
        set_green(1);
        last_audio_time = k_uptime_get();
        /* Check periodically if audio stops */
        k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
        break;

    case LED_STATE_CONNECTED_IDLE:
        set_green(1);
        k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
        break;

    case LED_STATE_DISCONNECTED:
        /* Brief red flash (200ms) */
        set_red(1);
        k_timer_start(&led_timer, K_MSEC(200), K_NO_WAIT);
        break;
    }
}

void led_notify_audio(void)
{
    last_audio_time = k_uptime_get();

    /* If we were idle, switch to audio state */
    if (current_state == LED_STATE_CONNECTED_IDLE) {
        k_timer_stop(&led_timer);
        all_off();
        set_green(1);
        current_state = LED_STATE_CONNECTED_AUDIO;
        k_timer_start(&led_timer, K_MSEC(500), K_MSEC(500));
    }
}
