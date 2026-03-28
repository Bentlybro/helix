/*
 * Helix-Mic - Audio Codec
 * 
 * Falls back to raw PCM if Opus not available in SDK
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(codec, CONFIG_LOG_DEFAULT_LEVEL);

#include "codec.h"

/* Opus not readily available in base Zephyr - use raw PCM for now */
/* TODO: Integrate Opus from external lib when available */

static bool use_opus = false;
static uint16_t encode_seq = 0;

int codec_init(void)
{
    /* Check for Opus library - for now assume not available */
    use_opus = false;
    
    LOG_WRN("Opus not available - using raw PCM output");
    return 0;
}

bool codec_is_opus(void)
{
    return use_opus;
}

int codec_encode(const int16_t *samples, size_t sample_count,
                 struct encoded_frame *out)
{
    out->seq_num = encode_seq++;
    out->flags = 0x01; /* Flag: 0x01 = raw PCM, 0x00 = Opus */
    
    if (use_opus) {
        /* Placeholder for Opus encoding */
        /* Would call opus_encode() here */
        out->len = 0;
        return -1;
    } else {
        /* Raw PCM passthrough - chunk into smaller packets */
        /* BLE has MTU limits, so we send ~80 bytes max payload */
        size_t bytes_to_copy = sample_count * sizeof(int16_t);
        
        if (bytes_to_copy > MAX_ENCODED_SIZE) {
            bytes_to_copy = MAX_ENCODED_SIZE;
        }
        
        memcpy(out->data, samples, bytes_to_copy);
        out->len = bytes_to_copy;
        
        /* Mark as PCM in flags */
        out->flags |= 0x01;
        
        return 0;
    }
}
