/* Prototypes of mini_host.c, the host layer of the native `mini` tool. The generated C of `mini` calls these `@CSymbol` functions
 * without declaring them: the build adds `-include mini_host.h` (scripts/build-mini.sh). */
#ifndef MINI_HOST_H
#define MINI_HOST_H
#include "minirt.h"

MkString *mh_env(const MkString *name);
MkString *mh_cwd(void);
MkString *mh_exe_path(void);
MkBoolean mh_is_tty(MkInt fd);
MkInt mh_terminal_width(void);
MkLong mh_now_millis(void);
MkInt mh_kind(const MkString *path);
MkLong mh_size(const MkString *path);
MkLong mh_mtime_millis(const MkString *path);
MkString *mh_list(const MkString *path);
MkBoolean mh_mkdirs(const MkString *path);
MkInt mh_remove(const MkString *path);
void mh_make_executable(const MkString *path);
MkString *mh_realpath(const MkString *path);
MkInt mh_write(MkInt fd, const MkString *text);
void mh_exit(MkInt code);
MkInt mh_exec(const MkString *argvList, const MkString *cwd, const MkString *envList, MkBoolean capture);
MkString *mh_exec_stdout(void);
MkString *mh_exec_stderr(void);
MkString *mh_exec_error(void);

#endif
