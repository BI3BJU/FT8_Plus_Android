#include "message.h"
#include "text.h"
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
#include <assert.h>   // Add assertion support

#define LOG_LEVEL LOG_WARN
#include "debug.h"

/* === Constants and macro definitions (supplementary) === */
#ifndef FTX_MAX_CALLSIGN_LEN
#define FTX_MAX_CALLSIGN_LEN 16
#endif

#define MAX22    ((uint32_t)4194304ul)   // 2^22
#define NTOKENS  ((uint32_t)2063592ul)   // Token space size
#define MAXGRID4 ((uint16_t)32400ul)     // Maximum valid grid value

/* Bit layout description (for reference only):
 * - Standard message (i3=1/2): 28-bit call A + 28-bit call B + 16-bit grid
 * - Non-standard call (i3=4): 12-bit call + 58-bit call + control bits
 * - Free text (i3=0,n3=0): 71-bit data (13 chars × 42-base)
 * - Telemetry (i3=0,n3=5): 71-bit raw data
 * - Transparent (i3=6): 72-bit user data + 2-bit type
 */

/* Static function prototypes */
static void add_brackets(char* result, const char* original, int length);
static bool save_callsign(const ftx_callsign_hash_interface_t* hash_if, const char* callsign, uint32_t* n22_out, uint16_t* n12_out, uint16_t* n10_out);
static bool lookup_callsign(const ftx_callsign_hash_interface_t* hash_if, ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign);
static int parse_cq_modifier(const char* string);
static int32_t pack28(const char* callsign, const ftx_callsign_hash_interface_t* hash_if, uint8_t* ip);
static int unpack28(uint32_t n28, uint8_t ip, uint8_t i3, const ftx_callsign_hash_interface_t* hash_if, char* result, ftx_field_t* field_type);
static bool pack58(const ftx_callsign_hash_interface_t* hash_if, const char* callsign, uint64_t* n58);
static bool unpack58(uint64_t n58, const ftx_callsign_hash_interface_t* hash_if, char* callsign);
static uint16_t packgrid(const char* grid4);
static int unpackgrid(uint16_t igrid4, uint8_t ir, char* extra, ftx_field_t* extra_field_type);
static uint64_t extract_77_bits(const ftx_message_t* msg);
static int safe_append(char* dest, size_t size, const char* fmt, ...);
static void decode_transparent_fields(const ftx_message_t* msg, uint8_t* f2_out, uint8_t b72_out[9]);

/* ========== Exported functions ========== */

void ftx_message_init(ftx_message_t* msg) {
    memset(msg, 0, sizeof(ftx_message_t));
}

uint8_t ftx_message_get_i3(const ftx_message_t* msg) {
    uint64_t bits77 = extract_77_bits(msg);
    return (uint8_t)(bits77 & 0x07);
}

uint8_t ftx_message_get_n3(const ftx_message_t* msg) {
    uint64_t bits77 = extract_77_bits(msg);
    return (uint8_t)((bits77 >> 3) & 0x07);
}

ftx_message_type_t ftx_message_get_type(const ftx_message_t* msg) {
    uint8_t i3 = ftx_message_get_i3(msg);
    switch (i3) {
        case 0: {
            uint8_t n3 = ftx_message_get_n3(msg);
            switch (n3) {
                case 0: return FTX_MESSAGE_TYPE_FREE_TEXT;
                case 1: return FTX_MESSAGE_TYPE_DXPEDITION;
                case 2: return FTX_MESSAGE_TYPE_EU_VHF;
                case 3: case 4: return FTX_MESSAGE_TYPE_ARRL_FD;
                case 5: return FTX_MESSAGE_TYPE_TELEMETRY;
                default: return FTX_MESSAGE_TYPE_UNKNOWN;
            }
        }
        case 1: case 2: return FTX_MESSAGE_TYPE_STANDARD;
        case 3: return FTX_MESSAGE_TYPE_ARRL_RTTY;
        case 4: return FTX_MESSAGE_TYPE_NONSTD_CALL;
        case 5: return FTX_MESSAGE_TYPE_WWROF;
        case 6: return FTX_MESSAGE_TYPE_TRANSPARENT;
        default: return FTX_MESSAGE_TYPE_UNKNOWN;
    }
}

