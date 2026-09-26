#ifndef NULLABILITY_H_

#ifdef __clang__

// nothing here

#else

// make GCC happy
#define _Nullable
#define _Nonnull __attribute__((nonnull))

#endif

#endif  // NULLABILITY_H_
