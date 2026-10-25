/* SPDX-License-Identifier: GPL-3.0-only
 * Ordinary FIFO opens and blocking file locks must retain libc cancellation
 * under LD_PRELOAD; they must never hold the virtual-controller mutex.
 */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

struct operation {const char *path;int fd;pthread_barrier_t barrier;};
static void *fifo_open(void *arg) {
    struct operation *op=arg;
    pthread_barrier_wait(&op->barrier);
    int fd=open(op->path,O_RDONLY);
    if (fd>=0) close(fd);
    return NULL;
}
static void *file_lock(void *arg) {
    struct operation *op=arg;
    struct flock held={.l_type=F_WRLCK,.l_whence=SEEK_SET};
    pthread_barrier_wait(&op->barrier);
    int result=fcntl(op->fd,F_SETLKW,&held);
    (void)result;
    return NULL;
}
static int cancel_operation(struct operation *op,void *(*operation)(void *)) {
    assert(pthread_barrier_init(&op->barrier,NULL,2)==0);
    pthread_t thread;
    assert(pthread_create(&thread,NULL,operation,op)==0);
    pthread_barrier_wait(&op->barrier);
    struct timespec settle={0,20000000};
    assert(nanosleep(&settle,NULL)==0);
    assert(pthread_cancel(thread)==0);
    struct timespec deadline;
    assert(clock_gettime(CLOCK_REALTIME,&deadline)==0);
    deadline.tv_sec+=2;
    void *result=NULL;
    int error=pthread_timedjoin_np(thread,&result,&deadline);
    if (error || result!=PTHREAD_CANCELED) {
        fprintf(stderr,"ordinary operation was not cancelled (join=%d)\n",error);
        return 0;
    }
    assert(pthread_barrier_destroy(&op->barrier)==0);
    return 1;
}
int main(int argc,char **argv) {
    assert(argc==2);
    alarm(10);
    char directory[]="/tmp/zfold-input-files-XXXXXX",path[128];
    assert(mkdtemp(directory));
    assert(snprintf(path,sizeof(path),"%s/file",directory)>0);
    struct operation op={.path=path,.fd=-1};
    int success;
    if (argv[1][0]=='o') {
        assert(mkfifo(path,0600)==0);
        success=cancel_operation(&op,fifo_open);
    } else {
        op.fd=open(path,O_CREAT|O_RDWR,0600);
        assert(op.fd>=0);
        int ready[2];assert(pipe(ready)==0);
        pid_t child=fork();assert(child>=0);
        if (!child) {
            close(ready[0]);
            struct flock held={.l_type=F_WRLCK,.l_whence=SEEK_SET};
            if (fcntl(op.fd,F_SETLK,&held)<0 || write(ready[1],"1",1)!=1) _exit(2);
            for (;;) pause();
        }
        close(ready[1]);char value;
        assert(read(ready[0],&value,1)==1 && value=='1');close(ready[0]);
        success=cancel_operation(&op,file_lock);
        assert(kill(child,SIGTERM)==0);
        assert(waitpid(child,NULL,0)==child);
        if (success) assert(close(op.fd)==0);
    }
    assert(unlink(path)==0 && rmdir(directory)==0);
    if (!success) _exit(1); /* stop any blocked regression thread without using its stack again */
    puts("ordinary file-operation cancellation passed");
    return 0;
}