ftx_message_rc_t ftx_message_encode(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if, const char* message_text) {
    char call_to[12];
    char call_de[12];
    char extra[20];

    const char* parse_position = message_text;
    const bool is_cq = starts_with(message_text, "CQ ");
    if (is_cq) {
        parse_position += 3;
        memset(call_to, 0, sizeof(call_to));
        copy_token(call_de, 12, parse_position);
        int cq_modifier_v = parse_cq_modifier(message_text);
        if (cq_modifier_v >= 0) {
            memcpy(call_to, "CQ \0", 4);
            parse_position = copy_token(call_to + 3, sizeof(call_to) - 3, parse_position);
        } else {
            memcpy(call_to, "CQ\0", 3);
        }
    } else {
        parse_position = copy_token(call_to, sizeof(call_to), parse_position);
    }
    parse_position = copy_token(call_de, sizeof(call_de), parse_position);
    parse_position = copy_token(extra, sizeof(extra), parse_position);

    if (call_to[sizeof(call_to)-1] != '\0') return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    if (call_de[sizeof(call_de)-1] != '\0') return FTX_MESSAGE_RC_ERROR_CALLSIGN2;
    if (extra[sizeof(extra)-1] != '\0') return FTX_MESSAGE_RC_ERROR_GRID;

    ftx_message_rc_t rc;
    if (!parse_position[0]) {
        rc = ftx_message_encode_std(msg, hash_if, call_to, call_de, extra);
        if (rc == FTX_MESSAGE_RC_OK) return rc;
        rc = ftx_message_encode_nonstd(msg, hash_if, call_to, call_de, extra);
        if (rc == FTX_MESSAGE_RC_OK) return rc;
    }
    rc = ftx_message_encode_free(msg, message_text);
    if (rc == FTX_MESSAGE_RC_OK) return rc;
    return rc;
}

ftx_message_rc_t ftx_message_encode_std(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                         const char* call_to, const char* call_de, const char* extra) {
    uint8_t ipa, ipb;
    int32_t n28a = pack28(call_to, hash_if, &ipa);
    int32_t n28b = pack28(call_de, hash_if, &ipb);
    if (n28a < 0) return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    if (n28b < 0) return FTX_MESSAGE_RC_ERROR_CALLSIGN2;

    uint8_t i3 = 1;
    if (ends_with(call_to, "/P") || ends_with(call_de, "/P")) {
        i3 = 2;
        if (ends_with(call_to, "/R") || ends_with(call_de, "/R"))
            return FTX_MESSAGE_RC_ERROR_SUFFIX;
    }

    char* slash_de = strchr(call_de, '/');
    uint8_t icq = (uint8_t)equals(call_to, "CQ") || starts_with(call_to, "CQ ");
    if (slash_de && (slash_de - call_de >= 2) && icq && !(equals(slash_de, "/P") || equals(slash_de, "/R")))
        return FTX_MESSAGE_RC_ERROR_CALLSIGN2;

    uint16_t igrid4 = packgrid(extra);
    if (igrid4 == (MAXGRID4 + 1)) return FTX_MESSAGE_RC_ERROR_GRID; // invalid grid

    uint32_t n29a = ((uint32_t)n28a << 1) | ipa;
    uint32_t n29b = ((uint32_t)n28b << 1) | ipb;
    // Note: pack28 has already set ipa/ipb based on suffix, so no need to modify the LSB here.
    // However, if the callsign ends with /R, the LSB must be set (already done by pack28).
    // Also, if /P exists, i3 is set to 2, but it does not affect the bit encoding.

    msg->payload[0] = (uint8_t)(n29a >> 21);
    msg->payload[1] = (uint8_t)(n29a >> 13);
    msg->payload[2] = (uint8_t)(n29a >> 5);
    msg->payload[3] = (uint8_t)(n29a << 3) | (uint8_t)(n29b >> 26);
    msg->payload[4] = (uint8_t)(n29b >> 18);
    msg->payload[5] = (uint8_t)(n29b >> 10);
    msg->payload[6] = (uint8_t)(n29b >> 2);
    msg->payload[7] = (uint8_t)(n29b << 6) | (uint8_t)(igrid4 >> 10);
    msg->payload[8] = (uint8_t)(igrid4 >> 2);
    msg->payload[9] = (uint8_t)(igrid4 << 6) | (uint8_t)(i3 << 3);
    return FTX_MESSAGE_RC_OK;
}

