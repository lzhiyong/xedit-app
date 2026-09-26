#include "init_handler.h"

__attribute__((constructor, used))
void _libunwindstack_ctor() {
    _libunwindstack_preload_init();
}
