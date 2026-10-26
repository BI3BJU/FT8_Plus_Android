#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <string>
#include <map>
#include <cmath>
#include <unordered_map>
#include <mutex>
#include <memory>
#include <algorithm>
#include <list>
#include <android/log.h>
#include <stdlib.h>
#include <math.h>

#define LOG_TAG "FT8Native"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#include "constants.h"
#include "monitor.h"
#include "decode.h"
#include "message.h"
#include "encode.h"

#ifndef FTX_MAX_CALLSIGN_LEN
#define FTX_MAX_CALLSIGN_LEN 16
#endif
#ifndef FTX_MAX_MESSAGE_LENGTH
#define FTX_MAX_MESSAGE_LENGTH 100
#endif
#ifndef FT8_SLOT_TIME
#define FT8_SLOT_TIME 15.0f
#endif

#define MAX_CANDIDATES         100
#define DEFAULT_MAX_ITER       80
#define MAX_HASH_ENTRIES       10000
#define FT8_MAX_SYMBOL_IDX     95

static std::unordered_map<uint32_t, std::string> g_hash_to_callsign;
static std::unordered_map<std::string, uint32_t> g_callsign_to_hash;
static std::mutex g_hash_mutex;
static std::list<uint32_t> g_hash_lru;

static bool my_lookup(ftx_callsign_hash_type_t type, uint32_t hash, char* callsign) {
    std::lock_guard<std::mutex> lock(g_hash_mutex);
    auto it = g_hash_to_callsign.find(hash);
    if (it != g_hash_to_callsign.end()) {
        strncpy(callsign, it->second.c_str(), FTX_MAX_CALLSIGN_LEN);
        callsign[FTX_MAX_CALLSIGN_LEN-1] = '\0';
        g_hash_lru.remove(hash);
        g_hash_lru.push_back(hash);
        LOGD("Hash lookup success: 0x%08X -> %s", hash, callsign);
        return true;
    }
    LOGD("Hash lookup failed: 0x%08X", hash);
    return false;
}

static void my_save(const char* callsign, uint32_t hash) {
    std::lock_guard<std::mutex> lock(g_hash_mutex);
    if (g_hash_to_callsign.find(hash) == g_hash_to_callsign.end()) {
        while (g_hash_to_callsign.size() >= MAX_HASH_ENTRIES && !g_hash_lru.empty()) {
            uint32_t oldest = g_hash_lru.front();
            auto old_it = g_hash_to_callsign.find(oldest);
            if (old_it != g_hash_to_callsign.end()) {
                g_callsign_to_hash.erase(old_it->second);
                g_hash_to_callsign.erase(old_it);
            }
            g_hash_lru.pop_front();
        }
        g_hash_to_callsign[hash] = std::string(callsign);
        g_callsign_to_hash[callsign] = hash;
        g_hash_lru.push_back(hash);
        LOGD("Saved hash mapping: %s -> 0x%08X", callsign, hash);
    }
}

static ftx_callsign_hash_interface_t hash_if = {
    .lookup_hash = my_lookup,
    .save_hash = my_save
};

