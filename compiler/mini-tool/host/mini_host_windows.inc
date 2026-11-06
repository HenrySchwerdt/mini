/* Native Windows implementation of mini_host.h. Included only on Windows.
 * Wide paths/argv retain UTF-16 units. Captured byte streams are UTF-8.
 * Child output uses temporary files so neither stream can fill a pipe and deadlock.
 */
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <io.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>
#include <limits.h>

static void *mh_alloc(size_t n) {
    void *p = malloc(n ? n : 1);
    if (!p) mk_panic("OutOfMemoryError");
    return p;
}
static wchar_t *mh_wcopy(const wchar_t *s, size_t n) {
    if (n > (SIZE_MAX / sizeof(wchar_t)) - 1) mk_panic("OutOfMemoryError");
    wchar_t *r = mh_alloc((n + 1) * sizeof *r);
    memcpy(r, s, n * sizeof *r); r[n] = 0; return r;
}
static wchar_t *mh_wide(const MkString *s) {
    if (!s) return NULL;
    wchar_t *r = mh_alloc(((size_t)s->length + 1) * sizeof *r);
    for (MkInt i = 0; i < s->length; i++) {
        if (!mk_string_at(s, i)) { free(r); return NULL; }
        r[i] = (wchar_t)mk_string_at(s, i);
    }
    r[s->length] = 0; return r;
}
/* Apply only to API path parameters, never to argv or environment values. */
static wchar_t *mh_path_separators(wchar_t *p) {
    if (p) for (wchar_t *w=p; *w; w++) if (*w == L'/') *w = L'\\';
    return p;
}
static wchar_t *mh_path(const MkString *s) { return mh_path_separators(mh_wide(s)); }
static MkString *mh_from_wide(const wchar_t *s, size_t n) {
    if (n > INT32_MAX) mk_panic("OutOfMemoryError");
    MkString *r = mk_string_alloc((MkInt)n);
    for (size_t i = 0; i < n; i++) r->chars[i] = (MkChar)s[i];
    return r;
}
MkInt mh_os(void) { return 2; }
MkString *mh_env(const MkString *name) {
    wchar_t *n = mh_wide(name);
    if (!n) return NULL;
    SetLastError(ERROR_SUCCESS);
    DWORD len = GetEnvironmentVariableW(n, NULL, 0);
    if (!len) { DWORD e = GetLastError(); free(n); return e == ERROR_ENVVAR_NOT_FOUND ? NULL : mk_string_alloc(0); }
    wchar_t *v = mh_alloc((size_t)len * sizeof *v);
    DWORD got = GetEnvironmentVariableW(n, v, len);
    free(n);
    MkString *r = got < len ? mh_from_wide(v, got) : NULL;
    free(v); return r;
}
MkString *mh_cwd(void) {
    DWORD n = GetCurrentDirectoryW(0, NULL);
    if (!n) mk_panic("cannot get current directory");
    wchar_t *p = mh_alloc((size_t)n * sizeof *p);
    DWORD got = GetCurrentDirectoryW(n, p);
    if (!got || got >= n) { free(p); mk_panic("cannot get current directory"); }
    MkString *s = mh_from_wide(p, got); free(p); return s;
}
MkString *mh_exe_path(void) {
    DWORD cap = 256;
    for (;;) {
        wchar_t *p = mh_alloc((size_t)cap * sizeof *p);
        DWORD n = GetModuleFileNameW(NULL, p, cap);
        if (n && n < cap) { MkString *r = mh_from_wide(p, n); free(p); return r; }
        free(p);
        if (!n || cap >= 32768) return NULL;
        cap *= 2;
    }
}
MkBoolean mh_is_tty(MkInt fd) { return _isatty((int)fd) ? 1 : 0; }
MkInt mh_terminal_width(void) {
    CONSOLE_SCREEN_BUFFER_INFO i;
    return GetConsoleScreenBufferInfo(GetStdHandle(STD_OUTPUT_HANDLE), &i) ? (MkInt)(i.srWindow.Right - i.srWindow.Left + 1) : 0;
}
MkLong mh_now_millis(void) { return (MkLong)GetTickCount64(); }
MkInt mh_kind(const MkString *p) { return mk_b1_file_kind(p); }
static int mh_attrs(const MkString *s, WIN32_FILE_ATTRIBUTE_DATA *a) {
    wchar_t *p = mh_path(s);
    int ok = p && GetFileAttributesExW(p, GetFileExInfoStandard, a);
    free(p); return ok;
}
MkLong mh_size(const MkString *p) {
    WIN32_FILE_ATTRIBUTE_DATA a;
    if (!mh_attrs(p, &a)) return -1;
    uint64_t n = ((uint64_t)a.nFileSizeHigh << 32) | a.nFileSizeLow;
    return n > INT64_MAX ? -1 : (MkLong)n;
}
MkLong mh_mtime_millis(const MkString *p) {
    WIN32_FILE_ATTRIBUTE_DATA a;
    if (!mh_attrs(p, &a)) return -1;
    uint64_t t = ((uint64_t)a.ftLastWriteTime.dwHighDateTime << 32) | a.ftLastWriteTime.dwLowDateTime;
    return (MkLong)(t / 10000) - INT64_C(11644473600000);
}
MkString *mh_list(const MkString *p) {
    MkString *s = mk_b1_list_dir(p);
    /* Windows forbids newline/control characters in directory entry names. */
    if (s && mk_string_is_latin1(s)) { uint8_t *b = (uint8_t *)(void *)s->chars; for (MkInt i = 0; i < s->length; i++) if (b[i] == '\n') b[i] = 1; }
    else if (s) for (MkInt i = 0; i < s->length; i++) if (s->chars[i] == '\n') s->chars[i] = 1;
    return s;
}
MkBoolean mh_mkdirs(const MkString *p) { return mk_b1_mkdirs(p); }
static wchar_t *mh_join(const wchar_t *a, const wchar_t *b) {
    size_t x = wcslen(a), y = wcslen(b);
    if (x > SIZE_MAX / sizeof(wchar_t) - y - 2) mk_panic("OutOfMemoryError");
    wchar_t *r = mh_alloc((x + y + 2) * sizeof *r);
    memcpy(r, a, x * sizeof *r); r[x] = L'\\'; memcpy(r+x+1, b, (y+1)*sizeof *r); return r;
}
static MkInt mh_remove_wide(const wchar_t *p) {
    DWORD a = GetFileAttributesW(p);
    if (a == INVALID_FILE_ATTRIBUTES) return 0;
    if (!(a & FILE_ATTRIBUTE_DIRECTORY)) return DeleteFileW(p) ? 1 : 0;
    MkInt count = 0;
    if (!(a & FILE_ATTRIBUTE_REPARSE_POINT)) {
        wchar_t *pattern = mh_join(p, L"*");
        WIN32_FIND_DATAW d;
        HANDLE h = FindFirstFileW(pattern, &d); free(pattern);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                if (!wcscmp(d.cFileName, L".") || !wcscmp(d.cFileName, L"..")) continue;
                wchar_t *c = mh_join(p, d.cFileName);
                MkInt n = mh_remove_wide(c); free(c);
                if (n > INT32_MAX - count) count = INT32_MAX; else count += n;
            } while (FindNextFileW(h, &d));
            FindClose(h);
        }
    }
    (void)RemoveDirectoryW(p); return count;
}
MkInt mh_remove(const MkString *s) {
    wchar_t *p = mh_path(s); MkInt n = p ? mh_remove_wide(p) : 0; free(p); return n;
}
void mh_make_executable(const MkString *p) { (void)p; }
MkString *mh_realpath(const MkString *s) {
    wchar_t *p = mh_path(s);
    if (!p) return NULL;
    HANDLE h = CreateFileW(p, FILE_READ_ATTRIBUTES, FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE, NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    free(p); if (h == INVALID_HANDLE_VALUE) return NULL;
    DWORD cap = GetFinalPathNameByHandleW(h, NULL, 0, FILE_NAME_NORMALIZED);
    wchar_t *v = mh_alloc(((size_t)cap + 1) * sizeof *v);
    DWORD n = cap ? GetFinalPathNameByHandleW(h, v, cap+1, FILE_NAME_NORMALIZED) : 0;
    CloseHandle(h);
    MkString *r = NULL;
    if (n && n <= cap) {
        if (!wcsncmp(v, L"\\\\?\\UNC\\", 8)) { v[6] = L'\\'; r = mh_from_wide(v+6, n-6); }
        else if (!wcsncmp(v, L"\\\\?\\", 4)) r = mh_from_wide(v+4, n-4);
        else r = mh_from_wide(v, n);
    }
    free(v); return r;
}
MkInt mh_write(MkInt fd, const MkString *s) {
    HANDLE h = (HANDLE)_get_osfhandle((int)fd); DWORD mode;
    if (GetConsoleMode(h, &mode)) {
        wchar_t *w = mh_alloc(((size_t)s->length * 2 + 1)*sizeof *w);
        DWORD length = 0;
        for (MkInt i=0;i<s->length;i++) {
            if (mk_string_at(s, i) == '\n' && (i == 0 || mk_string_at(s, i-1) != '\r')) w[length++] = L'\r';
            w[length++] = (wchar_t)mk_string_at(s, i);
        }
        DWORD at=0, n=0; int ok=1;
        while (at < length) {
            DWORD chunk = length-at > 32768 ? 32768 : length-at;
            if (!WriteConsoleW(h,w+at,chunk,&n,NULL) || !n) {ok=0;break;} at+=n;
        }
        free(w); return ok ? 0 : 2;
    }
    size_t len=0, at=0; char *u=mk_string_to_utf8(s,&len);
    MkInt r=0;
    while(at<len) {
        DWORD n=0, chunk=(DWORD)((len-at)>MAXDWORD ? MAXDWORD : (len-at));
        if(!WriteFile(h,u+at,chunk,&n,NULL)||!n) {r=GetLastError()==ERROR_BROKEN_PIPE?1:2;break;} at+=n;
    }
    free(u);return r;
}
void mh_exit(MkInt code) { mk_runtime_flush();exit((int)code); }

static MkString *g_out, *g_err, *g_start_error;
static void mh_error(DWORD code) {
    wchar_t *msg=NULL;
    DWORD n=FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER|FORMAT_MESSAGE_FROM_SYSTEM|FORMAT_MESSAGE_IGNORE_INSERTS,NULL,code,0,(wchar_t *)&msg,0,NULL);
    g_start_error=n?mh_from_wide(msg,n):mk_string_from_utf8("Windows process error",21);
    if(msg)LocalFree(msg);
}
/* Split in place; an empty list has zero entries (argv is checked by caller). */
static wchar_t **mh_split(wchar_t *s, size_t *count) {
    *count=0; if(!s || !*s)return NULL;
    size_t n=1;for(wchar_t *p=s;*p;p++)if(*p==1)n++;
    wchar_t **v=mh_alloc(n*sizeof *v);v[(*count)++]=s;
    for(wchar_t *p=s;*p;p++)if(*p==1){*p=0;v[(*count)++]=p+1;}
    return v;
}
static size_t mh_keylen(const wchar_t *s) {
    const wchar_t *e=wcschr(s+(*s==L'='),L'=');return e?(size_t)(e-s):wcslen(s);
}
static int mh_env_cmp(const void *a,const void *b) { return _wcsicmp(*(const wchar_t *const *)a,*(const wchar_t *const *)b); }
static wchar_t *mh_environment(wchar_t **extra,size_t count,wchar_t **path) {
    wchar_t *base=GetEnvironmentStringsW();if(!base)return NULL;
    size_t n=0;for(wchar_t *p=base;*p;p+=wcslen(p)+1)n++;
    wchar_t **all=mh_alloc((n+count+1)*sizeof *all);size_t at=0;
    for(wchar_t *p=base;*p;p+=wcslen(p)+1)all[at++]=p;
    for(size_t i=0;i<count;i++) {
        size_t k=mh_keylen(extra[i]);
        if(!k || extra[i][k]!=L'=' || extra[i][0]==L'='){free(all);FreeEnvironmentStringsW(base);SetLastError(ERROR_INVALID_PARAMETER);return NULL;}
        size_t j=0;while(j<at && !(mh_keylen(all[j])==k && !_wcsnicmp(all[j],extra[i],k)))j++;
        if(j==at)at++;all[j]=extra[i];
    }
    qsort(all,at,sizeof *all,mh_env_cmp);
    size_t units=1;for(size_t i=0;i<at;i++){size_t l=wcslen(all[i])+1;if(units>SIZE_MAX/sizeof(wchar_t)-l)mk_panic("OutOfMemoryError");units+=l;}
    wchar_t *out=mh_alloc((units+1)*sizeof *out), *w=out;
    *path=NULL;
    for(size_t i=0;i<at;i++){size_t l=wcslen(all[i])+1;memcpy(w,all[i],l*sizeof *w);if(mh_keylen(w)==4&&!_wcsnicmp(w,L"PATH",4))*path=w+5;w+=l;}
    *w=0;if(w==out)w[1]=0;
    free(all);FreeEnvironmentStringsW(base);return out;
}
#include "../../../runtime/src/windows_command.h"
static int mh_absolute(const wchar_t *p) { return p[0]==L'/'||p[0]==L'\\'||(p[0]&&p[1]==L':'); }
static wchar_t *mh_find_exe(const wchar_t *name,const wchar_t *cwd,const wchar_t *path) {
    if(wcschr(name,L'/')||wcschr(name,L'\\')||wcschr(name,L':'))return mh_absolute(name)?mh_wcopy(name,wcslen(name)):mh_join(cwd,name);
    /* Search the effective child's PATH, resolving relative entries in its cwd. No implicit cmd.exe/batch interpretation. */
    wchar_t *paths=mh_wcopy(path?path:L"",path?wcslen(path):0);
    size_t total=wcslen(paths)+1, dirs=1;for(wchar_t *p=paths;*p;p++)if(*p==L';')dirs++;
    if(dirs>(SIZE_MAX/sizeof(wchar_t)-total)/(wcslen(cwd)+2))mk_panic("OutOfMemoryError");
    wchar_t *search=mh_alloc((total+dirs*(wcslen(cwd)+2))*sizeof *search),*w=search;
    wchar_t *start=paths;
    for(wchar_t *p=paths;;p++)if(*p==L';'||!*p){int end=!*p;*p=0;wchar_t *s=start;size_t n=wcslen(s);if(n>=2&&s[0]==L'"'&&s[n-1]==L'"'){s[n-1]=0;s++;}wchar_t *full=mh_absolute(s)?mh_wcopy(s,wcslen(s)):mh_join(cwd,s);n=wcslen(full);memcpy(w,full,n*sizeof *w);w+=n;free(full);if(end)break;*w++=L';';start=p+1;}
    *w=0;free(paths);
    DWORD n=SearchPathW(search,name,L".exe",0,NULL,NULL);
    wchar_t *exe=n?mh_alloc(((size_t)n+1)*sizeof *exe):NULL;
    if(exe){DWORD got=SearchPathW(search,name,L".exe",n+1,exe,NULL);if(!got||got>n){free(exe);exe=NULL;}}
    free(search);return exe;
}
static HANDLE mh_duplicate(HANDLE h) {
    HANDLE r=NULL;if(!h||h==INVALID_HANDLE_VALUE)return INVALID_HANDLE_VALUE;
    if(!DuplicateHandle(GetCurrentProcess(),h,GetCurrentProcess(),&r,0,TRUE,DUPLICATE_SAME_ACCESS))return INVALID_HANDLE_VALUE;return r;
}
static HANDLE mh_temp(void) {
    wchar_t dir[MAX_PATH+1],file[MAX_PATH+100];
    DWORD n=GetTempPathW(MAX_PATH+1,dir);if(!n||n>MAX_PATH)return INVALID_HANDLE_VALUE;
    static unsigned counter;
    for(int i=0;i<100;i++) {
        int l=swprintf(file,sizeof file/sizeof *file,L"%lsmini-%lu-%llu-%u.tmp",dir,(unsigned long)GetCurrentProcessId(),(unsigned long long)GetTickCount64(),++counter);
        if(l<0){SetLastError(ERROR_BAD_LENGTH);return INVALID_HANDLE_VALUE;}
        SECURITY_ATTRIBUTES sa={sizeof sa,NULL,TRUE};
        HANDLE h=CreateFileW(file,GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,&sa,CREATE_NEW,FILE_ATTRIBUTE_TEMPORARY|FILE_FLAG_DELETE_ON_CLOSE,NULL);
        if(h!=INVALID_HANDLE_VALUE||GetLastError()!=ERROR_FILE_EXISTS)return h;
    }
    SetLastError(ERROR_FILE_EXISTS);return INVALID_HANDLE_VALUE;
}
static MkString *mh_read_capture(HANDLE h) {
    LARGE_INTEGER n,zero;zero.QuadPart=0;
    if(!GetFileSizeEx(h,&n)||n.QuadPart<0||n.QuadPart>INT32_MAX){SetLastError(ERROR_BAD_LENGTH);return NULL;}
    if(!SetFilePointerEx(h,zero,NULL,FILE_BEGIN))return NULL;
    size_t size=(size_t)n.QuadPart,at=0;char *p=mh_alloc(size);
    while(at<size){DWORD got=0;if(!ReadFile(h,p+at,(DWORD)(size-at),&got,NULL)||!got){free(p);SetLastError(ERROR_READ_FAULT);return NULL;}at+=got;}
    MkString *r=mk_string_from_utf8(p,size);free(p);return r;
}
MkInt mh_exec(const MkString *argvList,const MkString *cwd,const MkString *envList,MkBoolean capture) {
    if(g_out)mk_release(&g_out->header);if(g_err)mk_release(&g_err->header);if(g_start_error)mk_release(&g_start_error->header);
    g_out=g_err=g_start_error=NULL;
    MkInt result=-1;DWORD error=0;
    wchar_t *argtext=mh_wide(argvList),*envtext=mh_wide(envList),*dir=mh_path(cwd),*block=NULL,*cmd=NULL,*exe=NULL,*path=NULL;
    wchar_t **args=NULL,**extra=NULL;size_t argc=0,envc=0;
    HANDLE io[3]={INVALID_HANDLE_VALUE,INVALID_HANDLE_VALUE,INVALID_HANDLE_VALUE};
    LPPROC_THREAD_ATTRIBUTE_LIST attrs=NULL;int initialized=0;
    PROCESS_INFORMATION pi={0};
    args=mh_split(argtext,&argc);extra=mh_split(envtext,&envc);
    if(!argc||!*args[0]||wcschr(args[0],L'"')||(cwd&&!dir)||(envList&&!envtext)){error=ERROR_INVALID_PARAMETER;goto done;}
    if(!dir){MkString *s=mh_cwd();dir=mh_wide(s);mk_release(&s->header);}
    block=mh_environment(extra,envc,&path);if(!block){error=GetLastError();goto done;}
    cmd=mk_windows_command(args,argc);if(!cmd){error=GetLastError();goto done;}
    exe=mh_path_separators(mh_find_exe(args[0],dir,path));if(!exe){error=GetLastError();goto done;}
    if(capture){SECURITY_ATTRIBUTES sa={sizeof sa,NULL,TRUE};io[0]=CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&sa,OPEN_EXISTING,0,NULL);io[1]=mh_temp();io[2]=mh_temp();}
    else {io[0]=mh_duplicate(GetStdHandle(STD_INPUT_HANDLE));io[1]=mh_duplicate(GetStdHandle(STD_OUTPUT_HANDLE));io[2]=mh_duplicate(GetStdHandle(STD_ERROR_HANDLE));}
    for(int i=0;i<3;i++)if(io[i]==INVALID_HANDLE_VALUE){error=GetLastError();goto done;}
    SIZE_T bytes=0;(void)InitializeProcThreadAttributeList(NULL,1,0,&bytes);
    attrs=mh_alloc(bytes);
    if(!InitializeProcThreadAttributeList(attrs,1,0,&bytes)){error=GetLastError();goto done;}initialized=1;
    if(!UpdateProcThreadAttribute(attrs,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,io,sizeof io,NULL,NULL)){error=GetLastError();goto done;}
    STARTUPINFOEXW si;memset(&si,0,sizeof si);si.StartupInfo.cb=sizeof si;si.StartupInfo.dwFlags=STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput=io[0];si.StartupInfo.hStdOutput=io[1];si.StartupInfo.hStdError=io[2];si.lpAttributeList=attrs;
    fflush(NULL);
    if(!CreateProcessW(exe,cmd,NULL,NULL,TRUE,CREATE_UNICODE_ENVIRONMENT|EXTENDED_STARTUPINFO_PRESENT,block,dir,&si.StartupInfo,&pi)){error=GetLastError();goto done;}
    if(WaitForSingleObject(pi.hProcess,INFINITE)!=WAIT_OBJECT_0){error=GetLastError();goto done;}
    DWORD code;if(!GetExitCodeProcess(pi.hProcess,&code)){error=GetLastError();goto done;}
    if(capture){g_out=mh_read_capture(io[1]);if(!g_out){error=GetLastError();goto done;}g_err=mh_read_capture(io[2]);if(!g_err){error=GetLastError();goto done;}}
    result=(MkInt)code;
 done:
    if(pi.hThread)CloseHandle(pi.hThread);if(pi.hProcess)CloseHandle(pi.hProcess);
    if(initialized)DeleteProcThreadAttributeList(attrs);free(attrs);
    for(int i=0;i<3;i++)if(io[i]!=INVALID_HANDLE_VALUE)CloseHandle(io[i]);
    free(args);free(extra);free(argtext);free(envtext);free(dir);free(block);free(cmd);free(exe);
    if(error)mh_error(error);
    return result;
}
MkString *mh_exec_stdout(void){if(g_out)mk_retain(&g_out->header);return g_out;}
MkString *mh_exec_stderr(void){if(g_err)mk_retain(&g_err->header);return g_err;}
MkString *mh_exec_error(void){if(g_start_error)mk_retain(&g_start_error->header);return g_start_error;}
