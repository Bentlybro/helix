/*
 * Helix-Mic - Audio Codec (Opus or PCM fallback)
 */

#ifndef CODEC_H
#define CODEC_H

#include <zephyr/kernel.h>
#include <stdint.h>
#include <stddef.h>

#define OPUS_FRAME_SIZE  40   /* ~20ms at 16kbps */
#define MAX_ENCODED_SIZE 80   /* Max encoded frame size */

struct encoded_frame {
    uint16_t seq_num;
    uint8_t flags;
    uint8_t data[MAX_ENCODED_SIZE];
    size_t len;
};

/* Initialize codec */
int codec_init(void);

/* Encode audio samples */
int codec_encode(const int16_t *samples, size_t sample_count,
                 struct encoded_frame *out);

/* Check if Opus is available */
bool codec_is_opus(void);

#endif /* CODEC_H */
