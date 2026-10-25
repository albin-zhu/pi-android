/* SPDX-License-Identifier: GPL-3.0-only
 * Opt-in glibc evdev/joydev adapter. This is not a kernel device: preload the
 * library and open ZG_EVENT_PATH/ZG_JS_PATH. SDL discovers the evdev path through
 * SDL_JOYSTICK_DEVICE. Real devices and all other paths are left untouched.
 * timerfds provide real poll/select/epoll readiness without a producer thread;
 * no uinput, root, udev replacement, keyboard emulation or force feedback.
 */
#define _GNU_SOURCE
#include "state.h"
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <poll.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#define MAX_OPEN 128
#define MAX_EVENTS 24
static const unsigned short keys[11] = {
    BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_TL, BTN_TR,
    BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR
};
static const unsigned short masks[11] = {0x1000,0x2000,0x4000,0x8000,0x100,0x200,0x20,0x10,0x400,0x40,0x80};
static const unsigned char abs_codes[8] = {ABS_X,ABS_Y,ABS_Z,ABS_RX,ABS_RY,ABS_RZ,ABS_HAT0X,ABS_HAT0Y};
static const unsigned char axis_index[8] = {0,1,4,2,3,5,6,7};
struct device {
    unsigned refs, pending, next;
    int js, flags, initialized, clock;
    uint64_t generation;
    int seen_generation;
    struct zg_state previous;
    union {struct input_event ev[MAX_EVENTS]; struct js_event js[MAX_EVENTS];} queue;
};
static struct device devices[MAX_OPEN];
static struct {int fd; struct device *device;} handles[MAX_OPEN];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static pthread_once_t libc_once = PTHREAD_ONCE_INIT;
static ssize_t (*libc_read)(int,void *,size_t);
static int (*libc_openat)(int,const char *,int,...);
static int (*libc_close)(int);
static int (*libc_fcntl)(int,int,...);
static int libc_ready;
static _Thread_local int resolving_libc;
static void resolve_libc(void) {
    resolving_libc=1;
    libc_read=(ssize_t (*)(int,void *,size_t))dlsym(RTLD_NEXT,"read");
    libc_openat=(int (*)(int,const char *,int,...))dlsym(RTLD_NEXT,"openat");
    libc_close=(int (*)(int))dlsym(RTLD_NEXT,"close");
    libc_fcntl=(int (*)(int,int,...))dlsym(RTLD_NEXT,"fcntl");
    resolving_libc=0;
    __atomic_store_n(&libc_ready,1,__ATOMIC_RELEASE);
}
static void prepare_libc(void) {
    if (__atomic_load_n(&libc_ready,__ATOMIC_ACQUIRE)) return;
    int oldstate;
    pthread_setcancelstate(PTHREAD_CANCEL_DISABLE,&oldstate);
    pthread_once(&libc_once,resolve_libc);
    pthread_setcancelstate(oldstate,NULL);
}
/* Forward ordinary operations outside the device mutex: libc owns their
 * cancellation points and blocking file-lock/FIFO behavior. Only loader
 * recursion and internal controller descriptors use raw syscalls. */
static ssize_t ordinary_read(int fd,void *buffer,size_t count) {
    if (resolving_libc) return syscall(SYS_read,fd,buffer,count);
    prepare_libc();
    if (!libc_read) {errno=ENOSYS;return -1;}
    return libc_read(fd,buffer,count);
}
static int ordinary_openat(int dirfd,const char *path,int flags,mode_t mode) {
    if (resolving_libc) return (int)syscall(SYS_openat,dirfd,path,flags,mode);
    prepare_libc();
    if (!libc_openat) {errno=ENOSYS;return -1;}
    return libc_openat(dirfd,path,flags,mode);
}
static int ordinary_close(int fd) {
    if (resolving_libc) return (int)syscall(SYS_close,fd);
    prepare_libc();
    if (!libc_close) {errno=ENOSYS;return -1;}
    return libc_close(fd);
}
static int ordinary_fcntl(int fd,int command,unsigned long arg) {
    if (resolving_libc) return (int)syscall(SYS_fcntl,fd,command,arg);
    prepare_libc();
    if (!libc_fcntl) {errno=ENOSYS;return -1;}
    return libc_fcntl(fd,command,arg);
}

