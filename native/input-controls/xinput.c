/* SPDX-License-Identifier: GPL-3.0-only
 * Native PE XInput replacement for Wine (both i386 WoW64 and x86_64).
 * Deliberately no CRT, Unix ELF dependency, rumble, audio or DirectInput claim.
 */
#include "state.h"
#include <stddef.h>
#ifdef _M_IX86
#define WINAPI __stdcall
#else
#define WINAPI
#endif
#define IMPORT __declspec(dllimport)
typedef void *HANDLE;
typedef uint32_t DWORD;
typedef int BOOL;
typedef uint16_t WORD;
typedef uint8_t BYTE;
IMPORT HANDLE WINAPI CreateFileA(const char *,DWORD,DWORD,void *,DWORD,DWORD,HANDLE);
IMPORT BOOL WINAPI ReadFile(HANDLE,void *,DWORD,DWORD *,void *);
IMPORT BOOL WINAPI SetFilePointerEx(HANDLE,int64_t,int64_t *,DWORD);
IMPORT BOOL WINAPI CloseHandle(HANDLE);
IMPORT DWORD WINAPI GetFileAttributesA(const char *);
IMPORT DWORD WINAPI GetLastError(void);
IMPORT void WINAPI Sleep(DWORD);
struct filetime {DWORD low,high;};
IMPORT void WINAPI GetSystemTimeAsFileTime(struct filetime *);
#define BAD_HANDLE ((HANDLE)(intptr_t)-1)
#define SUCCESS 0u
#define NOT_SUPPORTED 50u
#define INVALID_PARAMETER 87u
#define DEVICE_NOT_CONNECTED 1167u
struct gamepad {WORD buttons;BYTE lt,rt;int16_t lx,ly,rx,ry;};
struct input_state {DWORD packet;struct gamepad gamepad;};
struct vibration {WORD left,right;};
struct capabilities {BYTE type,subtype;WORD flags;struct gamepad gamepad;struct vibration vibration;};
struct capabilities_ex {struct capabilities capabilities;WORD vendor,product,version;DWORD unknown;};
_Static_assert(sizeof(struct gamepad)==12,"XINPUT_GAMEPAD ABI");
_Static_assert(sizeof(struct input_state)==16,"XINPUT_STATE ABI");
_Static_assert(sizeof(struct capabilities)==20,"XINPUT_CAPABILITIES ABI");
_Static_assert(sizeof(struct capabilities_ex)==32,"XINPUT_CAPABILITIES_EX ABI");
static volatile long guard;
static int enabled=1,was_connected,have_generation;
static uint64_t generation;
static DWORD packet;
static struct gamepad previous;
static void enter(void) {while (__sync_lock_test_and_set(&guard,1)) Sleep(0);}
static void leave(void) {__sync_lock_release(&guard);}
static void zero(void *pointer,unsigned size) {unsigned char *p=pointer;while(size--) *p++=0;}
static HANDLE open_file(const char *path) {
    return CreateFileA(path,0x80000000u,7,NULL,3,0x80,NULL);
}
static int read_at(HANDLE file,void *data,DWORD size,DWORD offset) {
    DWORD count=0;
    return SetFilePointerEx(file,(int64_t)offset,NULL,0) && ReadFile(file,data,size,&count,NULL) && count==size;
}
static uint64_t walltime(void) {
    struct filetime time;
    GetSystemTimeAsFileTime(&time);
    uint64_t ticks=(uint64_t)time.high<<32 | time.low;
    if (ticks<116444736000000000ULL) return 0;
    ticks-=116444736000000000ULL;
#ifdef _M_IX86
    /* Divide the high word first so EDX < divisor. Avoid an unavailable CRT
     * __aulldiv import in the freestanding Win32 DLL. */
    uint32_t high=(uint32_t)(ticks>>32),quotient,remainder;
    __asm__("divl %4" : "=a"(quotient),"=d"(remainder)
            : "a"((uint32_t)ticks),"d"(high%10000u),"r"(10000u));
    return (uint64_t)(high/10000u)<<32 | quotient;
#else
    return ticks/10000;
#endif
}
static int owner_alive(DWORD pid) {
    char path[32]="Z:\\proc\\",digits[10];unsigned count=0,pos=8;
    do {digits[count++]=(char)('0'+pid%10);pid/=10;} while(pid);
    while(count) path[pos++]=digits[--count];path[pos]=0;
    if (GetFileAttributesA(path)!=0xffffffffu) return 1;
    /* Android can deny proc metadata despite a valid same-app state file. */
    return GetLastError()==5;
}
static int snapshot(struct zg_state *state) {
    zero(state,sizeof(*state));
    HANDLE file=open_file("Z:\\run\\zfold-x11\\gamepad\\state");
    if (file==BAD_HANDLE) return 0;
    unsigned char bytes[64],before[4],after[4];int good=0;
    for (unsigned retry=0;retry<3;++retry) {
        if (!read_at(file,before,4,8) || (zg_u32(before)&1) || !read_at(file,bytes,64,0) || !read_at(file,after,4,8)) continue;
        if (zg_decode_wall(bytes,zg_u32(before),zg_u32(after),walltime(),state) && owner_alive(state->pid)) {good=1;break;}
        zg_neutral(state);
    }
    CloseHandle(file);return good;
}
static void gamepad(const struct zg_state *s,struct gamepad *g,int guide) {
    g->buttons=(WORD)(s->buttons&(guide?ZG_BUTTON_MASK:ZG_BUTTON_MASK&~0x400u));
    g->lt=zg_trigger(s->axes[4]);g->rt=zg_trigger(s->axes[5]);
    g->lx=s->axes[0];g->ly=(int16_t)-s->axes[1];g->rx=s->axes[2];g->ry=(int16_t)-s->axes[3];
}
static DWORD get_state(DWORD index,struct input_state *out,int guide) {
    if (!out) return INVALID_PARAMETER;
    zero(out,sizeof(*out));if (index) return DEVICE_NOT_CONNECTED;
    enter();struct zg_state s;int connected=snapshot(&s);
    if (connected && have_generation && s.generation!=generation) {
        generation=s.generation;connected=0; /* expose a disconnect across publishers */
    } else if (connected) {generation=s.generation;have_generation=1;}
    if (!connected) {was_connected=0;leave();return DEVICE_NOT_CONNECTED;}
    struct gamepad value;gamepad(&s,&value,1);
    if (!enabled) zero(&value,sizeof(value));
    int changed=!was_connected;
    const unsigned char *a=(const unsigned char *)&value,*b=(const unsigned char *)&previous;
    for (unsigned i=0;i<sizeof(value);++i) if (a[i]!=b[i]) {changed=1;break;}
    if (changed) ++packet;
    previous=value;was_connected=1;out->packet=packet;out->gamepad=value;
    if (!guide) out->gamepad.buttons&=(WORD)~0x400u;
    leave();return SUCCESS;
}
DWORD WINAPI XInputGetState(DWORD index,struct input_state *state) {return get_state(index,state,0);}
DWORD WINAPI XInputGetStateEx(DWORD index,struct input_state *state) {return get_state(index,state,1);}
void WINAPI XInputEnable(BOOL value) {enter();enabled=!!value;leave();}
DWORD WINAPI XInputGetCapabilities(DWORD index,DWORD flags,struct capabilities *out) {
    if (!out || (flags&~1u)) return INVALID_PARAMETER;
    zero(out,sizeof(*out));struct zg_state state;
    if (index || !snapshot(&state)) return DEVICE_NOT_CONNECTED;
    out->type=1;out->subtype=1; /* gamepad, wired; no voice/wireless/FFB flags */
    out->gamepad.buttons=(WORD)(ZG_BUTTON_MASK&~0x400u);out->gamepad.lt=out->gamepad.rt=255;
    out->gamepad.lx=out->gamepad.ly=out->gamepad.rx=out->gamepad.ry=32767;
    return SUCCESS;
}
DWORD WINAPI XInputGetCapabilitiesEx(DWORD reserved,DWORD index,DWORD flags,struct capabilities_ex *out) {
    if (!out || reserved!=1) return INVALID_PARAMETER;
    zero(out,sizeof(*out));DWORD result=XInputGetCapabilities(index,flags,&out->capabilities);
    if (!result) {out->vendor=0x045e;out->product=0x028e;out->version=0x0114;}
    return result;
}
DWORD WINAPI XInputSetState(DWORD index,struct vibration *value) {
    if (!value) return INVALID_PARAMETER;
    struct zg_state state;return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
DWORD WINAPI XInputGetBatteryInformation(DWORD index,BYTE type,BYTE *out) {
    if (!out || type>1) return INVALID_PARAMETER;
    out[0]=out[1]=0;struct zg_state state;
    if (index || !snapshot(&state)) return DEVICE_NOT_CONNECTED;
    if (type) return NOT_SUPPORTED; /* no headset */
    out[0]=1;out[1]=3;return SUCCESS; /* wired, full */
}
DWORD WINAPI XInputGetKeystroke(DWORD index,DWORD reserved,void *out) {
    if (!out || reserved) return INVALID_PARAMETER;
    zero(out,8);struct zg_state state;
    return (index && index!=255) || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
DWORD WINAPI XInputGetDSoundAudioDeviceGuids(DWORD index,void *render,void *capture) {
    if (!render || !capture) return INVALID_PARAMETER;
    zero(render,16);zero(capture,16);struct zg_state state;
    return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
DWORD WINAPI XInputGetAudioDeviceIds(DWORD index,uint16_t *render,DWORD *render_count,uint16_t *capture,DWORD *capture_count) {
    if (!render_count || !capture_count) return INVALID_PARAMETER;
    if (render && *render_count) *render=0;
    if (capture && *capture_count) *capture=0;
    *render_count=*capture_count=0;struct zg_state state;
    return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
/* Legacy guide/power ordinals are explicit unsupported operations, not success
 * stubs; the Guide bit itself is available through ordinal 100 GetStateEx. */
DWORD WINAPI XInputWaitForGuideButton(DWORD index,DWORD flags,void *listen) {
    (void)flags;(void)listen;struct zg_state state;
    return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
DWORD WINAPI XInputCancelGuideButtonWait(DWORD index) {
    struct zg_state state;return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
DWORD WINAPI XInputPowerOffController(DWORD index) {
    struct zg_state state;return index || !snapshot(&state)?DEVICE_NOT_CONNECTED:NOT_SUPPORTED;
}
