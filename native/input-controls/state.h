/* SPDX-License-Identifier: GPL-3.0-only
 * Zfold gamepad wire ABI v1. No host struct layout is used for the file.
 */
#ifndef ZFOLD_INPUT_STATE_H
#define ZFOLD_INPUT_STATE_H
#include <stdint.h>
#define ZG_STATE_PATH "/run/zfold-x11/gamepad/state"
#define ZG_EVENT_PATH "/run/zfold-x11/gamepad/event0"
#define ZG_JS_PATH "/run/zfold-x11/gamepad/js0"
#define ZG_NAME "Microsoft X-Box 360 pad"
#define ZG_BUTTON_MASK 0xf7ffu
struct zg_state {
    uint32_t sequence, pid, buttons;
    uint64_t heartbeat, generation;
    int16_t axes[8];
    int connected;
};
static inline uint32_t zg_u32(const unsigned char *p) {
    return (uint32_t)p[0] | (uint32_t)p[1]<<8 | (uint32_t)p[2]<<16 | (uint32_t)p[3]<<24;
}
static inline uint64_t zg_u64(const unsigned char *p) {
    return (uint64_t)zg_u32(p) | (uint64_t)zg_u32(p+4)<<32;
}
static inline void zg_neutral(struct zg_state *s) {
    s->buttons = 0;
    for (unsigned i=0; i<8; ++i) s->axes[i] = 0;
    s->connected = 0;
}
/* The caller reads sequence BEFORE this payload and again AFTER it. */
static inline int zg_decode(const unsigned char p[64], uint32_t before, uint32_t after,
                            uint64_t now, struct zg_state *s) {
    if (zg_u32(p)!=0x4450475a || zg_u32(p+4)!=1 || (before&1) ||
        before!=after || before!=zg_u32(p+8) || zg_u32(p+44)!=1 ||
        !zg_u32(p+12) || zg_u32(p+12)>0x7fffffffu ||
        (zg_u32(p+24)&~ZG_BUTTON_MASK) || !zg_u64(p+56)) return 0;
    uint64_t heartbeat=zg_u64(p+16);
    if (now<heartbeat || now-heartbeat>2000) return 0;
    for (unsigned i=0; i<8; ++i) {
        int16_t v=(int16_t)((unsigned)p[28+2*i] | (unsigned)p[29+2*i]<<8);
        if ((i<4 && v==-32768) || (i>=4 && i<6 && v<0) || (i>=6 && (v < -1 || v > 1))) return 0;
        s->axes[i]=v;
    }
    s->sequence=before; s->pid=zg_u32(p+12); s->buttons=zg_u32(p+24);
    s->heartbeat=heartbeat; s->generation=zg_u64(p+48); s->connected=1;
    return 1;
}
/* PE clients cannot call CLOCK_BOOTTIME and Android may deny /proc/uptime.
 * Their wall-clock heartbeat fails closed on clock jumps until the next publish. */
static inline int zg_decode_wall(const unsigned char p[64],uint32_t before,uint32_t after,
                                 uint64_t wall,struct zg_state *s) {
    uint64_t published=zg_u64(p+56),heartbeat=zg_u64(p+16);
    if (!published || wall<published || wall-published>2000 ||
        heartbeat>UINT64_MAX-(wall-published)) return 0;
    return zg_decode(p,before,after,heartbeat+(wall-published),s);
}
static inline uint8_t zg_trigger(int16_t value) {
    return (uint8_t)(((uint32_t)value*255u+16383u)/32767u);
}
#endif
