/* SPDX-License-Identifier: GPL-3.0-only */
#include <assert.h>
#include <string.h>
#include "../native/input-controls/state.h"
static void u32(unsigned char *p,uint32_t v) {for(unsigned i=0;i<4;++i) p[i]=(unsigned char)(v>>(8*i));}
static void u64(unsigned char *p,uint64_t v) {u32(p,(uint32_t)v);u32(p+4,(uint32_t)(v>>32));}
static void axis(unsigned char *p,unsigned i,int16_t v) {p[28+i*2]=(unsigned char)v;p[29+i*2]=(unsigned char)((uint16_t)v>>8);}
int main(void) {
    unsigned char bytes[64]={0};struct zg_state state={0};
    u32(bytes,0x4450475a);u32(bytes+4,1);u32(bytes+8,2);u32(bytes+12,123);
    u64(bytes+16,10000);u32(bytes+24,0xf7ff);u32(bytes+44,1);u64(bytes+48,0x123456789abcdef0ULL);
    u64(bytes+56,1700000000000ULL);
    axis(bytes,0,-32767);axis(bytes,1,32767);axis(bytes,4,32767);axis(bytes,5,16384);axis(bytes,6,-1);axis(bytes,7,1);
    assert(zg_decode(bytes,2,2,10000,&state));
    assert(state.buttons==0xf7ff && state.axes[0]==-32767 && state.axes[1]==32767);
    assert(state.generation==0x123456789abcdef0ULL && state.connected);
    assert(zg_trigger(state.axes[4])==255 && zg_trigger(state.axes[5])==128 && zg_trigger(0)==0);
    assert(zg_decode(bytes,2,2,12000,&state));
    assert(!zg_decode(bytes,2,2,12001,&state));
    assert(!zg_decode(bytes,2,2,9999,&state));
    assert(!zg_decode(bytes,1,1,10000,&state));
    assert(!zg_decode(bytes,2,4,10000,&state));
    assert(!zg_decode(bytes,4,4,10000,&state));
    axis(bytes,0,-32768);assert(!zg_decode(bytes,2,2,10000,&state));axis(bytes,0,-32767);
    axis(bytes,4,-1);assert(!zg_decode(bytes,2,2,10000,&state));axis(bytes,4,32767);
    axis(bytes,6,2);assert(!zg_decode(bytes,2,2,10000,&state));axis(bytes,6,-1);
    u64(bytes+56,0);assert(!zg_decode(bytes,2,2,10000,&state));u64(bytes+56,1700000000000ULL);
    assert(zg_decode_wall(bytes,2,2,1700000000000ULL,&state));
    assert(zg_decode_wall(bytes,2,2,1700000002000ULL,&state));
    assert(!zg_decode_wall(bytes,2,2,1699999999999ULL,&state));
    assert(!zg_decode_wall(bytes,2,2,1700000002001ULL,&state));
    u32(bytes+44,0);assert(!zg_decode(bytes,2,2,10000,&state));u32(bytes+44,1);
    u32(bytes+24,0x800);assert(!zg_decode(bytes,2,2,10000,&state));u32(bytes+24,0x1000);
    u32(bytes+12,0);assert(!zg_decode(bytes,2,2,10000,&state));u32(bytes+12,123);
    u32(bytes+4,2);assert(!zg_decode(bytes,2,2,10000,&state));u32(bytes+4,1);
    assert(zg_decode(bytes,2,2,10000,&state));zg_neutral(&state);
    assert(!state.connected && !state.buttons);
    for(unsigned i=0;i<8;++i) assert(!state.axes[i]);
    return 0;
}
