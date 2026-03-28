/*
 * Helix-Mic - Header for PDM microphone capture (Zephyr DMIC API)
 */

#ifndef HELIX_MIC_H
#define HELIX_MIC_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stddef.h>

#define SAMPLE_RATE       16000
#define SAMPLE_BITS       16
#define NUM_CHANNELS      1
#define FRAME_SIZE_MS     100  /* 100ms frames = 1600 samples at 16kHz */
#define SAMPLES_PER_FRAME (SAMPLE_RATE * FRAME_SIZE_MS / 1000)

/* Audio frame callback — receives raw PCM buffer pointer (valid only during call) */
typedef void (*mic_frame_cb_t)(int16_t *buf, size_t num_samples);

/* Initialize DMIC device */
int mic_init(void);

/* Start audio capture */
int mic_start(void);

/* Stop audio capture */
int mic_stop(void);

/* Set audio frame callback */
void mic_set_frame_callback(mic_frame_cb_t cb);

#endif /* HELIX_MIC_H */