ftx_message_rc_t ftx_message_encode_nonstd(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                           const char* call_to, const char* call_de, const char* extra) {
    uint8_t i3 = 4;
    uint8_t icq = (uint8_t)equals(call_to, "CQ") || starts_with(call_to, "CQ ");
    int len_call_to = strlen(call_to);
    int len_call_de = strlen(call_de);
    if ((icq == 0) && (len_call_to < 3)) return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    if (len_call_de < 3) return FTX_MESSAGE_RC_ERROR_CALLSIGN2;

    uint8_t iflip;
    uint16_t n12;
    uint64_t n58;
    uint8_t nrpt;
    const char* call58;

    if (icq == 0) {
        iflip = 0;
        // Fix: use len_call_de to check if call_de is enclosed in angle brackets
        if (call_de[0] == '<' && call_de[len_call_de-1] == '>') iflip = 1;
        const char* call12 = (iflip == 0) ? call_to : call_de;
        call58 = (iflip == 0) ? call_de : call_to;
        if (!save_callsign(hash_if, call12, NULL, &n12, NULL))
            return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    } else {
        iflip = 0;
        n12 = 0;
        call58 = call_de;
    }
    if (!pack58(hash_if, call58, &n58)) return FTX_MESSAGE_RC_ERROR_CALLSIGN2;

    if (icq != 0) nrpt = 0;
    else if (equals(extra, "RRR")) nrpt = 1;
    else if (equals(extra, "RR73")) nrpt = 2;
    else if (equals(extra, "73")) nrpt = 3;
    else nrpt = 0;

    msg->payload[0] = (uint8_t)(n12 >> 4);
    msg->payload[1] = (uint8_t)(n12 << 4) | (uint8_t)(n58 >> 54);
    msg->payload[2] = (uint8_t)(n58 >> 46);
    msg->payload[3] = (uint8_t)(n58 >> 38);
    msg->payload[4] = (uint8_t)(n58 >> 30);
    msg->payload[5] = (uint8_t)(n58 >> 22);
    msg->payload[6] = (uint8_t)(n58 >> 14);
    msg->payload[7] = (uint8_t)(n58 >> 6);
    msg->payload[8] = (uint8_t)(n58 << 2) | (uint8_t)(iflip << 1) | (uint8_t)(nrpt >> 1);
    msg->payload[9] = (uint8_t)(nrpt << 7) | (uint8_t)(icq << 6) | (uint8_t)(i3 << 3);
    return FTX_MESSAGE_RC_OK;
}

ftx_message_rc_t ftx_message_encode_free(ftx_message_t* msg, const char* text) {
    uint8_t str_len = strlen(text);
    if (str_len > 13) return FTX_MESSAGE_RC_ERROR_TYPE;
    uint8_t b71[9] = {0};
    for (int idx = 0; idx < 13; idx++) {
        char c = (idx < str_len) ? text[idx] : ' ';
        int cid = nchar(c, FT8_CHAR_TABLE_FULL);
        if (cid == -1) return FTX_MESSAGE_RC_ERROR_TYPE;
        uint16_t rem = cid;
        for (int i = 8; i >= 0; i--) {
            rem += b71[i] * 42;
            b71[i] = rem & 0xff;
            rem >>= 8;
        }
    }
    ftx_message_rc_t ret = ftx_message_encode_telemetry(msg, b71);
    msg->payload[9] = 0;
    return ret;
}

ftx_message_rc_t ftx_message_encode_telemetry(ftx_message_t* msg, const uint8_t* telemetry) {
    uint8_t carry = 0;
    for (int i = 8; i >= 0; --i) {
        msg->payload[i] = (telemetry[i] << 1) | (carry >> 7);
        carry = telemetry[i] & 0x80;
    }
    return FTX_MESSAGE_RC_OK;
}

ftx_message_rc_t ftx_message_decode(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                    char* message, ftx_message_offsets_t* offsets) {
    char buf[384];
    char* field1 = buf;
    char* field2 = buf + 128;
    char* field3 = buf + 256;
    message[0] = '\0';
    for (int i = 0; i < FTX_MAX_MESSAGE_FIELDS; ++i) {
        offsets->types[i] = FTX_FIELD_UNKNOWN;
        offsets->offsets[i] = -1;
    }

    ftx_message_type_t msg_type = ftx_message_get_type(msg);
    ftx_message_rc_t rc;
    switch (msg_type) {
        case FTX_MESSAGE_TYPE_STANDARD:
            rc = ftx_message_decode_std(msg, hash_if, field1, field2, field3, offsets->types);
            break;
        case FTX_MESSAGE_TYPE_NONSTD_CALL:
            rc = ftx_message_decode_nonstd(msg, hash_if, field1, field2, field3, offsets->types);
            break;
        case FTX_MESSAGE_TYPE_FREE_TEXT:
            ftx_message_decode_free(msg, field1);
            field2 = field3 = NULL;
            rc = FTX_MESSAGE_RC_OK;
            break;
        case FTX_MESSAGE_TYPE_TELEMETRY:
            ftx_message_decode_telemetry_hex(msg, field1);
            field2 = field3 = NULL;
            rc = FTX_MESSAGE_RC_OK;
            break;
        case FTX_MESSAGE_TYPE_TRANSPARENT:
            rc = ftx_message_decode_transparent(msg, field1, NULL);
            field2 = field3 = NULL;
            break;
        default:
            field1 = NULL;
            rc = FTX_MESSAGE_RC_ERROR_TYPE;
            break;
    }

    if (field1) {
        const char* msg_start = message;
        message = append_string(message, field1);
        offsets->offsets[0] = 0;
        if (field2) {
            message = append_string(message, " ");
            offsets->offsets[1] = message - msg_start;
            message = append_string(message, field2);
            if (field3 && field3[0]) {
                message = append_string(message, " ");
                offsets->offsets[2] = message - msg_start;
                message = append_string(message, field3);
            }
        }
    }
    return rc;
}

