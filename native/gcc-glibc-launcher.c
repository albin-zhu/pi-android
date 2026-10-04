#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc,char **argv) {
  char self[PATH_MAX],root[PATH_MAX],real[PATH_MAX],linker[PATH_MAX],flag[PATH_MAX+40];
  ssize_t n=readlink("/proc/self/exe",self,sizeof(self)-1);
  if(n<=0)return 126;
  self[n]=0;
  char *marker=strstr(self,"/usr/bin/");
  if(!marker)return 126;
  const char *name=marker+strlen("/usr/bin/");
  *marker=0;
  if(snprintf(root,sizeof(root),"%s",self)>=sizeof(root))return 126;
  if(snprintf(real,sizeof(real),"%s/usr/glibc/bin/%s",root,name)>=sizeof(real))return 126;
  if(snprintf(linker,sizeof(linker),"%s/usr/glibc/lib/ld-linux-aarch64.so.1",root)>=sizeof(linker))return 126;
  if(snprintf(flag,sizeof(flag),"-Wl,--dynamic-linker=%s",linker)>=sizeof(flag))return 126;
  char **next=malloc(sizeof(char*)*(size_t)(argc+2));
  if(!next)return 126;
  int count=0;
  next[count++]=(char*)real;
  for(int i=1;i<argc;i++)next[count++]=argv[i];
  next[count++]=flag;
  next[count++]=0;
  execv(real,next);
  perror("execv");
  return 126;
}
