/*
 * Helix-Mic - PDM Microphone Capture using nrfx_pdm directly
 *
 * Uses double-buffered nrfx_pdm with IRQ_DIRECT_CONNECT.
 * Based on proven nRF52840 PDM approach from BasedHardware/omi.
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(mic, CONFIG_LOG_DEFAULT_LEVEL);

#include "mic.h"

#include <nrfx_pdm.h>
#include <hal/nrf_clock.h>
#include <hal/nrf_gpio.h>
#include <zephyr/irq.h>

/* Double buffers for PDM DMA */
static int16_t pdm_buf_0[SAMPLES_PER_FRAME];
static int16_t pdm_buf_1[SAMPLES_PER_FRAME];
static volatile uint8_t next_buf_idx = 0;

static volatile mic_frame_cb_t frame_callback = NULL;
static volatile bool pdm_running = false;

static void pdm_event_handler(nrfx_pdm_evt_t const *event)
{
    if (event->error) {
        LOG_ERR("PDM error: %d", event->error);
        return;
    }

    /* Provide next buffer to PDM DMA */
    if (event->buffer_requested) {
        if (next_buf_idx == 0) {
            nrfx_pdm_buffer_set(pdm_buf_0, SAMPLES_PER_FRAME);
            next_buf_idx = 1;
        } else {
            nrfx_pdm_buffer_set(pdm_buf_1, SAMPLES_PER_FRAME);
            next_buf_idx = 0;
        }
    }

    /* Deliver completed buffer to consumer */
    if (event->buffer_released) {
        if (frame_callback) {
            frame_callback(event->buffer_released,
                           SAMPLES_PER_FRAME);
        }
    }
}

int mic_init(void)
{
    LOG_INF("PDM microphone initialized (nrfx direct)");
    return 0;
}

int mic_start(void)
{
    nrfx_err_t err;

    if (pdm_running) {
        LOG_WRN("PDM already running");
        return 0;
    }

    /* Ensure HFCLK is running — required for PDM */
    if (!nrf_clock_hf_is_running(NRF_CLOCK, NRF_CLOCK_HFCLK_HIGH_ACCURACY)) {
        nrf_clock_task_trigger(NRF_CLOCK, NRF_CLOCK_TASK_HFCLKSTART);
    }

    /* Configure PDM */
    nrfx_pdm_config_t cfg = NRFX_PDM_DEFAULT_CONFIG(PDM_CLK_PIN, PDM_DIN_PIN);
    cfg.clock_freq  = NRF_PDM_FREQ_1280K;
    cfg.mode        = NRF_PDM_MODE_MONO;
    cfg.edge        = NRF_PDM_EDGE_LEFTRISING; /* XIAO Sense: L/R=GND = left ch = rising edge */
    cfg.ratio       = NRF_PDM_RATIO_80X;
    cfg.gain_l      = 0x28; /* 0dB explicit */
    cfg.gain_r      = 0x28;

    /* Wire the IRQ directly — critical for nrfx_pdm on Zephyr */
    IRQ_DIRECT_CONNECT(PDM_IRQn, 5, nrfx_pdm_irq_handler, 0);

    err = nrfx_pdm_init(&cfg, pdm_event_handler);
    if (err != NRFX_SUCCESS) {
        LOG_ERR("nrfx_pdm_init failed: 0x%08x", err);
        return -EIO;
    }

    /* Reset buffer index */
    next_buf_idx = 0;

    err = nrfx_pdm_start();
    if (err != NRFX_SUCCESS) {
        LOG_ERR("nrfx_pdm_start failed: 0x%08x", err);
        nrfx_pdm_uninit();
        return -EIO;
    }

    pdm_running = true;
    LOG_INF("PDM capture started (nrfx, %d samples/frame, %d ms)",
            SAMPLES_PER_FRAME, FRAME_SIZE_MS);
    return 0;
}

int mic_stop(void)
{
    if (!pdm_running) {
        return 0;
    }

    nrfx_pdm_stop();
    nrfx_pdm_uninit();
    pdm_running = false;

    LOG_INF("PDM capture stopped");
    return 0;
}

void mic_set_frame_callback(mic_frame_cb_t cb)
{
    frame_callback = cb;
}