static int virtual_path(const char *path) {
    const char *enabled=getenv("ZFOLD_GAMEPAD");
    if (enabled && !strcmp(enabled,"0")) return 0;
    if (!strcmp(path,ZG_EVENT_PATH)) return 1;
    if (!strcmp(path,ZG_JS_PATH)) return 2;
    return 0;
}
static struct device *lookup(int fd) {
    for (unsigned i=0;i<MAX_OPEN;++i) if (handles[i].device && handles[i].fd==fd) return handles[i].device;
    return NULL;
}
static int attach(int fd, struct device *d) {
    for (unsigned i=0;i<MAX_OPEN;++i) if (!handles[i].device) {
        handles[i].fd=fd; handles[i].device=d; ++d->refs; return 0;
    }
    errno=EMFILE; return -1;
}
static void detach(int fd) {
    for (unsigned i=0;i<MAX_OPEN;++i) if (handles[i].device && handles[i].fd==fd) {
        --handles[i].device->refs; handles[i].device=NULL; return;
    }
}
static void snapshot(struct zg_state *s) {
    memset(s,0,sizeof(*s));
    struct timespec now;
    int fd=(int)syscall(SYS_openat,AT_FDCWD,ZG_STATE_PATH,O_RDONLY|O_CLOEXEC|O_NOFOLLOW,0);
    if (fd<0) return;
    unsigned char bytes[64],before[4],after[4];
    for (unsigned retry=0;retry<3;++retry) {
        if (syscall(SYS_pread64,fd,before,4,8)!=4 || (zg_u32(before)&1)) continue;
        if (syscall(SYS_pread64,fd,bytes,64,0)!=64 || syscall(SYS_pread64,fd,after,4,8)!=4) continue;
        if (clock_gettime(CLOCK_BOOTTIME,&now)) break;
        if (zg_decode(bytes,zg_u32(before),zg_u32(after),(uint64_t)now.tv_sec*1000+now.tv_nsec/1000000,s)) {
            if (syscall(SYS_kill,(int)s->pid,0)<0 && errno==ESRCH) zg_neutral(s);
            break;
        }
        zg_neutral(s);
    }
    syscall(SYS_close,fd);
}
static int device_open(int kind,int flags) {
    if ((flags&O_ACCMODE)==O_WRONLY || (flags&(O_CREAT|O_TRUNC|O_PATH))) {errno=EINVAL;return -1;}
    int fd=timerfd_create(CLOCK_MONOTONIC,TFD_NONBLOCK|((flags&O_CLOEXEC)?TFD_CLOEXEC:0));
    if (fd<0) return -1;
    struct itimerspec tick={{0,16000000},{0,1}};
    if (timerfd_settime(fd,0,&tick,NULL)) {int error=errno;syscall(SYS_close,fd);errno=error;return -1;}
    pthread_mutex_lock(&lock);
    struct device *d=NULL;
    for (unsigned i=0;i<MAX_OPEN;++i) if (!devices[i].refs) {d=&devices[i];break;}
    if (d) {
        memset(d,0,sizeof(*d)); d->js=kind==2; d->flags=flags; d->clock=CLOCK_REALTIME;
        if (attach(fd,d)) d=NULL;
    }
    pthread_mutex_unlock(&lock);
    if (!d) {syscall(SYS_close,fd);errno=EMFILE;return -1;}
    return fd;
}
static int open_impl(int dirfd,const char *path,int flags,mode_t mode) {
    int kind=virtual_path(path);
    if (kind) {pthread_testcancel();return device_open(kind,flags);}
    return ordinary_openat(dirfd,path,flags,mode);
}
#define OPEN_MODE(flags,ap,mode) do { \
    if (((flags)&O_CREAT) || (((flags)&O_TMPFILE)==O_TMPFILE)) (mode)=(mode_t)va_arg(ap,int); \
} while (0)
int open(const char *path,int flags,...) {
    mode_t mode=0;va_list ap;va_start(ap,flags);OPEN_MODE(flags,ap,mode);va_end(ap);
    return open_impl(AT_FDCWD,path,flags,mode);
}
int open64(const char *path,int flags,...) {
    mode_t mode=0;va_list ap;va_start(ap,flags);OPEN_MODE(flags,ap,mode);va_end(ap);
    return open_impl(AT_FDCWD,path,flags,mode);
}
int openat(int dirfd,const char *path,int flags,...) {
    mode_t mode=0;va_list ap;va_start(ap,flags);OPEN_MODE(flags,ap,mode);va_end(ap);
    return open_impl(dirfd,path,flags,mode);
}
int openat64(int dirfd,const char *path,int flags,...) {
    mode_t mode=0;va_list ap;va_start(ap,flags);OPEN_MODE(flags,ap,mode);va_end(ap);
    return open_impl(dirfd,path,flags,mode);
}
int __open_2(const char *path,int flags) {return open_impl(AT_FDCWD,path,flags,0);}
int __open64_2(const char *path,int flags) {return open_impl(AT_FDCWD,path,flags,0);}
int __openat_2(int dirfd,const char *path,int flags) {return open_impl(dirfd,path,flags,0);}
int __openat64_2(int dirfd,const char *path,int flags) {return open_impl(dirfd,path,flags,0);}
int close(int fd) {
    pthread_testcancel();
    pthread_mutex_lock(&lock);
    if (!lookup(fd)) {pthread_mutex_unlock(&lock);return ordinary_close(fd);}
    detach(fd);int result=(int)syscall(SYS_close,fd);pthread_mutex_unlock(&lock);return result;
}
static void event(struct device *d,unsigned type,unsigned code,int value,unsigned js_number,struct timespec *time,int initial) {
    unsigned n=d->pending++;
    if (d->js) {
        struct js_event *e=&d->queue.js[n];
        e->time=(uint32_t)((uint64_t)time->tv_sec*1000+time->tv_nsec/1000000);
        e->value=(int16_t)value;e->type=(type==EV_KEY?JS_EVENT_BUTTON:JS_EVENT_AXIS)|(initial?JS_EVENT_INIT:0);
        e->number=(unsigned char)js_number;
    } else {
        struct input_event *e=&d->queue.ev[n];
        e->time.tv_sec=time->tv_sec;e->time.tv_usec=time->tv_nsec/1000;
        e->type=(unsigned short)type;e->code=(unsigned short)code;e->value=value;
    }
}
static void sample(struct device *d) {
    struct zg_state state; snapshot(&state);
    if (state.connected) {
        if (d->seen_generation && state.generation!=d->generation) {
            d->generation=state.generation;zg_neutral(&state);
        } else {d->generation=state.generation;d->seen_generation=1;}
    }
    struct timespec time;
    clock_gettime(d->js?CLOCK_MONOTONIC:d->clock,&time);
    int initial=!d->initialized;
    for (unsigned i=0;i<11;++i) if (initial || ((state.buttons^d->previous.buttons)&masks[i]))
        event(d,EV_KEY,keys[i],!!(state.buttons&masks[i]),i,&time,initial);
    for (unsigned i=0;i<8;++i) {
        unsigned a=axis_index[i];
        if (initial || state.axes[a]!=d->previous.axes[a]) {
            int value=state.axes[a];
            /* joydev normalizes ALL axes to signed full scale, unlike evdev. */
            if (d->js && a>=4 && a<6) value=value*2-32767;
            else if (d->js && a>=6) value*=32767;
            event(d,EV_ABS,abs_codes[i],value,i,&time,initial);
        }
    }
    if (!d->js && d->pending) event(d,EV_SYN,SYN_REPORT,0,0,&time,0);
    d->previous=state;d->initialized=1;
}
ssize_t read(int fd,void *buffer,size_t count) {
    for (;;) {
        /* Deferred cancellation happens only outside the device mutex. */
        pthread_testcancel();
        pthread_mutex_lock(&lock);
        struct device *d=lookup(fd);
        if (!d) {pthread_mutex_unlock(&lock);return ordinary_read(fd,buffer,count);}
        size_t size=d->js?sizeof(struct js_event):sizeof(struct input_event);
        if (count<size) {pthread_mutex_unlock(&lock);errno=EINVAL;return -1;}
        if (!d->pending) {
            /* Leave the expiration unread while queued events remain. This
             * keeps poll/select/epoll readable even for one-event reads,
             * without rearming a timer or spinning between samples. ppoll's
             * raw syscall is non-cancelling while the mutex is held. */
            struct pollfd ready={fd,POLLIN,0};
            struct timespec zero={0,0};
            int result=(int)syscall(SYS_ppoll,&ready,1,&zero,NULL,0);
            if (result<0) {pthread_mutex_unlock(&lock);return -1;}
            if (ready.revents&(POLLNVAL|POLLERR|POLLHUP)) {
                pthread_mutex_unlock(&lock);errno=ENODEV;return -1;
            }
            if (ready.revents&POLLIN) {
                sample(d);
                if (!d->pending) {
                    uint64_t ticks;
                    if (syscall(SYS_read,fd,&ticks,sizeof(ticks))<0) {
                        pthread_mutex_unlock(&lock);return -1;
                    }
                }
            }
        }
        if (d->pending) {
            size_t n=count/size;if (n>d->pending) n=d->pending;
            if (n==d->pending) {
                uint64_t ticks;
                if (syscall(SYS_read,fd,&ticks,sizeof(ticks))<0) {
                    pthread_mutex_unlock(&lock);return -1;
                }
            }
            memcpy(buffer,(unsigned char *)&d->queue+d->next*size,n*size);
            d->next+=(unsigned)n;d->pending-=(unsigned)n;if (!d->pending) d->next=0;
            pthread_mutex_unlock(&lock);return (ssize_t)(n*size);
        }
        int nonblock=d->flags&O_NONBLOCK;
        pthread_mutex_unlock(&lock);
        if (nonblock) {errno=EAGAIN;return -1;}
        struct pollfd wait={fd,POLLIN,0};
        if (poll(&wait,1,-1)<0) return -1;
        if (wait.revents&(POLLNVAL|POLLERR|POLLHUP)) {errno=ENODEV;return -1;}
    }
}
ssize_t __read_chk(int fd,void *buffer,size_t count,size_t available) {
    if (count>available) {errno=EFAULT;return -1;}return read(fd,buffer,count);
}
static void bit(void *buffer,size_t size,unsigned number) {
    if (number/8<size) ((unsigned char *)buffer)[number/8]|=(unsigned char)(1u<<(number%8));
}
static int text_result(void *arg,size_t size,const char *text) {
    size_t length=strlen(text)+1;if (length>size) length=size;
    memcpy(arg,text,length);return (int)length;
}
static int input_ioctl(struct device *d,unsigned long request,void *arg) {
    unsigned nr=_IOC_NR(request);size_t size=_IOC_SIZE(request);
    if (request==EVIOCSCLOCKID && !d->js) {
        int clock=*(int *)arg;
        if (clock!=CLOCK_REALTIME && clock!=CLOCK_MONOTONIC && clock!=CLOCK_BOOTTIME) {errno=EINVAL;return -1;}
        d->clock=clock;return 0;
    }
    if (!(_IOC_DIR(request)&_IOC_READ)) {errno=EOPNOTSUPP;return -1;}
    struct zg_state state;snapshot(&state);
    if (d->js && _IOC_TYPE(request)=='j') {
        switch (nr) {
        case 0x01: *(uint32_t *)arg=JS_VERSION;return 0;
        case 0x11: *(unsigned char *)arg=8;return 0;
        case 0x12: *(unsigned char *)arg=11;return 0;
        case 0x13: return text_result(arg,size,ZG_NAME);
        case 0x32: memset(arg,0,size);memcpy(arg,abs_codes,size<8?size:8);return 0;
        case 0x34: memset(arg,0,size);memcpy(arg,keys,size<sizeof(keys)?size:sizeof(keys));return 0;
        default: errno=ENOTTY;return -1;
        }
    }
    if (d->js || _IOC_TYPE(request)!='E') {errno=ENOTTY;return -1;}
    if (request==EVIOCGVERSION) {*(int *)arg=0x010001;return 0;}
    if (request==EVIOCGID) {struct input_id id={BUS_USB,0x045e,0x028e,0x0114};memcpy(arg,&id,sizeof(id));return 0;}
    if (nr==0x06) return text_result(arg,size,ZG_NAME);
    if (nr==0x07) return text_result(arg,size,"zfold/input0");
    if (nr==0x08) return text_result(arg,size,"zfold-xbox-0");
    if (nr==0x09 || nr==0x18 || (nr>=0x20 && nr<=0x20+EV_MAX)) {
        memset(arg,0,size);
        if (nr==0x18) {for (unsigned i=0;i<11;++i) if (state.buttons&masks[i]) bit(arg,size,keys[i]);}
        else if (nr==0x20) {bit(arg,size,EV_SYN);bit(arg,size,EV_KEY);bit(arg,size,EV_ABS);}
        else if (nr==0x20+EV_KEY) {for (unsigned i=0;i<11;++i) bit(arg,size,keys[i]);}
        else if (nr==0x20+EV_ABS) {for (unsigned i=0;i<8;++i) bit(arg,size,abs_codes[i]);}
        return (int)size;
    }
    if (nr>=0x40 && nr<=0x40+ABS_MAX && size==sizeof(struct input_absinfo)) {
        for (unsigned i=0;i<8;++i) if (abs_codes[i]==nr-0x40) {
            unsigned a=axis_index[i];struct input_absinfo info={0};
            info.value=state.axes[a];info.minimum=a<4?-32768:(a<6?0:-1);info.maximum=a<6?32767:1;
            memcpy(arg,&info,sizeof(info));return 0;
        }
        errno=EINVAL;return -1;
    }
    errno=ENOTTY;return -1;
}
int ioctl(int fd,unsigned long request,...) {
    va_list ap;va_start(ap,request);void *arg=va_arg(ap,void *);va_end(ap);
    pthread_mutex_lock(&lock);struct device *d=lookup(fd);
    int result=d?input_ioctl(d,request,arg):(int)syscall(SYS_ioctl,fd,request,arg);
    pthread_mutex_unlock(&lock);return result;
}
static int fake_stat(struct stat *s,int kind) {
    memset(s,0,sizeof(*s));s->st_mode=S_IFCHR|0600;s->st_nlink=1;s->st_uid=getuid();s->st_gid=getgid();
    /* Stay outside the kernel's evdev/joydev minor ranges: SDL deduplicates by
     * dev_t and must not confuse this path with a real event31 or js31. */
    s->st_rdev=makedev(13,0x5a470u+(unsigned)kind);s->st_ino=0x5a475000u+(unsigned)kind;s->st_blksize=4096;return 0;
}
int stat(const char *path,struct stat *s) {int kind=virtual_path(path);return kind?fake_stat(s,kind):(int)syscall(SYS_newfstatat,AT_FDCWD,path,s,0);}
int lstat(const char *path,struct stat *s) {int kind=virtual_path(path);return kind?fake_stat(s,kind):(int)syscall(SYS_newfstatat,AT_FDCWD,path,s,AT_SYMLINK_NOFOLLOW);}
int fstat(int fd,struct stat *s) {
    pthread_mutex_lock(&lock);struct device *d=lookup(fd);int result=d?fake_stat(s,d->js?2:1):(int)syscall(SYS_fstat,fd,s);pthread_mutex_unlock(&lock);return result;
}
int fstatat(int fd,const char *path,struct stat *s,int flags) {
    int kind=virtual_path(path);return kind?fake_stat(s,kind):(int)syscall(SYS_newfstatat,fd,path,s,flags);
}
/* The packaged ABI is 64-bit glibc; stat and stat64 have identical layouts. */
_Static_assert(sizeof(struct stat)==sizeof(struct stat64),"64-bit glibc stat ABI required");
int stat64(const char *p,struct stat64 *s) {return stat(p,(struct stat *)s);}
int lstat64(const char *p,struct stat64 *s) {return lstat(p,(struct stat *)s);}
int fstat64(int fd,struct stat64 *s) {return fstat(fd,(struct stat *)s);}
int fstatat64(int fd,const char *p,struct stat64 *s,int flags) {return fstatat(fd,p,(struct stat *)s,flags);}
int __xstat(int version,const char *p,struct stat *s) {(void)version;return stat(p,s);}
int __lxstat(int version,const char *p,struct stat *s) {(void)version;return lstat(p,s);}
int __fxstat(int version,int fd,struct stat *s) {(void)version;return fstat(fd,s);}
int __xstat64(int version,const char *p,struct stat64 *s) {(void)version;return stat64(p,s);}
int __lxstat64(int version,const char *p,struct stat64 *s) {(void)version;return lstat64(p,s);}
int __fxstat64(int version,int fd,struct stat64 *s) {(void)version;return fstat64(fd,s);}
int access(const char *path,int mode) {
    if (virtual_path(path)) {if (mode&X_OK) {errno=EACCES;return -1;}return 0;}
    return (int)syscall(SYS_faccessat,AT_FDCWD,path,mode);
}
static int duplicate(int oldfd,int newfd,int flags,int specific) {
    pthread_mutex_lock(&lock);struct device *d=lookup(oldfd);
    if (oldfd==newfd && specific) {
        pthread_mutex_unlock(&lock);if (flags) {errno=EINVAL;return -1;}return fcntl(oldfd,F_GETFD)<0?-1:oldfd;
    }
    int result=specific?(int)syscall(SYS_dup3,oldfd,newfd,flags):(int)syscall(SYS_dup,oldfd);
    if (result>=0) {detach(result);if (d && attach(result,d)) {syscall(SYS_close,result);result=-1;}}
    pthread_mutex_unlock(&lock);return result;
}
int dup(int fd) {return duplicate(fd,-1,0,0);}
int dup2(int fd,int other) {return duplicate(fd,other,0,1);}
int dup3(int fd,int other,int flags) {if (fd==other) {errno=EINVAL;return -1;}return duplicate(fd,other,flags,1);}
int fcntl(int fd,int command,...) {
    unsigned long arg=0;va_list ap;va_start(ap,command);
    switch (command) {
    case F_GETFD:case F_GETFL:case F_GETOWN:case F_GETSIG:case F_GETLEASE:case F_GETPIPE_SZ:case F_GET_SEALS:break;
    case F_DUPFD:case F_DUPFD_CLOEXEC:case F_SETFD:case F_SETFL:case F_SETOWN:case F_SETSIG:case F_SETLEASE:case F_SETPIPE_SZ:case F_ADD_SEALS:
        arg=(unsigned long)va_arg(ap,int);break;
    default:arg=(unsigned long)va_arg(ap,void *);break;
    }
    va_end(ap);pthread_mutex_lock(&lock);struct device *d=lookup(fd);int result;
    if (!d) {pthread_mutex_unlock(&lock);return ordinary_fcntl(fd,command,arg);}
    if (command==F_GETFL) result=d->flags&~O_CLOEXEC;
    else if (command==F_SETFL) {d->flags=(d->flags&~O_NONBLOCK)|((int)arg&O_NONBLOCK);result=0;}
    else {
        result=(int)syscall(SYS_fcntl,fd,command,arg);
        if (result>=0 && (command==F_DUPFD || command==F_DUPFD_CLOEXEC) && attach(result,d)) {syscall(SYS_close,result);result=-1;}
    }
    pthread_mutex_unlock(&lock);return result;
}
extern __typeof(fcntl) fcntl64 __attribute__((alias("fcntl")));
/* No worker thread exists, but a multithreaded caller can fork during read/ioctl. */
static void before_fork(void) {pthread_mutex_lock(&lock);}
static void after_fork(void) {pthread_mutex_unlock(&lock);}
__attribute__((constructor)) static void initialize(void) {pthread_atfork(before_fork,after_fork,after_fork);}
