#include "aacdecoder_lib.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <emscripten.h>

static HANDLE_AACDECODER dec = NULL;
static int out_channels = 2;
static int frame_samples = 480;

EMSCRIPTEN_KEEPALIVE
int fdk_open(const uint8_t *asc, int asc_len) {
    if (dec) {
        aacDecoder_Close(dec);
        dec = NULL;
    }
    dec = aacDecoder_Open(TT_MP4_RAW, 1);
    if (!dec) return -1;
    UCHAR *conf[1];
    UCHAR *buf = (UCHAR *)malloc((size_t)asc_len);
    if (!buf) return -2;
    memcpy(buf, asc, (size_t)asc_len);
    conf[0] = buf;
    UINT conf_len = (UINT)asc_len;
    AAC_DECODER_ERROR err = aacDecoder_ConfigRaw(dec, conf, &conf_len);
    free(buf);
    if (err != AAC_DEC_OK) return (int)err;
    CStreamInfo *info = aacDecoder_GetStreamInfo(dec);
    if (info) {
        out_channels = info->numChannels > 0 ? info->numChannels : 2;
        frame_samples = info->frameSize > 0 ? info->frameSize : 480;
    }
    return 0;
}

EMSCRIPTEN_KEEPALIVE
int fdk_channels(void) { return out_channels; }

EMSCRIPTEN_KEEPALIVE
int fdk_frame_samples(void) { return frame_samples; }

EMSCRIPTEN_KEEPALIVE
int fdk_decode(const uint8_t *in, int in_len, int16_t *out, int out_samples_cap) {
    if (!dec || !in || in_len <= 0 || !out) return -1;
    UCHAR *p_buffer[1];
    UCHAR *tmp = (UCHAR *)malloc((size_t)in_len);
    if (!tmp) return -2;
    memcpy(tmp, in, (size_t)in_len);
    p_buffer[0] = tmp;
    UINT buffer_size = (UINT)in_len;
    UINT bytes_valid = (UINT)in_len;
    AAC_DECODER_ERROR err = aacDecoder_Fill(dec, p_buffer, &buffer_size, &bytes_valid);
    if (err != AAC_DEC_OK) {
        free(tmp);
        return (int)err;
    }
    int need = frame_samples * out_channels;
    if (need > out_samples_cap) {
        free(tmp);
        return -3;
    }
    err = aacDecoder_DecodeFrame(dec, out, need, 0);
    free(tmp);
    if (err != AAC_DEC_OK) return (int)err;
    return need * (int)sizeof(int16_t);
}

EMSCRIPTEN_KEEPALIVE
void fdk_close(void) {
    if (dec) {
        aacDecoder_Close(dec);
        dec = NULL;
    }
}