ftx_message_rc_t ftx_message_decode_std(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                        char* call_to, char* call_de, char* extra,
                                        ftx_field_t field_types[FTX_MAX_MESSAGE_FIELDS]) {
    uint32_t n29a = (msg->payload[0] << 21) | (msg->payload[1] << 13) | (msg->payload[2] << 5) | (msg->payload[3] >> 3);
    uint32_t n29b = ((msg->payload[3] & 0x07) << 26) | (msg->payload[4] << 18) | (msg->payload[5] << 10) | (msg->payload[6] << 2) | (msg->payload[7] >> 6);
    uint8_t ir = (msg->payload[7] >> 5) & 1;
    uint16_t igrid4 = ((msg->payload[7] & 0x1F) << 10) | (msg->payload[8] << 2) | (msg->payload[9] >> 6);
    uint8_t i3 = (msg->payload[9] >> 3) & 7;
    call_to[0] = call_de[0] = extra[0] = '\0';

    int ret;
    ret = unpack28(n29a >> 1, n29a & 1, i3, hash_if, call_to, &field_types[0]);
    if (ret < 0) return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    ret = unpack28(n29b >> 1, n29b & 1, i3, hash_if, call_de, &field_types[1]);
    if (ret < 0) return FTX_MESSAGE_RC_ERROR_CALLSIGN2;
    ret = unpackgrid(igrid4, ir, extra, &field_types[2]);
    if (ret < 0) return FTX_MESSAGE_RC_ERROR_GRID;
    return FTX_MESSAGE_RC_OK;
}

ftx_message_rc_t ftx_message_decode_nonstd(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                           char* call_to, char* call_de, char* extra,
                                           ftx_field_t field_types[FTX_MAX_MESSAGE_FIELDS]) {
    uint16_t n12 = (msg->payload[0] << 4) | (msg->payload[1] >> 4);
    uint64_t n58 = ((uint64_t)(msg->payload[1] & 0x0F) << 54) | ((uint64_t)msg->payload[2] << 46) |
                   ((uint64_t)msg->payload[3] << 38) | ((uint64_t)msg->payload[4] << 30) |
                   ((uint64_t)msg->payload[5] << 22) | ((uint64_t)msg->payload[6] << 14) |
                   ((uint64_t)msg->payload[7] << 6) | ((uint64_t)msg->payload[8] >> 2);
    uint8_t iflip = (msg->payload[8] >> 1) & 1;
    uint8_t nrpt = ((msg->payload[8] & 1) << 1) | (msg->payload[9] >> 7);
    uint8_t icq = (msg->payload[9] >> 6) & 1;
    uint8_t i3 = (msg->payload[9] >> 3) & 7;

    char call_decoded[FTX_MAX_CALLSIGN_LEN + 1];
    char call_3[FTX_MAX_CALLSIGN_LEN + 1];
    // Decode 58-bit callsign
    if (!unpack58(n58, hash_if, call_decoded)) {
        return FTX_MESSAGE_RC_ERROR_CALLSIGN2;
    }
    // Lookup 12-bit callsign
    if (!lookup_callsign(hash_if, FTX_CALLSIGN_HASH_12_BITS, n12, call_3)) {
        return FTX_MESSAGE_RC_ERROR_CALLSIGN1;
    }
    char* call_1 = iflip ? call_decoded : call_3;
    char* call_2 = iflip ? call_3 : call_decoded;

    if (icq == 0) {
        strncpy(call_to, call_1, FTX_MAX_CALLSIGN_LEN);
        call_to[FTX_MAX_CALLSIGN_LEN] = '\0';
        field_types[0] = FTX_FIELD_CALL;
        if (nrpt == 1) { strncpy(extra, "RRR", 4); field_types[2] = FTX_FIELD_TOKEN; }
        else if (nrpt == 2) { strncpy(extra, "RR73", 5); field_types[2] = FTX_FIELD_TOKEN; }
        else if (nrpt == 3) { strncpy(extra, "73", 3); field_types[2] = FTX_FIELD_TOKEN; }
        else { extra[0] = '\0'; field_types[2] = FTX_FIELD_NONE; }
    } else {
        strncpy(call_to, "CQ", 3);
        call_to[2] = '\0';
        extra[0] = '\0';
        field_types[0] = FTX_FIELD_TOKEN;
        field_types[2] = FTX_FIELD_NONE;
    }
    strncpy(call_de, call_2, FTX_MAX_CALLSIGN_LEN);
    call_de[FTX_MAX_CALLSIGN_LEN] = '\0';
    field_types[1] = FTX_FIELD_CALL;
    return FTX_MESSAGE_RC_OK;
}

