/*
 * Helix-Mic - PDM Microphone Capture for XIAO nRF52840 Sense
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(mic, CONFIG_LOG_DEFAULT_LEVEL);

#include "mic.h"
#include <zephyr/drivers/gpio.h>

/* Try DT_ALIAS first, fallback to node labels */
#if DT_NODE_EXISTS(DT_ALIAS(dmic_dev))
#define DMIC_DEV DT_ALIAS(dmic_dev)
#elif DT_NODE_EXISTS(DT_NODELABEL(dmic0))
#define DMIC_DEV DT_NODELABEL(dmic0)
#elif DT_NODE_EXISTS(DT_NODELABEL(pdm0))
#define DMIC_DEV DT_NODELABEL(pdm0)
#else
#error "No DMIC/PDM device found in device tree"
#endif
#define BUF_COUNT    4
#define STREAM_ID    0

static const struct device *dmic_dev;
static mic_frame_cb_t frame_callback = NULL;
static volatile bool running = false;
static uint16_t sequence = 0;

/* Memory slab for DMA buffers */
K_MEM_SLAB_DEFINE(rx_mem_slab, 
                  SAMPLES_PER_FRAME * sizeof(int16_t), 
                  BUF_COUNT, 4);

/* PDM stream configuration */
static struct pcm_stream_cfg pcm_cfg = {
    .pcm_rate = SAMPLE_RATE,
    .pcm_width = SAMPLE_BITS,
    .block_size = SAMPLES_PER_FRAME * sizeof(int16_t),
    .mem_slab = &rx_mem_slab,
};

static struct dmic_cfg dmic_config = {
    .io = {
        /* nRF52840 PDM clock range for 16kHz sampling */
        .min_pdm_clk_freq = 1000000,
        .max_pdm_clk_freq = 3500000,
    },
    .streams = &pcm_cfg,
    .channel = {
        .req_num_streams = 1,
        .req_num_chan = NUM_CHANNELS,
        .req_chan_map_hi = 0,
    },
};

int mic_init(void)
{
    dmic_dev = DEVICE_DT_GET(DMIC_DEV);
    if (!device_is_ready(dmic_dev)) {
        LOG_ERR("PDM device not ready");
        return -ENODEV;
    }

    /* Set channel map at runtime (dmic_build_channel_map is not constexpr) */
    dmic_config.channel.req_chan_map_lo =
        dmic_build_channel_map(0, 0, PDM_CHAN_LEFT);

    LOG_INF("PDM microphone initialized");
    return 0;
}

static void dmic_thread_fn(void *arg1, void *arg2, void *arg3)
{
    void *buffer;
    size_t size;
    struct audio_frame frame;

    while (running) {
        if (dmic_read(dmic_dev, STREAM_ID, &buffer, &size, 100) == 0) {
            frame.seq_num = sequence++;
            frame.flags = 0;
            frame.len = size / sizeof(int16_t);
            
            if (frame.len <= SAMPLES_PER_FRAME) {
                memcpy(frame.samples, buffer, size);
                
                if (frame_callback) {
                    frame_callback(&frame);
                }
            }
            
            k_mem_slab_free(&rx_mem_slab, buffer);
        }
    }
}

K_THREAD_STACK_DEFINE(dmic_stack, 8192);
static struct k_thread dmic_thread;

int mic_start(void)
{
    int ret;

    ret = dmic_configure(dmic_dev, &dmic_config);
    if (ret < 0) {
        LOG_ERR("DMIC configure failed: %d", ret);
        return ret;
    }

    ret = dmic_trigger(dmic_dev, DMIC_TRIGGER_START);
    if (ret < 0) {
        LOG_ERR("DMIC start failed: %d", ret);
        return ret;
    }

    running = true;
    
    k_thread_create(&dmic_thread, dmic_stack,
                    K_THREAD_STACK_SIZEOF(dmic_stack),
                    dmic_thread_fn, NULL, NULL, NULL,
                    5, 0, K_NO_WAIT);
    k_thread_name_set(&dmic_thread, "dmic");

    LOG_INF("PDM capture started");
    return 0;
}

int mic_stop(void)
{
    running = false;
    
    dmic_trigger(dmic_dev, DMIC_TRIGGER_STOP);
    
    k_thread_join(&dmic_thread, K_MSEC(500));
    
    LOG_INF("PDM capture stopped");
    return 0;
}

void mic_set_frame_callback(mic_frame_cb_t cb)
{
    frame_callback = cb;
}
