/*
 * Helix-Mic - Header for PDM microphone capture
 */

#ifndef HELIX_MIC_H
#define HELIX_MIC_H

#include <zephyr/kernel.h>
#include <zephyr/audio/dmic.h>

#define SAMPLE_RATE     16000
#define SAMPLE_BITS     16
#define NUM_CHANNELS    1
#define FRAME_SIZE_MS   20  /* 20ms frames = 320 samples at 16kHz */
#define SAMPLES_PER_FRAME (SAMPLE_RATE * FRAME_SIZE_MS / 1000)

/* Audio frame structure */
struct audio_frame {
    uint16_t seq_num;
    uint8_t flags;
    int16_t samples[SAMPLES_PER_FRAME];
    size_t len;
};

/* Initialize PDM microphone */
int mic_init(void);

/* Start audio capture */
int mic_start(void);

/* Stop audio capture */
int mic_stop(void);

/* Read audio frame (blocking) */
int mic_read_frame(struct audio_frame *frame);

/* Audio frame callback */
typedef void (*mic_frame_cb_t)(struct audio_frame *frame);
void mic_set_frame_callback(mic_frame_cb_t cb);

#endif /* HELIX_MIC_H */