void ftx_message_decode_free(const ftx_message_t* msg, char* text) {
    uint8_t b71[9];
    ftx_message_decode_telemetry(msg, b71);
    char c14[14];
    c14[13] = 0;
    for (int idx = 12; idx >= 0; --idx) {
        uint16_t rem = 0;
        for (int i = 0; i < 9; ++i) {
            rem = (rem << 8) | b71[i];
            b71[i] = rem / 42;
            rem %= 42;
        }
        c14[idx] = charn(rem, FT8_CHAR_TABLE_FULL);
    }
    strcpy(text, trim(c14));
}

void ftx_message_decode_telemetry_hex(const ftx_message_t* msg, char* telemetry_hex) {
    uint8_t b71[9];
    ftx_message_decode_telemetry(msg, b71);
    for (int i = 0; i < 9; ++i) {
        uint8_t n1 = b71[i] >> 4;
        uint8_t n2 = b71[i] & 0x0F;
        telemetry_hex[i*2] = (n1 > 9) ? (n1 - 10 + 'A') : (n1 + '0');
        telemetry_hex[i*2+1] = (n2 > 9) ? (n2 - 10 + 'A') : (n2 + '0');
    }
    telemetry_hex[18] = '\0';
}

void ftx_message_decode_telemetry(const ftx_message_t* msg, uint8_t* telemetry) {
    uint8_t carry = 0;
    for (int i = 0; i < 9; ++i) {
        telemetry[i] = (carry << 7) | (msg->payload[i] >> 1);
        carry = msg->payload[i] & 1;
    }
}

/* ---------- Transparent message decoding ---------- */
static int safe_append(char* dest, size_t size, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int ret = vsnprintf(dest, size, fmt, args);
    va_end(args);
    return ret;
}

static void decode_transparent_fields(const ftx_message_t* msg, uint8_t* f2_out, uint8_t b72_out[9]) {
    const uint8_t* p = msg->payload;
    uint8_t bits[80];
    for (int i = 0; i < 10; i++) {
        uint8_t byte = p[i];
        for (int j = 0; j < 8; j++) {
            bits[i*8 + j] = (byte >> (7 - j)) & 1;
        }
    }
    uint8_t f2 = (bits[72] << 1) | bits[73];
    if (f2_out) *f2_out = f2;
    memset(b72_out, 0, 9);
    for (int i = 0; i < 72; i++) {
        int byte_idx = i / 8;
        int bit_off = 7 - (i % 8);
        if (bits[i]) b72_out[byte_idx] |= (1 << bit_off);
    }
}

ftx_message_rc_t ftx_message_decode_transparent(const ftx_message_t* msg, char* text, ftx_transparent_data_t* out_data) {
    if (!msg || !text) return FTX_MESSAGE_RC_ERROR_TYPE;
    uint8_t f2;
    uint8_t b72_bytes[9];
    decode_transparent_fields(msg, &f2, b72_bytes);
    if (out_data) {
        out_data->f2 = f2;
        memcpy(out_data->bytes, b72_bytes, 9);
        size_t len = 9;
        while (len > 0 && out_data->bytes[len-1] == 0) len--;
        out_data->data_len = len;
    }
    size_t data_len = 9;
    while (data_len > 0 && b72_bytes[data_len-1] == 0) data_len--;
    char b72_hex[19];
    for (int i = 0; i < 9; i++) sprintf(b72_hex + i*2, "%02x", b72_bytes[i]);
    b72_hex[18] = '\0';
    char* p = text;
    size_t remaining = FTX_MAX_MESSAGE_LENGTH;
    int written = safe_append(p, remaining, "[Transparent] f2:%d b72:0x%s", f2, b72_hex);
    if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
    p += written; remaining -= written;
    if (f2 == 0 && data_len > 0) {
        char utf8_buf[10];
        memcpy(utf8_buf, b72_bytes, data_len);
        utf8_buf[data_len] = '\0';
        bool printable = true;
        for (size_t i = 0; i < data_len; i++) {
            if ((unsigned char)utf8_buf[i] < 0x20 && utf8_buf[i] != '\n' && utf8_buf[i] != '\r') {
                printable = false; break;
            }
        }
        if (printable) {
            written = safe_append(p, remaining, " | %.*s", (int)data_len, utf8_buf);
            if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
            p += written; remaining -= written;
        } else {
            written = safe_append(p, remaining, " | hex:");
            if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
            p += written; remaining -= written;
            for (size_t i = 0; i < data_len; i++) {
                written = safe_append(p, remaining, "%02x", b72_bytes[i]);
                if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
                p += written; remaining -= written;
            }
        }
    } else if (data_len > 0) {
        written = safe_append(p, remaining, " | hex:");
        if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
        p += written; remaining -= written;
        for (size_t i = 0; i < data_len; i++) {
            written = safe_append(p, remaining, "%02x", b72_bytes[i]);
            if (written < 0 || written >= (int)remaining) { text[FTX_MAX_MESSAGE_LENGTH-1] = '\0'; return FTX_MESSAGE_RC_OK; }
            p += written; remaining -= written;
        }
    }
    text[FTX_MAX_MESSAGE_LENGTH-1] = '\0';
    return FTX_MESSAGE_RC_OK;
}

