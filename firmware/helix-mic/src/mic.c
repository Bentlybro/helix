/*
 * Helix-Mic - PDM Microphone Capture using Zephyr DMIC API
 *
 * Uses Zephyr's standard DMIC driver with k_mem_slab for buffer management.
 * Mic enable (P1.10) is handled by device tree regulator-fixed with regulator-boot-on.
 */

#include <zephyr/logging/log.h>
LOG_MODULE_REGISTER(mic, CONFIG_LOG_DEFAULT_LEVEL);

#include "mic.h"

#include <zephyr/audio/dmic.h>

/* Buffer management — DMIC driver allocates from this slab */
#define BYTES_PER_SAMPLE  sizeof(int16_t)
#define BLOCK_SIZE        (BYTES_PER_SAMPLE * SAMPLES_PER_FRAME * NUM_CHANNELS)
#define BLOCK_COUNT       4
K_MEM_SLAB_DEFINE_STATIC(mic_mem_slab, BLOCK_SIZE, BLOCK_COUNT, 4);

/* Mic reader thread */
#define MIC_THREAD_STACK_SIZE 2048
#define MIC_THREAD_PRIORITY   K_PRIO_PREEMPT(5)
K_THREAD_STACK_DEFINE(mic_thread_stack, MIC_THREAD_STACK_SIZE);
static struct k_thread mic_thread_data;

static const struct device *dmic;
static volatile mic_frame_cb_t frame_callback;
static volatile bool mic_running;

/* Read timeout in ms */
#define READ_TIMEOUT_MS 1000

/* Software gain multiplier (Omi uses hardware gain 64 ≈ 4x our default) */
#define SOFTWARE_GAIN_SHIFT 2  /* Multiply by 4 */

static void mic_reader_entry(void *p1, void *p2, void *p3)
{
	ARG_UNUSED(p1);
	ARG_UNUSED(p2);
	ARG_UNUSED(p3);

	void *buffer;
	uint32_t size;
	int ret;

	LOG_INF("Mic reader thread started");

	while (mic_running) {
		ret = dmic_read(dmic, 0, &buffer, &size, READ_TIMEOUT_MS);
		if (ret < 0) {
			if (mic_running) {
				LOG_ERR("dmic_read failed: %d", ret);
			}
			continue;
		}

		if (frame_callback && size > 0) {
			size_t num_samples = size / BYTES_PER_SAMPLE;
			frame_callback((int16_t *)buffer, num_samples);
		}

		k_mem_slab_free(&mic_mem_slab, buffer);
	}

	LOG_INF("Mic reader thread exiting");
}

int mic_init(void)
{
	dmic = DEVICE_DT_GET(DT_NODELABEL(dmic_dev));
	if (!device_is_ready(dmic)) {
		LOG_ERR("DMIC device not ready");
		return -ENODEV;
	}

	LOG_INF("DMIC device initialized (Zephyr DMIC API)");
	return 0;
}

int mic_start(void)
{
	int ret;

	if (mic_running) {
		LOG_WRN("Mic already running");
		return 0;
	}

	struct pcm_stream_cfg stream = {
		.pcm_rate  = SAMPLE_RATE,
		.pcm_width = SAMPLE_BITS,
		.block_size = BLOCK_SIZE,
		.mem_slab  = &mic_mem_slab,
	};

	struct dmic_cfg cfg = {
		.io = {
			.min_pdm_clk_freq = 1000000,
			.max_pdm_clk_freq = 3500000,
			.min_pdm_clk_dc   = 40,
			.max_pdm_clk_dc   = 60,
		},
		.streams = &stream,
		.channel = {
			.req_num_streams = 1,
			.req_num_chan = NUM_CHANNELS,
			.req_chan_map_lo = dmic_build_channel_map(0, 0, PDM_CHAN_RIGHT),
		},
	};

	ret = dmic_configure(dmic, &cfg);
	if (ret < 0) {
		LOG_ERR("dmic_configure failed: %d", ret);
		return ret;
	}

	ret = dmic_trigger(dmic, DMIC_TRIGGER_START);
	if (ret < 0) {
		LOG_ERR("dmic_trigger START failed: %d", ret);
		return ret;
	}

	mic_running = true;

	/* Spawn reader thread */
	k_thread_create(&mic_thread_data, mic_thread_stack,
			K_THREAD_STACK_SIZEOF(mic_thread_stack),
			mic_reader_entry, NULL, NULL, NULL,
			MIC_THREAD_PRIORITY, 0, K_NO_WAIT);
	k_thread_name_set(&mic_thread_data, "mic_reader");

	LOG_INF("DMIC capture started (%d Hz, %d-bit, %d ms frames)",
		SAMPLE_RATE, SAMPLE_BITS, FRAME_SIZE_MS);
	return 0;
}

int mic_stop(void)
{
	int ret;

	if (!mic_running) {
		return 0;
	}

	mic_running = false;

	ret = dmic_trigger(dmic, DMIC_TRIGGER_STOP);
	if (ret < 0) {
		LOG_ERR("dmic_trigger STOP failed: %d", ret);
		return ret;
	}

	/* Wait for reader thread to exit */
	k_thread_join(&mic_thread_data, K_MSEC(2000));

	LOG_INF("DMIC capture stopped");
	return 0;
}

void mic_set_frame_callback(mic_frame_cb_t cb)
{
	frame_callback = cb;
}
