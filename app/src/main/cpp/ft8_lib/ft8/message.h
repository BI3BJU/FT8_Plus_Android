#ifndef _INCLUDE_MESSAGE_H_
#define _INCLUDE_MESSAGE_H_

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>   // add size_t definition

#ifdef __cplusplus
extern "C"
{
#endif

/* ========== Public constants ========== */
#define FTX_PAYLOAD_LENGTH_BYTES 10       // Fixed payload length: 10 bytes
#define FTX_MAX_MESSAGE_LENGTH   128      // Maximum decoded text length (including transparent hex)
#define FTX_MAX_MESSAGE_FIELDS   3        // Maximum 3 fields (to, de, extra)
#define FTX_MAX_CALLSIGN_LEN     16       // Maximum callsign length (including terminator)

/* ========== Core message structure ========== */
typedef struct
{
    uint8_t payload[FTX_PAYLOAD_LENGTH_BYTES];
    uint16_t hash;          // Reserved, unused
} ftx_message_t;

/* ========== Message type enumeration ========== */
typedef enum
{
    FTX_MESSAGE_TYPE_FREE_TEXT,      // Free text (i3=0,n3=0)
    FTX_MESSAGE_TYPE_DXPEDITION,     // DXpedition (reserved)
    FTX_MESSAGE_TYPE_EU_VHF,         // EU VHF (reserved)
    FTX_MESSAGE_TYPE_ARRL_FD,        // ARRL Field Day (reserved)
    FTX_MESSAGE_TYPE_TELEMETRY,      // Telemetry (i3=0,n3=5)
    FTX_MESSAGE_TYPE_CONTESTING,     // Contesting (not implemented)
    FTX_MESSAGE_TYPE_STANDARD,       // Standard message (i3=1/2)
    FTX_MESSAGE_TYPE_ARRL_RTTY,      // ARRL RTTY (i3=3)
    FTX_MESSAGE_TYPE_NONSTD_CALL,    // Non-standard call (i3=4)
    FTX_MESSAGE_TYPE_WWROF,          // WWROF (i3=5)
    FTX_MESSAGE_TYPE_TRANSPARENT,    // Transparent data (i3=6)
    FTX_MESSAGE_TYPE_UNKNOWN         // Unknown type
} ftx_message_type_t;

/* ========== Hash type enumeration ========== */
typedef enum
{
    FTX_CALLSIGN_HASH_22_BITS,
    FTX_CALLSIGN_HASH_12_BITS,
    FTX_CALLSIGN_HASH_10_BITS
} ftx_callsign_hash_type_t;

/* ========== External hash table callback interface ========== */
typedef struct
{
    bool (*lookup_hash)(ftx_callsign_hash_type_t hash_type, uint32_t hash, char* callsign);
    void (*save_hash)(const char* callsign, uint32_t n22);
} ftx_callsign_hash_interface_t;

/* ========== Return codes (extended) ========== */
typedef enum
{
    FTX_MESSAGE_RC_OK,
    FTX_MESSAGE_RC_ERROR_CALLSIGN1,   // First callsign invalid
    FTX_MESSAGE_RC_ERROR_CALLSIGN2,   // Second callsign invalid
    FTX_MESSAGE_RC_ERROR_SUFFIX,      // Suffix conflict (both /P and /R)
    FTX_MESSAGE_RC_ERROR_GRID,        // Grid invalid
    FTX_MESSAGE_RC_ERROR_TYPE,        // Unsupported message type or content
    FTX_MESSAGE_RC_ERROR_HASH,        // Hash lookup or save failed
    FTX_MESSAGE_RC_ERROR_TRUNCATION   // Decoded string truncated
} ftx_message_rc_t;

/* ========== Field type enumeration ========== */
typedef enum
{
    FTX_FIELD_UNKNOWN,          // Not recognized
    FTX_FIELD_NONE,             // No field
    FTX_FIELD_TOKEN,            // Fixed token (CQ, DE, QRZ, etc.)
    FTX_FIELD_TOKEN_WITH_ARG,   // Token with argument (e.g., CQ with frequency or letters)
    FTX_FIELD_CALL,             // Callsign
    FTX_FIELD_GRID,             // Grid locator
    FTX_FIELD_RST               // Signal report (RST)
} ftx_field_t;

/* ========== Decode offset information ========== */
typedef struct
{
    ftx_field_t types[FTX_MAX_MESSAGE_FIELDS];
    int16_t offsets[FTX_MAX_MESSAGE_FIELDS];   // Starting offset of each field in the output string
} ftx_message_offsets_t;

/* ========== Transparent data extraction structure ========== */
typedef struct
{
    uint8_t f2;          // 2-bit type flag
    uint8_t bytes[9];    // Up to 9 bytes of user data
    size_t data_len;     // Actual valid byte count (automatically computed during decode)
} ftx_transparent_data_t;

/* ========== Public function declarations ========== */

/* Initialization and type identification */
void ftx_message_init(ftx_message_t* msg);
uint8_t ftx_message_get_i3(const ftx_message_t* msg);
uint8_t ftx_message_get_n3(const ftx_message_t* msg);
ftx_message_type_t ftx_message_get_type(const ftx_message_t* msg);

/* Encoding functions */
ftx_message_rc_t ftx_message_encode(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if, const char* message_text);
ftx_message_rc_t ftx_message_encode_std(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                        const char* call_to, const char* call_de, const char* extra);
ftx_message_rc_t ftx_message_encode_nonstd(ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                           const char* call_to, const char* call_de, const char* extra);
ftx_message_rc_t ftx_message_encode_free(ftx_message_t* msg, const char* text);
ftx_message_rc_t ftx_message_encode_telemetry(ftx_message_t* msg, const uint8_t* telemetry);
/* Transparent encoding: b72 is data, data_len is valid byte count (0~9), zeros are padded if less than 9 */
ftx_message_rc_t ftx_message_encode_transparent(ftx_message_t* msg, uint8_t f2, const uint8_t b72[9]);

/* Decoding functions */
ftx_message_rc_t ftx_message_decode(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                    char* message, ftx_message_offsets_t* offsets);
ftx_message_rc_t ftx_message_decode_std(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                        char* call_to, char* call_de, char* extra,
                                        ftx_field_t field_types[FTX_MAX_MESSAGE_FIELDS]);
ftx_message_rc_t ftx_message_decode_nonstd(const ftx_message_t* msg, ftx_callsign_hash_interface_t* hash_if,
                                           char* call_to, char* call_de, char* extra,
                                           ftx_field_t field_types[FTX_MAX_MESSAGE_FIELDS]);
void ftx_message_decode_free(const ftx_message_t* msg, char* text);
void ftx_message_decode_telemetry_hex(const ftx_message_t* msg, char* telemetry_hex);
void ftx_message_decode_telemetry(const ftx_message_t* msg, uint8_t* telemetry);

/* Transparent decoding */
ftx_message_rc_t ftx_message_decode_transparent(const ftx_message_t* msg, char* text, ftx_transparent_data_t* out_data);
bool ftx_message_get_transparent_data(const ftx_message_t* msg, ftx_transparent_data_t* out_data);

#ifdef FTX_DEBUG_PRINT
void ftx_message_print(ftx_message_t* msg);
#endif

#ifdef __cplusplus
}
#endif

#endif /* _INCLUDE_MESSAGE_H_ */