/* SPDX-License-Identifier: GPL-3.0-only
 * Run with LD_PRELOAD pointing to the glibc input-controls adapter and
 * ZFOLD_GAMEPAD=1. No state writer or attached controller is required.
 */
#define _GNU_SOURCE
#include <assert.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/joystick.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/epoll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>
#include "../native/input-controls/state.h"

static void queued_readiness(const char *path,int js) {
    int fd=open(path,O_RDONLY|O_NONBLOCK);
    assert(fd>=0);
    if (!js) {
        static const unsigned sticks[]={ABS_X,ABS_Y,ABS_RX,ABS_RY};
        for (unsigned i=0;i<4;++i) {
            struct input_absinfo axis;
            assert(ioctl(fd,EVIOCGABS(sticks[i]),&axis)==0);
            assert(axis.minimum<0 && axis.maximum>0);
            /* SDL's full signed-range normalization must keep raw zero neutral. */
            int64_t neutral=-(int64_t)axis.minimum*65535/(axis.maximum-axis.minimum)-32768;
            assert(neutral==0);
        }
    }
    /* Isolate the queued-event readiness from the periodic sampling tick.
     * Without this, a slow test could accidentally pass on the next tick. */
    struct itimerspec tick={{60,0},{0,1}};
    assert(timerfd_settime(fd,0,&tick,NULL)==0);
    int ep=epoll_create1(EPOLL_CLOEXEC);
    assert(ep>=0);
    struct epoll_event interest={.events=EPOLLIN,.data.fd=fd}, ready;
    assert(epoll_ctl(ep,EPOLL_CTL_ADD,fd,&interest)==0);
    struct pollfd p={fd,POLLIN,0};
    assert(poll(&p,1,1000)==1 && (p.revents&POLLIN));
    union {struct input_event ev;struct js_event js;} event;
    size_t size=js?sizeof(event.js):sizeof(event.ev);
    unsigned total=js?19:20;
    for (unsigned i=0;i<total;++i) {
        p.revents=0;
        assert(poll(&p,1,0)==1 && (p.revents&POLLIN));
        assert(epoll_wait(ep,&ready,1,0)==1 && (ready.events&EPOLLIN));
        assert(read(fd,&event,size)==(ssize_t)size);
        if (js) assert(event.js.type&JS_EVENT_INIT);
        else if (i==total-1) assert(event.ev.type==EV_SYN && event.ev.code==SYN_REPORT);
    }
    assert(poll(&p,1,0)==0);
    assert(epoll_wait(ep,&ready,1,0)==0);
    assert(close(ep)==0);
    assert(close(fd)==0);
}

struct reader {int fd;pthread_barrier_t barrier;};
static void *blocked_read(void *arg) {
    struct reader *r=arg;
    pthread_barrier_wait(&r->barrier);
    struct input_event event;
    ssize_t count=read(r->fd,&event,sizeof(event));
    fprintf(stderr,"blocking read unexpectedly returned %zd\n",count);
    return NULL;
}
static void cancel_reader(int fd) {
    struct reader r={.fd=fd};
    assert(pthread_barrier_init(&r.barrier,NULL,2)==0);
    pthread_t thread;
    assert(pthread_create(&thread,NULL,blocked_read,&r)==0);
    pthread_barrier_wait(&r.barrier);
    struct timespec settle={0,20000000};
    assert(nanosleep(&settle,NULL)==0);
    assert(pthread_cancel(thread)==0);
    struct timespec deadline;
    assert(clock_gettime(CLOCK_REALTIME,&deadline)==0);
    deadline.tv_sec+=2;
    void *result=NULL;
    assert(pthread_timedjoin_np(thread,&result,&deadline)==0);
    assert(result==PTHREAD_CANCELED);
    assert(pthread_barrier_destroy(&r.barrier)==0);
}
int main(void) {
    alarm(15);
    queued_readiness(ZG_EVENT_PATH,0);
    queued_readiness(ZG_JS_PATH,1);
    int pipes[2];
    assert(pipe(pipes)==0);
    cancel_reader(pipes[0]);
    assert(close(pipes[0])==0 && close(pipes[1])==0);
    int sockets[2];
    assert(socketpair(AF_UNIX,SOCK_STREAM,0,sockets)==0);
    cancel_reader(sockets[0]);
    assert(close(sockets[0])==0 && close(sockets[1])==0);
    int fd=open(ZG_EVENT_PATH,O_RDONLY);
    assert(fd>=0);
    struct itimerspec tick={{60,0},{60,0}};
    assert(timerfd_settime(fd,0,&tick,NULL)==0);
    cancel_reader(fd);
    /* close/open both need the mutex: a cancelled tracked reader must not
     * retain it. alarm bounds this assertion even if the lock is leaked. */
    assert(close(fd)==0);
    fd=open(ZG_EVENT_PATH,O_RDONLY|O_NONBLOCK);
    assert(fd>=0 && close(fd)==0);
    puts("input-controls readiness and read cancellation passed");
    return 0;
}
