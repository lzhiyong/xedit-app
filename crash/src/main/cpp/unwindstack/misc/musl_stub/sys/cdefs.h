#ifndef MISC_MUSL_STUB_SYS_CDEFS_H
#define MISC_MUSL_STUB_SYS_CDEFS_H

// this file exists to make musl happy

#if defined(__cplusplus)
#define __BEGIN_DECLS extern "C" {
#define __END_DECLS }
#else
#define __BEGIN_DECLS
#define __END_DECLS
#endif

#endif
