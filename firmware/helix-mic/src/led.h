/*
 * Helix-Mic - LED Status Indicators
 */

#ifndef HELIX_LED_H
#define HELIX_LED_H

#include <zephyr/kernel.h>

/* LED states */
enum led_state {
    LED_STATE_OFF,              /* All off */
    LED_STATE_BOOT,             /* Brief blue blinks on startup */
    LED_STATE_ADVERTISING,      /* Slow blue pulse (500ms on/off) */
    LED_STATE_CONNECTED_AUDIO,  /* Solid green */
    LED_STATE_CONNECTED_IDLE,   /* Slow green blink */
    LED_STATE_DISCONNECTED,     /* Brief red flash, then off */
};

/* Initialize LED GPIOs */
int led_init(void);

/* Set the current LED state */
void led_set_state(enum led_state state);

/* Call from audio path to signal activity */
void led_notify_audio(void);

#endif /* HELIX_LED_H */
