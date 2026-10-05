/* Small APK-packaged client for the app-owned package manager. No apt subprocess. */
#include <arpa/inet.h>
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static volatile sig_atomic_t connection = -1;
static void cancel(int sig) {
    if (connection >= 0) close(connection);
    const char message[] = "\npkg: 已请求取消；已经开始替换的包会先完成收尾。\n";
    write(STDERR_FILENO, message, sizeof(message) - 1);
    _exit(128 + sig);
}
static int transfer(int fd, void *data, size_t size, int writing) {
    char *cursor = data;
    while (size) {
        ssize_t count = writing ? write(fd, cursor, size) : read(fd, cursor, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        cursor += count; size -= (size_t)count;
    }
    return 0;
}
static int number(int fd, uint32_t value) {
    value = htonl(value);
    return transfer(fd, &value, sizeof(value), 1);
}
int main(int argc, char **argv) {
    const char *endpoint = getenv("ZFOLD_PKG_SOCKET");
    struct sockaddr_un address = {.sun_family = AF_UNIX};
    if (!endpoint || !*endpoint || strlen(endpoint) >= sizeof(address.sun_path) - 1) {
        fprintf(stderr, "pkg: 请在橙橙的终端中运行，或重新打开终端以加载运行环境。\n");
        return 69;
    }
    if (argc > 65) { fprintf(stderr, "pkg: 参数过多\n"); return 2; }
    size_t total = 0;
    for (int i = 1; i < argc; i++) {
        size_t length = strlen(argv[i]); total += length;
        if (length > 4096 || total > 16384) { fprintf(stderr, "pkg: 参数过长\n"); return 2; }
    }
    signal(SIGPIPE, SIG_IGN);
    signal(SIGINT, cancel); signal(SIGTERM, cancel); signal(SIGHUP, cancel);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) { perror("pkg: socket"); return 69; }
    connection = fd;
    memcpy(address.sun_path + 1, endpoint, strlen(endpoint));
    if (connect(fd, (struct sockaddr *)&address, offsetof(struct sockaddr_un, sun_path) + 1 + strlen(endpoint)) < 0) {
        fprintf(stderr, "pkg: 本机包管理服务不可用，请重新打开橙橙。\n"); close(fd); return 69;
    }
    struct ucred peer; socklen_t peer_size = sizeof(peer);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &peer_size) < 0 || peer.uid != getuid()) {
        fprintf(stderr, "pkg: 包管理服务身份不匹配\n"); close(fd); return 77;
    }
    if (number(fd, 0x5a504b31) || number(fd, (uint32_t)(argc - 1))) goto disconnected;
    for (int i = 1; i < argc; i++) {
        size_t length = strlen(argv[i]);
        if (number(fd, (uint32_t)length) || transfer(fd, argv[i], length, 1)) goto disconnected;
    }
    for (;;) {
        unsigned char kind; uint32_t encoded;
        if (transfer(fd, &kind, 1, 0) || transfer(fd, &encoded, 4, 0)) goto disconnected;
        uint32_t length = ntohl(encoded);
        if (kind == 3 && length == 4) {
            if (transfer(fd, &encoded, 4, 0)) goto disconnected;
            close(fd); return (int)(ntohl(encoded) & 255);
        }
        if (kind == 4 && length == 0) continue;
        if ((kind != 1 && kind != 2) || length > 65536) goto disconnected;
        char buffer[4096];
        while (length) {
            size_t chunk = length < sizeof(buffer) ? length : sizeof(buffer);
            if (transfer(fd, buffer, chunk, 0) || transfer(kind == 1 ? STDOUT_FILENO : STDERR_FILENO, buffer, chunk, 1)) goto disconnected;
            length -= chunk;
        }
    }
disconnected:
    fprintf(stderr, "pkg: 与包管理服务的连接中断；请用 pkg list 检查已完成的安装。\n");
    close(fd); return 74;
}
