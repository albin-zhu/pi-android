#include <jni.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <termios.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <string>
#include <mutex>
#include <map>
#include <memory>
#include <vector>
#include <algorithm>
#include <android/log.h>
#include <cstdlib>
#include <cstring>

namespace node { int Start(int argc, char** argv); }
static int command_pipe[2], event_pipe[2];
static std::mutex write_mutex;
static bool prepared = false;

struct PtySession {
  pid_t pid;
  int fd;
  bool alive;
  int exitCode;
};
static std::mutex pty_mutex;
static std::map<int,std::shared_ptr<PtySession>> pty_sessions;
static int pty_next_id = 1;
static std::string str(JNIEnv* env, jstring value) {
  const char* p = env->GetStringUTFChars(value, nullptr);
  std::string s(p); env->ReleaseStringUTFChars(value, p); return s;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zfold_assistant_PiRuntime_prepare(JNIEnv* env, jclass, jstring cache) {
  if (prepared) return true;
  if (pipe(command_pipe) || pipe(event_pipe)) return false;
  fcntl(command_pipe[0], F_SETFD, FD_CLOEXEC);
  fcntl(command_pipe[1], F_SETFD, FD_CLOEXEC);
  fcntl(event_pipe[0], F_SETFD, FD_CLOEXEC);
  fcntl(event_pipe[1], F_SETFD, FD_CLOEXEC);
  setenv("TMPDIR", str(env, cache).c_str(), 1);
  prepared = true; return true;
}
extern "C" JNIEXPORT jint JNICALL
Java_dev_zfold_assistant_PiRuntime_startNode(JNIEnv* env, jclass, jstring entry, jstring data, jstring credentials, jstring nativeDir) {
  std::string script = str(env, entry), root = str(env, data), credentialsDir = str(env, credentials);
  std::string input = std::to_string(command_pipe[0]), output = std::to_string(event_pipe[1]);
  setenv("PI_CODING_AGENT_DIR", (root + "/.pi").c_str(), 1);
  setenv("ZFOLD_CREDENTIALS_DIR", credentialsDir.c_str(), 1);
  setenv("ZFOLD_NATIVE_LIB_DIR", str(env, nativeDir).c_str(), 1);
  std::string runtime = script.substr(0, script.find_last_of('/'));
  setenv("ZFOLD_NODE_BOOTSTRAP", (runtime + "/node-bootstrap.cjs").c_str(), 1);
  setenv("ZFOLD_NODE_EXEC_PATH", "/proc/self/exe", 1);
  setenv("ZFOLD_NODE_WRAPPER", (runtime + "/node-wrapper.sh").c_str(), 1);
  // Explicit application-owned paths; never borrow another app's runtime or home.
  chdir(root.c_str());
  std::string values[] = {"node", "--max-old-space-size=192", script, input, output, root};
  char* argv[6]; for (int i=0;i<6;i++) argv[i]=values[i].data();
  return node::Start(6, argv);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_zfold_assistant_PiRuntime_writeCommand(JNIEnv* env, jclass, jbyteArray command) {
  std::string s(env->GetArrayLength(command), '\0');
  env->GetByteArrayRegion(command, 0, s.size(), reinterpret_cast<jbyte*>(s.data()));
  s += "\n";
  std::lock_guard<std::mutex> lock(write_mutex);
  size_t offset = 0;
  while (offset < s.size()) {
    ssize_t n = write(command_pipe[1], s.data()+offset, s.size()-offset);
    if (n < 0 && errno == EINTR) continue;
    if (n <= 0) break;
    offset += n;
  }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_zfold_assistant_PiRuntime_readEvents(JNIEnv* env, jclass) {
  char buffer[16384]; ssize_t n;
  do { n = read(event_pipe[0], buffer, sizeof(buffer)); } while (n < 0 && errno == EINTR);
  if (n <= 0) return nullptr;
  jbyteArray bytes=env->NewByteArray(n); env->SetByteArrayRegion(bytes,0,n,(jbyte*)buffer); return bytes;
}

static void throwJava(JNIEnv* env, const char* message) {
  jclass cls = env->FindClass("java/lang/IllegalArgumentException");
  if (cls) env->ThrowNew(cls, message);
}

// Non-interactive children get their own process group and separate output pipes.
// Startup status closes on exec, so Java never cancels an unestablished group.
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_zfold_assistant_RuntimeProcess_start(JNIEnv* env,jclass,jobjectArray args,jstring cwd,jobjectArray environment) {
  std::vector<std::string> values,envValues;
  for(int i=0;i<env->GetArrayLength(args);i++){
    jstring value=(jstring)env->GetObjectArrayElement(args,i);values.push_back(str(env,value));env->DeleteLocalRef(value);
  }
  for(int i=0;i<env->GetArrayLength(environment);i++){
    jstring value=(jstring)env->GetObjectArrayElement(environment,i);envValues.push_back(str(env,value));env->DeleteLocalRef(value);
  }
  if(values.empty()){throwJava(env,"Empty command");return nullptr;}
  std::vector<char*> argv,envp;
  for(auto& value:values)argv.push_back(value.data());argv.push_back(nullptr);
  for(auto& value:envValues)envp.push_back(value.data());envp.push_back(nullptr);
  std::string directory=str(env,cwd);
  int output[2]={-1,-1},error[2]={-1,-1},startup[2]={-1,-1};
  auto closePipes=[&](){for(int fd:{output[0],output[1],error[0],error[1],startup[0],startup[1]})if(fd>=0)close(fd);};
  if(pipe2(output,O_CLOEXEC)||pipe2(error,O_CLOEXEC)||pipe2(startup,O_CLOEXEC)){
    closePipes();throwJava(env,"Cannot open process pipes");return nullptr;
  }
  pid_t pid=fork();
  if(pid<0){closePipes();throwJava(env,"Cannot fork command");return nullptr;}
  if(pid==0){
    close(output[0]);close(error[0]);close(startup[0]);
    int failure=0;
    if(setsid()<0||chdir(directory.c_str())<0)failure=errno;
    int input=open("/dev/null",O_RDONLY|O_CLOEXEC);
    if(!failure&&(input<0||dup2(input,STDIN_FILENO)<0||dup2(output[1],STDOUT_FILENO)<0||dup2(error[1],STDERR_FILENO)<0))failure=errno;
    if(input>2)close(input);close(output[1]);close(error[1]);
    signal(SIGPIPE,SIG_DFL);
    if(!failure){execve(argv[0],argv.data(),envp.data());failure=errno;}
    (void)!write(startup[1],&failure,sizeof(failure));_exit(127);
  }
  close(output[1]);output[1]=-1;close(error[1]);error[1]=-1;close(startup[1]);startup[1]=-1;
  int failure=0;ssize_t bytes;
  do{bytes=read(startup[0],&failure,sizeof(failure));}while(bytes<0&&errno==EINTR);
  close(startup[0]);startup[0]=-1;
  if(bytes!=0){
    kill(-pid,SIGKILL);kill(pid,SIGKILL);
    while(waitpid(pid,nullptr,0)<0&&errno==EINTR){}
    closePipes();std::string message=std::string("Cannot start command: ")+strerror(failure?failure:EIO);
    throwJava(env,message.c_str());return nullptr;
  }
  jint fields[]={pid,output[0],error[0]};jintArray result=env->NewIntArray(3);
  env->SetIntArrayRegion(result,0,3,fields);return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zfold_assistant_RuntimeProcess_poll(JNIEnv*,jclass,jint pid){
  int status=0;pid_t result;
  do{result=waitpid(pid,&status,WNOHANG);}while(result<0&&errno==EINTR);
  if(result==0)return -1;
  if(result<0)return -2;
  return WIFEXITED(status)?WEXITSTATUS(status):WIFSIGNALED(status)?128+WTERMSIG(status):255;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zfold_assistant_RuntimeProcess_signalGroup(JNIEnv*,jclass,jint pid,jint signal){
  if(pid>1)kill(-pid,signal);
}

static std::shared_ptr<PtySession> ptySession(int id) {
  auto it = pty_sessions.find(id);
  return it == pty_sessions.end() ? nullptr : it->second;
}

static void reapPty(std::shared_ptr<PtySession> session) {
  if (!session->alive) return;
  int status = 0;
  pid_t result = waitpid(session->pid, &status, WNOHANG);
  if (result == session->pid) {
    session->alive = false;
    session->exitCode = WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
  } else if (result < 0 && errno != EINTR) {
    session->alive = false;
    session->exitCode = -1;
  }
}

static std::string base64(JNIEnv* env, const std::vector<char>& value) {
  static const char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string out;
  out.reserve(((value.size() + 2) / 3) * 4);
  size_t i = 0;
  while (i + 2 < value.size()) {
    unsigned v = (unsigned char)value[i] << 16 | (unsigned char)value[i+1] << 8 | (unsigned char)value[i+2];
    out += table[(v >> 18) & 63]; out += table[(v >> 12) & 63]; out += table[(v >> 6) & 63]; out += table[v & 63];
    i += 3;
  }
  if (i + 1 == value.size()) {
    unsigned v = (unsigned char)value[i] << 16;
    out += table[(v >> 18) & 63]; out += table[(v >> 12) & 63]; out += "==";
  } else if (i + 2 == value.size()) {
    unsigned v = (unsigned char)value[i] << 16 | (unsigned char)value[i+1] << 8;
    out += table[(v >> 18) & 63]; out += table[(v >> 12) & 63]; out += table[(v >> 6) & 63]; out += '=';
  }
  (void)env;
  return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyOpen(JNIEnv* env, jclass, jobjectArray argvArray,
                                           jstring cwd, jobjectArray envArray, jint rows, jint cols) {
  int argc = env->GetArrayLength(argvArray);
  if (argc < 1) { throwJava(env, "PTY command is empty"); return 0; }
  std::vector<std::string> storage;
  for (int i = 0; i < argc; i++) {
    jstring item = (jstring)env->GetObjectArrayElement(argvArray, i);
    const char* value = item ? env->GetStringUTFChars(item, nullptr) : "";
    storage.emplace_back(value);
    if (item) env->ReleaseStringUTFChars(item, value);
    if (storage.back().empty() || storage.back().size() > 4096) { throwJava(env, "PTY argv is invalid"); return 0; }
  }
  std::vector<char*> argv;
  for (const std::string& value : storage) argv.push_back(const_cast<char*>(value.data()));
  for (const std::string& value : storage) {
    if (value.empty() || value.size() > 4096) { throwJava(env, "PTY argv is invalid"); return 0; }
  }
  argv.push_back(nullptr);
  int envc = envArray ? env->GetArrayLength(envArray) : 0;
  std::vector<std::string> envStorage;
  for (int i = 0; i < envc; i++) {
    jstring item = (jstring)env->GetObjectArrayElement(envArray, i);
    const char* value = item ? env->GetStringUTFChars(item, nullptr) : "";
    envStorage.emplace_back(value);
    if (item) env->ReleaseStringUTFChars(item, value);
  }
  std::vector<char*> envp;
  for (const std::string& value : envStorage) envp.push_back(const_cast<char*>(value.data()));
  envp.push_back(nullptr);
  const char* cwdChars = env->GetStringUTFChars(cwd, nullptr);
  std::string cwdValue = cwdChars;
  env->ReleaseStringUTFChars(cwd, cwdChars);

  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) { throwJava(env, "posix_openpt failed"); return 0; }
  if (grantpt(master) || unlockpt(master)) { close(master); throwJava(env, "PTY grant failed"); return 0; }
  char slaveName[128];
  if (ptsname_r(master, slaveName, sizeof(slaveName))) { close(master); throwJava(env, "ptsname failed"); return 0; }
  termios term{};
  if (tcgetattr(master, &term) == 0) {
    term.c_cflag |= CLOCAL | CREAD;
    term.c_iflag |= ICRNL;
    term.c_oflag |= OPOST | ONLCR;
    winsize size{(unsigned short)rows,(unsigned short)cols,0,0};
    ioctl(master, TIOCSWINSZ, &size);
    tcsetattr(master, TCSANOW, &term);
  }
  fcntl(master, F_SETFD, FD_CLOEXEC);
  std::string slave = slaveName;
  pid_t pid = fork();
  if (pid == 0) {
    signal(SIGPIPE, SIG_DFL);
    if (setsid() < 0) _exit(127);
    int slaveFd = open(slave.c_str(), O_RDWR);
    if (slaveFd < 0) _exit(127);
    ioctl(slaveFd, TIOCSCTTY, 0);
    dup2(slaveFd, 0); dup2(slaveFd, 1); dup2(slaveFd, 2);
    if (slaveFd > 2) close(slaveFd);
    close(master);
    if (chdir(cwdValue.c_str()) != 0) _exit(127);
    execve(argv[0], argv.data(), envp.empty() ? environ : envp.data());
    _exit(127);
  }
  if (pid < 0) { close(master); throwJava(env, "fork failed"); return 0; }
  int id;
  {
    std::lock_guard<std::mutex> lock(pty_mutex);
    if (pty_sessions.size() >= 4) {
      close(master); kill(pid, SIGTERM); waitpid(pid, nullptr, 0);
      throwJava(env, "PTY sessions are full; close one first"); return 0;
    }
    id = pty_next_id++;
    pty_sessions[id] = std::make_shared<PtySession>(PtySession{pid, master, true, -1});
  }
  return id;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyWrite(JNIEnv* env, jclass, jint id, jbyteArray data) {
  std::shared_ptr<PtySession> session;
  { std::lock_guard<std::mutex> lock(pty_mutex); session = ptySession(id); }
  if (!session || session->fd < 0) { throwJava(env, "PTY session not found"); return; }
  int length = env->GetArrayLength(data);
  std::vector<char> bytes(length);
  if (length) env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
  size_t offset = 0;
  while (offset < bytes.size()) {
    ssize_t written = write(session->fd, bytes.data() + offset, bytes.size() - offset);
    if (written >= 0) { offset += written; continue; }
    if (errno == EINTR) continue;
    throwJava(env, "PTY write failed"); return;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyRead(JNIEnv* env, jclass, jint id, jint timeoutMs) {
  std::shared_ptr<PtySession> session;
  { std::lock_guard<std::mutex> lock(pty_mutex); session = ptySession(id); }
  if (!session || session->fd < 0) { throwJava(env, "PTY session not found"); return nullptr; }
  std::vector<char> output;
  int deadline = timeoutMs;
  while (output.size() < 256 * 1024) {
    pollfd fds{session->fd, POLLIN, 0};
    int ready = poll(&fds, 1, std::min(100, std::max(0, deadline)));
    deadline -= 100;
    if (ready > 0 && fds.revents & (POLLIN | POLLHUP)) {
      char chunk[32768];
      ssize_t readBytes = read(session->fd, chunk, sizeof(chunk));
      if (readBytes > 0) output.insert(output.end(), chunk, chunk + readBytes);
      if (readBytes > 0 && output.size() < 256 * 1024 && deadline > 0) continue;
      break;
    }
    { std::lock_guard<std::mutex> lock(pty_mutex); reapPty(session); }
    if (!session->alive || deadline <= 0) break;
  }
  std::string encoded = base64(env, output);
  return env->NewStringUTF(encoded.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyResize(JNIEnv* env, jclass, jint id, jint rows, jint cols) {
  std::shared_ptr<PtySession> session;
  { std::lock_guard<std::mutex> lock(pty_mutex); session = ptySession(id); }
  if (!session || session->fd < 0) { throwJava(env, "PTY session not found"); return; }
  winsize size{(unsigned short)rows,(unsigned short)cols,0,0};
  if (ioctl(session->fd, TIOCSWINSZ, &size)) throwJava(env, "PTY resize failed");
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyStatus(JNIEnv* env, jclass, jint id) {
  std::shared_ptr<PtySession> session;
  {
    std::lock_guard<std::mutex> lock(pty_mutex);
    session = ptySession(id);
    if (!session) return -2;
    reapPty(session);
  }
  return session->alive ? -1 : session->exitCode;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zfold_assistant_PiRuntime_ptyClose(JNIEnv* env, jclass, jint id) {
  std::shared_ptr<PtySession> session;
  {
    std::lock_guard<std::mutex> lock(pty_mutex);
    session = ptySession(id);
    if (!session) return;
    if (session->fd >= 0) { close(session->fd); session->fd = -1; }
    if (session->alive) kill(-session->pid, SIGTERM);
    reapPty(session);
  }
  for (int i = 0; i < 20 && session->alive; i++) {
    usleep(20 * 1000);
    std::lock_guard<std::mutex> lock(pty_mutex);
    reapPty(session);
  }
  if (session->alive) {
    std::lock_guard<std::mutex> lock(pty_mutex);
    kill(-session->pid, SIGKILL); waitpid(session->pid, nullptr, 0); session->alive = false;
  }
  pty_sessions.erase(id);
}
