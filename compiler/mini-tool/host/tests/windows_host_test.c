/* Native Win32 host contract test. Parent/child share this executable; no shell. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "mini_host.h"

static void check(int ok, const char *why) { if (!ok) { fprintf(stderr,"host test: %s (Win32 %lu)\n",why,(unsigned long)GetLastError()); exit(1); } }
static MkString *str(const wchar_t *s) {
    size_t n=wcslen(s);MkString *r=mk_string_alloc((MkInt)n);
    for(size_t i=0;i<n;i++)r->chars[i]=(MkChar)s[i];return r;
}
static void release(MkString *s) { if(s)mk_release(&s->header); }
static MkString *argv_list(MkString *exe) {
    const wchar_t *tail=L"\1--child\1\1space value\1tail\\\1quote\"value\1café 😀\1&|<>^%";
    MkString *t=str(tail),*r=mk_string_concat(exe,t);release(t);return r;
}
static void outputs(void) {
    MkString *a=mh_exec_stdout(),*b=mh_exec_stderr();
    check(a&&b&&a->length==200000&&b->length==200000,"both captured streams exceed pipe buffers");
    for(MkInt i=0;i<a->length;i++)check(mk_string_at(a,i)=='o'&&mk_string_at(b,i)=='e',"captured bytes exact");
    release(a);release(b);check(mh_exec_error()==NULL,"no stale start error");
}
int wmain(int argc,wchar_t **argv) {
    if(argc==3&&!wcscmp(argv[1],L"--long")) {
        check(wcslen(argv[2])==20000,"long argument length");
        for(size_t i=0;i<20000;i++)check(argv[2][i]==L'x',"long argument content");
        return 0;
    }
    if(argc==2&&!wcscmp(argv[1],L"--exit")) { puts("inherited stdout");fputs("inherited stderr\n",stderr);return 23; }
    if(argc>1&&!wcscmp(argv[1],L"--child")) {
        const wchar_t *expected[]={L"",L"space value",L"tail\\",L"quote\"value",L"café 😀",L"&|<>^%"};
        check(argc==8,"child argc");for(int i=0;i<6;i++)check(!wcscmp(argv[i+2],expected[i]),"CRT argv roundtrip");
        wchar_t env[100];check(GetEnvironmentVariableW(L"MINI_HOST_TEST",env,100)&&!wcscmp(env,L"new Ω 😀"),"child environment override");
        char block[1000];memset(block,'o',sizeof block);for(int i=0;i<200;i++)check(fwrite(block,1,sizeof block,stdout)==sizeof block,"child stdout");
        memset(block,'e',sizeof block);for(int i=0;i<200;i++)check(fwrite(block,1,sizeof block,stderr)==sizeof block,"child stderr");
        return 7;
    }
    check(mh_os()==2,"Windows detection");
    MkString *self=mh_exe_path(),*cwd=mh_cwd(),*empty=str(L""),*args=argv_list(self),*env=str(L"MINI_HOST_TEST=new Ω 😀");
    wchar_t nativeSelf[32768],extended[32768];
    DWORD selfLen=GetModuleFileNameW(NULL,nativeSelf,32768);check(selfLen&&selfLen<32760,"module path for extended API test");
    check(swprintf(extended,32768,L"//?/%ls",nativeSelf)>0,"extended path");
    for(wchar_t *p=extended;*p;p++)if(*p==L'\\')*p=L'/';
    MkString *ext=str(extended);check(mh_size(ext)>0,"extended path size");
    MkString *real=mh_realpath(ext);check(real&&real->length>0,"extended path canonicalization");release(real);release(ext);
    check(SetEnvironmentVariableW(L"MINI_HOST_TEST",L"old"),"parent environment setup");
    check(mh_exec(args,cwd,env,1)==7,"child exit code");outputs();
    wchar_t value[100];check(GetEnvironmentVariableW(L"MINI_HOST_TEST",value,100)&&!wcscmp(value,L"old"),"parent environment unchanged");
    MkString *missing=str(L"mini-host-executable-that-does-not-exist-83f41");
    check(mh_exec(missing,cwd,empty,1)==-1,"missing executable start failure");
    MkString *err=mh_exec_error();check(err&&err->length>0,"start failure diagnostic");release(err);release(missing);
    check(mh_exec(args,cwd,env,1)==7,"repeated process state");outputs();
    /* A relative PATH entry is resolved using the child's cwd, not parent's cwd. */
    wchar_t current[32768];DWORD n=GetCurrentDirectoryW(32768,current);check(n&&n<32768,"cwd");
    wchar_t dir[32768],copy[32768];check(swprintf(dir,32768,L"%ls\\Host Ω 😀 path",current)>0,"temp path");
    MkString *dirString=str(dir);check(mh_mkdirs(dirString),"Unicode child cwd");
    check(swprintf(copy,32768,L"%ls\\probe.exe",dir)>0,"exe path");
    wchar_t own[32768];n=GetModuleFileNameW(NULL,own,32768);check(n&&n<32768&&CopyFileW(own,copy,FALSE),"copy test executable");
    MkString *probe=str(L"probe"),*probeArgs=argv_list(probe),*pathEnv=str(L"MINI_HOST_TEST=new Ω 😀\1pAtH=.");
    check(mh_exec(probeArgs,dirString,pathEnv,1)==7,"child PATH override + relative entry + cwd");outputs();
    MkString *after=mh_cwd();check(mk_string_equals(cwd,after),"parent cwd unchanged");release(after);
    MkString *exitTail=str(L"\1--exit"),*exitArgs=mk_string_concat(self,exitTail);release(exitTail);
    check(mh_exec(exitArgs,cwd,empty,0)==23,"inherited stdio exit code");
    check(mh_exec_stdout()==NULL&&mh_exec_stderr()==NULL,"inherited mode has no stale capture");release(exitArgs);
    release(probe);release(probeArgs);release(pathEnv);check(DeleteFileW(copy)&&RemoveDirectoryW(dir),"test cleanup");release(dirString);
    MkString *longTail=mk_string_alloc(20008);
    const wchar_t *prefix=L"\1--long\1";
    for(int i=0;i<8;i++)longTail->chars[i]=(MkChar)prefix[i];
    for(int i=8;i<20008;i++)longTail->chars[i]='x';
    MkString *longArgs=mk_string_concat(self,longTail);release(longTail);
    check(mh_exec(longArgs,cwd,empty,1)==0,"valid command longer than 16K");release(longArgs);
    release(args);release(self);release(cwd);release(env);release(empty);
    puts("Windows host argv/env/cwd/dual-stream/exit/error tests passed.");return 0;
}
