//
// Created by sulfate on 2024-03-13.
//

#ifndef LIBUNWINDSTACK_INIT_HANDLER_H
#define LIBUNWINDSTACK_INIT_HANDLER_H

#ifdef __cplusplus

extern "C" {

void _libunwindstack_preload_init();

};

#else

void _libunwindstack_preload_init(void);

#endif

#endif //LIBUNWINDSTACK_INIT_HANDLER_H