bool ftx_message_get_transparent_data(const ftx_message_t* msg, ftx_transparent_data_t* out_data) {
    if (!msg || !out_data) return false;
    uint8_t f2;
    uint8_t b72_bytes[9];
    decode_transparent_fields(msg, &f2, b72_bytes);
    out_data->f2 = f2;
    memcpy(out_data->bytes, b72_bytes, 9);
    size_t len = 9;
    while (len > 0 && out_data->bytes[len-1] == 0) len--;
    out_data->data_len = len;
    return true;
}

/* ---------- Transparent message encoding (transmit) ---------- */
ftx_message_rc_t ftx_message_encode_transparent(ftx_message_t* msg, uint8_t f2, const uint8_t b72[9]) {
    if (!msg || !b72) return FTX_MESSAGE_RC_ERROR_TYPE;
    if (f2 > 3) return FTX_MESSAGE_RC_ERROR_TYPE;

    uint8_t bits[80] = {0};
    for (int i = 0; i < 72; i++) {
        int byte_idx = i / 8;
        int bit_idx = 7 - (i % 8);
        if (b72[byte_idx] & (1 << bit_idx)) bits[i] = 1;
    }
    bits[72] = (f2 >> 1) & 1;
    bits[73] = f2 & 1;
    bits[74] = 1; bits[75] = 1; bits[76] = 0; // i3=6 (110)
    for (int i = 0; i < 10; i++) {
        uint8_t byte = 0;
        for (int j = 0; j < 8; j++) byte |= (bits[i*8 + j] << (7 - j));
        msg->payload[i] = byte;
    }
    msg->hash = 0;
    return FTX_MESSAGE_RC_OK;
}

/* ---------- Helper functions ---------- */
static uint64_t extract_77_bits(const ftx_message_t* msg) {
    uint64_t bits77 = 0;
    for (int i = 0; i < 10; ++i) bits77 = (bits77 << 8) | msg->payload[i];
    return bits77 >> 3;
}

static void add_brackets(char* result, const char* original, int length) {
    result[0] = '<';
    memcpy(result+1, original, length);
    result[length+1] = '>';
    result[length+2] = '\0';
}

static bool save_callsign(const ftx_callsign_hash_interface_t* hash_if, const char* callsign,
                          uint32_t* n22_out, uint16_t* n12_out, uint16_t* n10_out) {
    uint64_t n58 = 0;
    int i = 0;
    while (callsign[i] && i < 11) {
        int j = nchar(callsign[i], FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0) return false;
        n58 = 38 * n58 + j;
        i++;
    }
    while (i < 11) { n58 *= 38; i++; }
    uint32_t n22 = ((47055833459ull * n58) >> (64-22)) & 0x3FFFFFul;
    if (n22_out) *n22_out = n22;
    if (n12_out) *n12_out = n22 >> 10;
    if (n10_out) *n10_out = n22 >> 12;
    if (hash_if) hash_if->save_hash(callsign, n22);
    return true;
}

/* Fix: angle bracket handling + return value improvement */
static bool lookup_callsign(const ftx_callsign_hash_interface_t* hash_if, ftx_callsign_hash_type_t type,
                            uint32_t hash, char* callsign) {
    char c11[FTX_MAX_CALLSIGN_LEN + 1];
    bool found = hash_if && hash_if->lookup_hash(type, hash, c11);
    if (!found) {
        strncpy(callsign, "<...>", FTX_MAX_CALLSIGN_LEN);
        callsign[FTX_MAX_CALLSIGN_LEN] = '\0';
        return false;
    } else {
        add_brackets(callsign, c11, strlen(c11));
        return true;
    }
}

int32_t pack_basecall(const char* callsign, int length) {
    if (length <= 2) return -1;
    char c6[6] = {' ',' ',' ',' ',' ',' '};
    if (starts_with(callsign, "3DA0") && length > 4 && length <= 7) {
        memcpy(c6, "3D0", 3);
        memcpy(c6+3, callsign+4, length-4);
    } else if (starts_with(callsign, "3X") && is_letter(callsign[2]) && length <= 7) {
        memcpy(c6, "Q", 1);
        memcpy(c6+1, callsign+2, length-2);
    } else {
        if (is_digit(callsign[2]) && length <= 6) memcpy(c6, callsign, length);
        else if (is_digit(callsign[1]) && length <= 5) memcpy(c6+1, callsign, length);
    }
    int i0 = nchar(c6[0], FT8_CHAR_TABLE_ALPHANUM_SPACE);
    int i1 = nchar(c6[1], FT8_CHAR_TABLE_ALPHANUM);
    int i2 = nchar(c6[2], FT8_CHAR_TABLE_NUMERIC);
    int i3 = nchar(c6[3], FT8_CHAR_TABLE_LETTERS_SPACE);
    int i4 = nchar(c6[4], FT8_CHAR_TABLE_LETTERS_SPACE);
    int i5 = nchar(c6[5], FT8_CHAR_TABLE_LETTERS_SPACE);
    if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0 || i4 < 0 || i5 < 0) return -1;
    int32_t n = i0;
    n = n*36 + i1;
    n = n*10 + i2;
    n = n*27 + i3;
    n = n*27 + i4;
    n = n*27 + i5;
    return n;
}