static float compute_snr_raw(const ftx_waterfall_t* wf, const ftx_candidate_t* cand, ftx_protocol_t protocol) {
    if (!wf || !cand) return 0.0f;
    if (cand->freq_offset < 0 || cand->freq_offset >= wf->num_bins) {
        LOGE("compute_snr_raw: freq_offset out of range (%d, max=%d)", cand->freq_offset, wf->num_bins);
        return 0.0f;
    }
    if (cand->time_sub < 0 || cand->time_sub >= wf->time_osr ||
        cand->freq_sub < 0 || cand->freq_sub >= wf->freq_osr) {
        LOGE("compute_snr_raw: sub index out of range (time_sub=%d, freq_sub=%d)", cand->time_sub, cand->freq_sub);
        return 0.0f;
    }
    const int num_data_syms = (protocol == FTX_PROTOCOL_FT4) ? FT4_ND : FT8_ND;
    float total_snr_db = 0.0f;
    int valid_count = 0;
    const size_t max_offset = wf->num_bins * wf->block_stride * (FT8_MAX_SYMBOL_IDX + 5);
    for (int k = 0; k < num_data_syms; ++k) {
        int sym_idx = k;
        if (protocol == FTX_PROTOCOL_FT4) {
            if (k < 29) sym_idx += 5;
            else if (k < 58) sym_idx += 9;
            else sym_idx += 13;
        } else {
            sym_idx = k + ((k < 29) ? 7 : 14);
        }
        int offset = cand->time_offset;
        offset = (offset * wf->time_osr) + cand->time_sub;
        offset = (offset * wf->freq_osr) + cand->freq_sub;
        offset = (offset * wf->num_bins) + cand->freq_offset;
        offset += sym_idx * wf->block_stride;
        if (offset + 7 >= max_offset || offset < 0) {
            LOGE("compute_snr_raw: offset %d out of bounds", offset);
            continue;
        }
        float mags[8];
        for (int i = 0; i < 8; ++i) {
            uint8_t scaled = wf->mag[offset + i];
            mags[i] = scaled / 2.0f - 120.0f;
        }
        float max_mag = mags[0];
        for (int i = 1; i < 8; ++i) if (mags[i] > max_mag) max_mag = mags[i];
        float noise_sum = 0;
        for (int i = 0; i < 8; ++i) noise_sum += mags[i];
        noise_sum = (noise_sum - max_mag) / 7.0f;
        float snr_db = max_mag - noise_sum;
        if (snr_db > -20.0f) { total_snr_db += snr_db; ++valid_count; }
    }
    return (valid_count > 0) ? (total_snr_db / valid_count) : 0.0f;
}

