/* The X11 server needs Unix sockets only. Apply before loading libXlorie:
 * upstream's reconnect-knock listener otherwise binds INADDR_ANY independently
 * of -nolisten tcp. TSYNC covers ART/Binder threads as well as future children.
 * No libc dependency: this also builds with a host clang targeting Android.
 */
#if !defined(__aarch64__)
#error "The packaged X11 runtime supports arm64 only"
#endif

typedef __UINT16_TYPE__ u16;
typedef __UINT32_TYPE__ u32;
struct filter { u16 code; unsigned char jt, jf; u32 k; };
struct program { u16 length; const struct filter *instructions; };

static long syscall5(long number, long a, long b, long c, long d, long e) {
    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4) : "memory", "cc");
    return x0;
}

__attribute__((visibility("default")))
int Java_com_termux_x11_X11Service_restrictNetwork(void *environment, void *type) {
    (void)environment;
    (void)type;
    static const struct filter instructions[] = {
        {0x20, 0, 0, 4},                 /* seccomp_data.arch */
        {0x15, 1, 0, 0xc00000b7},        /* AUDIT_ARCH_AARCH64 */
        {0x06, 0, 0, 0x80000000},        /* KILL_PROCESS */
        {0x20, 0, 0, 0},                 /* seccomp_data.nr */
        {0x15, 1, 0, 198},               /* socket */
        {0x15, 0, 3, 199},               /* socketpair */
        {0x20, 0, 0, 16},                /* args[0]: domain */
        {0x15, 2, 0, 2},                 /* AF_INET */
        {0x15, 1, 0, 10},                /* AF_INET6 */
        {0x06, 0, 0, 0x7fff0000},        /* ALLOW */
        {0x06, 0, 0, 0x00050001},        /* ERRNO(EPERM) */
    };
    const struct program policy = {sizeof(instructions) / sizeof(instructions[0]), instructions};
    long result = syscall5(167, 38, 1, 0, 0, 0); /* prctl(NO_NEW_PRIVS) */
    if (result < 0) return (int)result;
    result = syscall5(277, 1, 1, (long)&policy, 0, 0); /* seccomp(FILTER, TSYNC) */
    /* TSYNC returns a positive unsynchronizable thread ID on failure. */
    return result > 0 ? -16 : (int)result;
}