static int parse_cq_modifier(const char* string) {
    int nnum = 0, nlet = 0, m = 0;
    for (int i = 3; i < 8; ++i) {
        if (!string[i] || is_space(string[i])) break;
        else if (is_digit(string[i])) nnum++;
        else if (is_letter(string[i])) {
            nlet++;
            m = 27*m + (string[i] - 'A' + 1);
        } else return -1;
    }
    if (nnum == 3 && nlet == 0) return atoi(string+3);
    if (nnum == 0 && nlet <= 4) return 1000 + m;
    return -1;
}

static int32_t pack28(const char* callsign, const ftx_callsign_hash_interface_t* hash_if, uint8_t* ip) {
    *ip = 0;
    if (equals(callsign, "DE")) return 0;
    if (equals(callsign, "QRZ")) return 1;
    if (equals(callsign, "CQ")) return 2;
    int len = strlen(callsign);
    if (starts_with(callsign, "CQ ") && len < 8) {
        int v = parse_cq_modifier(callsign);
        if (v < 0) return -1;
        return 3 + v;
    }
    int len_base = len;
    if (ends_with(callsign, "/P") || ends_with(callsign, "/R")) {
        *ip = 1;
        len_base = len - 2;
    }
    int32_t n28 = pack_basecall(callsign, len_base);
    if (n28 >= 0) {
        if (!save_callsign(hash_if, callsign, NULL, NULL, NULL)) return -1;
        return NTOKENS + MAX22 + (uint32_t)n28;
    }
    if (len >= 3 && len <= 11) {
        uint32_t n22;
        if (!save_callsign(hash_if, callsign, &n22, NULL, NULL)) return -1;
        *ip = 0;
        return NTOKENS + n22;
    }
    return -1;
}

static int unpack28(uint32_t n28, uint8_t ip, uint8_t i3, const ftx_callsign_hash_interface_t* hash_if,
                    char* result, ftx_field_t* field_type) {
    if (n28 < NTOKENS) {
        if (n28 <= 2) {
            if (n28 == 0) strcpy(result, "DE");
            else if (n28 == 1) strcpy(result, "QRZ");
            else strcpy(result, "CQ");
            *field_type = FTX_FIELD_TOKEN;
            return 0;
        }
        if (n28 <= 1002) {
            strcpy(result, "CQ ");
            int_to_dd(result+3, n28-3, 3, false);
            *field_type = FTX_FIELD_TOKEN_WITH_ARG;
            return 0;
        }
        if (n28 <= 532443) {
            uint32_t n = n28 - 1003;
            char aaaa[5] = {0};
            for (int i = 3; ; i--) {
                aaaa[i] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE);
                if (i == 0) break;
                n /= 27;
            }
            strcpy(result, "CQ ");
            strcat(result, trim_front(aaaa, ' '));
            *field_type = FTX_FIELD_TOKEN_WITH_ARG;
            return 0;
        }
        return -1;
    }
    n28 -= NTOKENS;
    if (n28 < MAX22) {
        if (!lookup_callsign(hash_if, FTX_CALLSIGN_HASH_22_BITS, n28, result)) {
            return -1;   // hash lookup failed, return error
        }
        *field_type = FTX_FIELD_CALL;
        return 0;
    }
    uint32_t n = n28 - MAX22;
    char callsign[7];
    callsign[5] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE); n /= 27;
    callsign[4] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE); n /= 27;
    callsign[3] = charn(n % 27, FT8_CHAR_TABLE_LETTERS_SPACE); n /= 27;
    callsign[2] = charn(n % 10, FT8_CHAR_TABLE_NUMERIC); n /= 10;
    callsign[1] = charn(n % 36, FT8_CHAR_TABLE_ALPHANUM); n /= 36;
    callsign[0] = charn(n % 37, FT8_CHAR_TABLE_ALPHANUM_SPACE);
    callsign[6] = '\0';
    if (starts_with(callsign, "3D0") && !is_space(callsign[3])) {
        memcpy(result, "3DA0", 4);
        trim_copy(result+4, callsign+3);
    } else if (callsign[0] == 'Q' && is_letter(callsign[1])) {
        memcpy(result, "3X", 2);
        trim_copy(result+2, callsign+1);
    } else {
        trim_copy(result, callsign);
    }
    int len = strlen(result);
    if (len < 3) return -1;
    if (ip) {
        if (i3 == 1) strcat(result, "/R");
        else if (i3 == 2) strcat(result, "/P");
        else return -2;
    }
    if (!save_callsign(hash_if, result, NULL, NULL, NULL)) {
        return -1;   // save failed, but result is already generated; should return error
    }
    *field_type = FTX_FIELD_CALL;
    return 0;
}