static float compute_snr_wsjtx(const ftx_waterfall_t* wf, const ftx_candidate_t* cand, ftx_protocol_t protocol) {
    float raw_snr = compute_snr_raw(wf, cand, protocol);
    if (raw_snr == 0.0f) return 0.0f;
    const float bandwidth_correction = 10.0f * std::log10(2500.0f / 50.0f);
    return raw_snr - bandwidth_correction;
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_bi3bju_ft8_1plus_MainActivity_00024FT8Decoder_decodeFt8(JNIEnv *env, jclass, jshortArray jSamples, jint sampleRate) {
    LOGD("decodeFt8 called, sampleRate=%d", sampleRate);
    if (sampleRate != 12000) {
        LOGE("Unsupported sample rate: %d", sampleRate);
        return env->NewStringUTF("");
    }
    jsize len = env->GetArrayLength(jSamples);
    int requiredLen = (int)(FT8_SLOT_TIME * sampleRate);
    if (len != requiredLen) {
        LOGE("Invalid sample count: expected %d, got %d", requiredLen, len);
        return env->NewStringUTF("");
    }

    jshort* src = env->GetShortArrayElements(jSamples, nullptr);
    if (!src) {
        LOGE("GetShortArrayElements failed");
        return env->NewStringUTF("");
    }

    std::unique_ptr<float[]> samples(new float[requiredLen]);
    for (int i = 0; i < requiredLen; ++i) {
        float sample = src[i] / 32768.0f;
        if (sample > 1.0f) sample = 1.0f;
        if (sample < -1.0f) sample = -1.0f;
        samples[i] = sample;
    }
    env->ReleaseShortArrayElements(jSamples, src, JNI_ABORT);

    monitor_config_t cfg = {
        .f_min = 200.0f,
        .f_max = 3000.0f,
        .sample_rate = sampleRate,
        .time_osr = 2,
        .freq_osr = 2,
        .protocol = FTX_PROTOCOL_FT8
    };
    monitor_t mon;
    monitor_init(&mon, &cfg);
    int blockSize = mon.block_size;
    float symbol_period = (float)blockSize / sampleRate;

    for (int offset = 0; offset + blockSize <= requiredLen; offset += blockSize) {
        monitor_process(&mon, samples.get() + offset);
    }

    ftx_candidate_t candidates[MAX_CANDIDATES];
    int numCandidates = ftx_find_candidates(&mon.wf, MAX_CANDIDATES, candidates, 0.0f);
    LOGD("Found %d candidates", numCandidates);

    float bin_width = (cfg.f_max - cfg.f_min) / mon.wf.num_bins;

    struct BestCandidate { std::string message; float snr_db; float dt; };
    std::map<std::string, BestCandidate> bestMap;
    for (int i = 0; i < numCandidates; ++i) {
        if (candidates[i].freq_offset < 0 || candidates[i].freq_offset >= mon.wf.num_bins) continue;
        if (candidates[i].time_sub < 0 || candidates[i].time_sub >= cfg.time_osr ||
            candidates[i].freq_sub < 0 || candidates[i].freq_sub >= cfg.freq_osr) continue;

        ftx_message_t msg;
        ftx_decode_status_t status;
        if (ftx_decode_candidate(&mon.wf, &candidates[i], DEFAULT_MAX_ITER, &msg, &status)) {
            if (status.ldpc_errors == 0 && status.crc_extracted == status.crc_calculated) {
                char text[FTX_MAX_MESSAGE_LENGTH];
                ftx_message_offsets_t offsets;
                if (ftx_message_decode(&msg, &hash_if, text, &offsets) == FTX_MESSAGE_RC_OK) {
                    uint8_t i3 = ftx_message_get_i3(&msg);
                    float freq_hz = cfg.f_min + (candidates[i].freq_offset + candidates[i].freq_sub / (float)cfg.freq_osr) * bin_width;
                    float snr_wsjtx = compute_snr_wsjtx(&mon.wf, &candidates[i], cfg.protocol);
                    float dt = (candidates[i].time_offset + candidates[i].time_sub / (float)cfg.time_osr) * symbol_period;
                    char line[256];
                    snprintf(line, sizeof(line), "SNR: %4.0f DT: %5.1f i3:%d %6.0fHz    %s", snr_wsjtx, dt, i3, freq_hz, text);
                    std::string display = line;
                    std::string key = text;
                    
                    auto it = bestMap.find(key);
                    if (it == bestMap.end() || snr_wsjtx > it->second.snr_db) {
                        bestMap[key] = {display, snr_wsjtx, dt};
                        LOGD("Decoded (best): %s", display.c_str());
                    }
                }
            }
        }
    }
    LOGD("Decoding finished: processed %d candidates, decoded %d unique messages", numCandidates, (int)bestMap.size());
    monitor_free(&mon);

    std::string allMessages;
    for (const auto& pair : bestMap) {
        if (!allMessages.empty()) allMessages += "\n";
        allMessages += pair.second.message;
    }
    return env->NewStringUTF(allMessages.c_str());
}

JNIEXPORT jshortArray JNICALL
Java_com_bi3bju_ft8_1plus_MainActivity_00024FT8Encoder_encodeTransparent(JNIEnv *env, jclass, jint f2, jstring jHexData, jint freqHz, jint sampleRate) {
    if (sampleRate != 12000) {
        LOGE("encodeTransparent: unsupported sample rate %d", sampleRate);
        return nullptr;
    }
    if (f2 < 0 || f2 > 3) {
        LOGE("encodeTransparent: invalid f2 %d", f2);
        return nullptr;
    }

    const char* hex = env->GetStringUTFChars(jHexData, nullptr);
    if (!hex) return nullptr;
    size_t hexLen = strlen(hex);
    if (hexLen != 18) {
        LOGE("encodeTransparent: hex string length %zu, expected 18", hexLen);
        env->ReleaseStringUTFChars(jHexData, hex);
        return nullptr;
    }
    uint8_t b72[9];
    for (int i = 0; i < 9; ++i) {
        char byteStr[3] = {hex[i*2], hex[i*2+1], 0};
        b72[i] = (uint8_t)strtoul(byteStr, nullptr, 16);
    }
    env->ReleaseStringUTFChars(jHexData, hex);

    ftx_message_t msg;
    if (ftx_message_encode_transparent(&msg, (uint8_t)f2, b72) != FTX_MESSAGE_RC_OK) {
        LOGE("encodeTransparent: ftx_message_encode_transparent failed");
        return nullptr;
    }

    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    const int samples_per_symbol = (int)(sampleRate * FT8_SYMBOL_PERIOD);
    const int num_symbols = FT8_NN;
    const int num_samples = num_symbols * samples_per_symbol;

    jshortArray result = env->NewShortArray(num_samples);
    if (!result) return nullptr;
    jshort* samples = env->GetShortArrayElements(result, nullptr);
    if (!samples) {
        env->DeleteLocalRef(result);
        return nullptr;
    }

    float* float_buffer = (float*)calloc(num_samples, sizeof(float));
    float* symbol_buffer = (float*)malloc(samples_per_symbol * sizeof(float));
    if (!float_buffer || !symbol_buffer) {
        LOGE("Memory allocation failed");
        if (float_buffer) free(float_buffer);
        if (symbol_buffer) free(symbol_buffer);
        env->ReleaseShortArrayElements(result, samples, JNI_ABORT);
        env->DeleteLocalRef(result);
        return nullptr;
    }

    float phase = 0.0f;
    for (int sym = 0; sym < num_symbols; ++sym) {
        float offset = (tones[sym] - 3.5f) * 6.25f;
        float tone_freq = freqHz + offset;
        float phase_inc = 2.0f * M_PI * tone_freq / sampleRate;

        for (int i = 0; i < samples_per_symbol; ++i) {
            symbol_buffer[i] = sinf(phase);
            phase += phase_inc;
            if (phase >= 2.0f * M_PI) phase -= 2.0f * M_PI;
        }
        memcpy(float_buffer + sym * samples_per_symbol, symbol_buffer, samples_per_symbol * sizeof(float));
    }

    for (int i = 0; i < num_samples; ++i) {
        float val = float_buffer[i];
        if (val > 1.0f) val = 1.0f;
        if (val < -1.0f) val = -1.0f;
        samples[i] = (jshort)(val * 32767.0f);
    }

    free(symbol_buffer);
    free(float_buffer);
    env->ReleaseShortArrayElements(result, samples, 0);
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_bi3bju_ft8_1plus_MainActivity_00024FT8Encoder_encodeFreeText(JNIEnv *env, jclass, jstring jText, jint freqHz, jint sampleRate) {
    if (sampleRate != 12000) {
        LOGE("encodeFreeText: unsupported sample rate %d", sampleRate);
        return nullptr;
    }

    const char* text = env->GetStringUTFChars(jText, nullptr);
    if (!text) return nullptr;
    size_t textLen = strlen(text);
    if (textLen > 13) {
        LOGE("encodeFreeText: text too long (%zu)", textLen);
        env->ReleaseStringUTFChars(jText, text);
        return nullptr;
    }

    ftx_message_t msg;
    if (ftx_message_encode_free(&msg, text) != FTX_MESSAGE_RC_OK) {
        LOGE("encodeFreeText: ftx_message_encode_free failed");
        env->ReleaseStringUTFChars(jText, text);
        return nullptr;
    }
    env->ReleaseStringUTFChars(jText, text);

    uint8_t tones[FT8_NN];
    ft8_encode(msg.payload, tones);

    const int samples_per_symbol = (int)(sampleRate * FT8_SYMBOL_PERIOD);
    const int num_symbols = FT8_NN;
    const int num_samples = num_symbols * samples_per_symbol;

    jshortArray result = env->NewShortArray(num_samples);
    if (!result) return nullptr;
    jshort* samples = env->GetShortArrayElements(result, nullptr);
    if (!samples) {
        env->DeleteLocalRef(result);
        return nullptr;
    }

    float* float_buffer = (float*)calloc(num_samples, sizeof(float));
    float* symbol_buffer = (float*)malloc(samples_per_symbol * sizeof(float));
    if (!float_buffer || !symbol_buffer) {
        LOGE("Memory allocation failed");
        if (float_buffer) free(float_buffer);
        if (symbol_buffer) free(symbol_buffer);
        env->ReleaseShortArrayElements(result, samples, JNI_ABORT);
        env->DeleteLocalRef(result);
        return nullptr;
    }

    float phase = 0.0f;
    for (int sym = 0; sym < num_symbols; ++sym) {
        float offset = (tones[sym] - 3.5f) * 6.25f;
        float tone_freq = freqHz + offset;
        float phase_inc = 2.0f * M_PI * tone_freq / sampleRate;

        for (int i = 0; i < samples_per_symbol; ++i) {
            symbol_buffer[i] = sinf(phase);
            phase += phase_inc;
            if (phase >= 2.0f * M_PI) phase -= 2.0f * M_PI;
        }
        memcpy(float_buffer + sym * samples_per_symbol, symbol_buffer, samples_per_symbol * sizeof(float));
    }

    for (int i = 0; i < num_samples; ++i) {
        float val = float_buffer[i];
        if (val > 1.0f) val = 1.0f;
        if (val < -1.0f) val = -1.0f;
        samples[i] = (jshort)(val * 32767.0f);
    }

    free(symbol_buffer);
    free(float_buffer);
    env->ReleaseShortArrayElements(result, samples, 0);
    return result;
}

} // extern "C"