/* SPDX-License-Identifier: GPL-3.0-only
 * Real PE consumer smoke tool; builds for both Win32 and Win64 without a CRT.
 * Prints XInput capabilities and changed states for 30 seconds. Exit 1 means
 * DLL/export/ordinal ABI failure, not merely an unattached controller.
 */
#include <stdint.h>
#include <stddef.h>
#ifdef _M_IX86
#define WINAPI __stdcall
#else
#define WINAPI
#endif
#define IMPORT __declspec(dllimport)
IMPORT void *WINAPI LoadLibraryA(const char *);
IMPORT void *WINAPI GetProcAddress(void *,const char *);
IMPORT void *WINAPI GetStdHandle(uint32_t);
IMPORT int WINAPI WriteFile(void *,const void *,uint32_t,uint32_t *,void *);
IMPORT void WINAPI Sleep(uint32_t);
IMPORT __declspec(noreturn) void WINAPI ExitProcess(uint32_t);
struct gamepad {uint16_t buttons;uint8_t lt,rt;int16_t lx,ly,rx,ry;};
struct state {uint32_t packet;struct gamepad gamepad;};
struct caps {uint8_t type,subtype;uint16_t flags;struct gamepad gamepad;uint16_t motors[2];};
struct caps_ex {struct caps caps;uint16_t vendor,product,version;uint32_t unknown;};
typedef uint32_t (WINAPI *get_state)(uint32_t,struct state *);
typedef uint32_t (WINAPI *get_caps)(uint32_t,uint32_t,struct caps *);
typedef uint32_t (WINAPI *get_caps_ex)(uint32_t,uint32_t,uint32_t,struct caps_ex *);
static void text(const char *s) {
    uint32_t length=0,written=0;while(s[length]) ++length;
    WriteFile(GetStdHandle((uint32_t)-11),s,length,&written,NULL);
}
static void hex(uint32_t value) {
    char digits[9];digits[8]=0;
    for(int i=7;i>=0;--i) {digits[i]="0123456789abcdef"[value&15];value>>=4;}
    text(digits);
}
static const char *names[5]={"xinput1_1.dll","xinput1_2.dll","xinput1_3.dll","xinput1_4.dll","xinput9_1_0.dll"};
void mainCRTStartup(void) {
    get_state states[5],extended[4];uint32_t previous_packet[5]={0},previous_status[5]={0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu,0xffffffffu};
    for(unsigned i=0;i<5;++i) {
        void *module=LoadLibraryA(names[i]);
        states[i]=(get_state)GetProcAddress(module,"XInputGetState");
        get_caps capabilities=(get_caps)GetProcAddress(module,"XInputGetCapabilities");
        if(!module || !states[i] || !capabilities) {text("FAIL named XInput exports: ");text(names[i]);text("\n");ExitProcess(1);}
        if(i<4) {
            extended[i]=(get_state)GetProcAddress(module,(const char *)(uintptr_t)100);
            if(!extended[i] || (get_state)GetProcAddress(module,(const char *)(uintptr_t)2)!=states[i]) {
                text("FAIL XInput ordinals: ");text(names[i]);text("\n");ExitProcess(1);
            }
        }
        struct caps caps;uint32_t result=capabilities(0,0,&caps);
        text(names[i]);text(" caps status=");hex(result);text(" type/subtype=");hex((uint32_t)caps.type<<8|caps.subtype);text(" flags=");hex(caps.flags);text("\n");
        if(i==3) {
            get_caps_ex extra=(get_caps_ex)GetProcAddress(module,(const char *)(uintptr_t)108);
            if(!extra) {text("FAIL capabilities ordinal108\n");ExitProcess(1);}
            struct caps_ex extended_caps;result=extra(1,0,0,&extended_caps);
            text("ordinal108 status=");hex(result);text(" vendor/product=");hex((uint32_t)extended_caps.vendor<<16|extended_caps.product);text("\n");
        }
    }
    for(unsigned tick=0;tick<300;++tick) {
        for(unsigned i=0;i<5;++i) {
            struct state state;uint32_t result=states[i](0,&state);
            if(result!=previous_status[i] || state.packet!=previous_packet[i]) {
                text(names[i]);text(" status=");hex(result);text(" packet=");hex(state.packet);
                text(" buttons=");hex(state.gamepad.buttons);
                text(" LT/RT=");hex((uint32_t)state.gamepad.lt<<8|state.gamepad.rt);
                text(" LX/LY/RX/RY=");hex((uint16_t)state.gamepad.lx);text("/");hex((uint16_t)state.gamepad.ly);
                text("/");hex((uint16_t)state.gamepad.rx);text("/");hex((uint16_t)state.gamepad.ry);
                if(i<4) {struct state ex;uint32_t status=extended[i](0,&ex);text(" ordinal100=");hex(status);text("/");hex(ex.gamepad.buttons);}
                text("\n");previous_status[i]=result;previous_packet[i]=state.packet;
            }
        }
        Sleep(100);
    }
    ExitProcess(0);
}