/* Fix: properly handle callsigns enclosed in angle brackets */
static bool pack58(const ftx_callsign_hash_interface_t* hash_if, const char* callsign, uint64_t* n58) {
    const char* src = callsign;
    char tmp[12] = {0};
    int len = 0;

    // If the first character is '<', try to extract the inner string up to '>'
    if (*src == '<') {
        src++; // skip '<'
        const char* end = strchr(src, '>');
        if (!end) return false; // missing closing bracket
        len = end - src;
        if (len > 11) return false;
        memcpy(tmp, src, len);
        tmp[len] = '\0';
        src = tmp;
        // ignore any remaining characters (should not occur)
    }

    uint64_t res = 0;
    char c11[12];
    int i = 0;
    while (*src && *src != '<' && i < 11) {
        c11[i] = *src;
        int j = nchar(*src, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (j < 0) return false;
        res = res*38 + j;
        src++; i++;
    }
    c11[i] = '\0';
    if (!save_callsign(hash_if, c11, NULL, NULL, NULL)) return false;
    *n58 = res;
    return true;
}

/* Fix: enlarge buffer, add error return */
static bool unpack58(uint64_t n58, const ftx_callsign_hash_interface_t* hash_if, char* callsign) {
    char c11[FTX_MAX_CALLSIGN_LEN + 1];
    uint64_t tmp = n58;
    for (int i = 10; ; i--) {
        c11[i] = charn(tmp % 38, FT8_CHAR_TABLE_ALPHANUM_SPACE_SLASH);
        if (i == 0) break;
        tmp /= 38;
    }
    c11[11] = '\0';
    trim_copy(callsign, c11);
    if (strlen(callsign) < 3) return false;
    return save_callsign(hash_if, callsign, NULL, NULL, NULL);
}

static uint16_t packgrid(const char* grid4) {
    if (!grid4 || grid4[0] == '\0') return MAXGRID4 + 1;
    if (equals(grid4, "RRR")) return MAXGRID4 + 2;
    if (equals(grid4, "RR73")) return MAXGRID4 + 3;
    if (equals(grid4, "73")) return MAXGRID4 + 4;
    if (in_range(grid4[0], 'A', 'R') && in_range(grid4[1], 'A', 'R') && is_digit(grid4[2]) && is_digit(grid4[3])) {
        uint16_t ig = (grid4[0]-'A');
        ig = ig*18 + (grid4[1]-'A');
        ig = ig*10 + (grid4[2]-'0');
        ig = ig*10 + (grid4[3]-'0');
        return ig;
    }
    if (grid4[0] == 'R') {
        int dd = dd_to_int(grid4+1, 3);
        // Check if dd is within valid range (0~999, but actually should be 0~99)
        if (dd < 0 || dd > 999) return MAXGRID4 + 1;
        uint16_t val = MAXGRID4 + 35 + dd;
        // Prevent overflow: val max is 33434, safe
        return val | 0x8000;
    } else {
        int dd = dd_to_int(grid4, 3);
        if (dd < 0 || dd > 999) return MAXGRID4 + 1;
        return MAXGRID4 + 35 + dd;
    }
}

static int unpackgrid(uint16_t igrid4, uint8_t ir, char* extra, ftx_field_t* extra_field_type) {
    char* dst = extra;
    if (igrid4 <= MAXGRID4) {
        if (ir) dst = stpcpy(dst, "R ");
        uint16_t n = igrid4;
        dst[3] = '0' + (n % 10); n /= 10;
        dst[2] = '0' + (n % 10); n /= 10;
        dst[1] = 'A' + (n % 18); n /= 18;
        dst[0] = 'A' + (n % 18);
        dst[4] = '\0';
        *extra_field_type = FTX_FIELD_GRID;
    } else {
        int irpt = igrid4 - MAXGRID4;
        switch (irpt) {
            case 1: dst[0] = '\0'; *extra_field_type = FTX_FIELD_NONE; break;
            case 2: strcpy(dst, "RRR"); *extra_field_type = FTX_FIELD_TOKEN; break;
            case 3: strcpy(dst, "RR73"); *extra_field_type = FTX_FIELD_TOKEN; break;
            case 4: strcpy(dst, "73"); *extra_field_type = FTX_FIELD_TOKEN; break;
            default:
                if (ir) *dst++ = 'R';
                int_to_dd(dst, irpt - 35, 2, true);
                *extra_field_type = FTX_FIELD_RST;
                break;
        }
    }
    return 0;
}