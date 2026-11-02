#ifndef MINI_WINDOWS_COMMAND_H
#define MINI_WINDOWS_COMMAND_H
#include "minirt.h"
#include <windows.h>
#include <wchar.h>
#include <stdlib.h>

/* Always quote each argument, applying the MS CRT backslash/quote rules. */
static wchar_t *mk_windows_command(wchar_t **args,size_t count) {
    size_t cap=1;
    for (size_t i=0; i<count; i++) {
        size_t n=2+(i?1:0), slashes=0;
        for (const wchar_t *p=args[i]; *p; p++) {
            n++;
            if (*p==L'\\') slashes++;
            else { if (*p==L'"') n+=slashes+1; slashes=0; }
            if (n>32767) { SetLastError(ERROR_BAD_LENGTH); return NULL; }
        }
        n+=slashes; /* Double backslashes immediately before the closing quote. */
        if (n>32767-cap) { SetLastError(ERROR_BAD_LENGTH); return NULL; }
        cap+=n;
    }
    wchar_t *s=malloc(cap*sizeof *s);
    if (!s) mk_panic("OutOfMemoryError");
    wchar_t *w=s;
    for(size_t i=0;i<count;i++) {
        if(i)*w++=L' ';*w++=L'"';size_t slashes=0;
        for(const wchar_t *p=args[i];;p++) {
            if(*p==L'\\'){slashes++;continue;}
            size_t copies=slashes*((*p==L'"'||!*p)?2:1);
            while(copies--)*w++=L'\\';slashes=0;
            if(*p==L'"')*w++=L'\\';
            if(!*p)break;*w++=*p;
        }
        *w++=L'"';
    }
    *w=0;return s;
}

#endif
