#include <vector>
#include <string>
#include <cstdio>
#include <iostream>
#include <iosfwd>
#include <thread>

#include "../preload/init_handler.h"

void* thread_entry(void* arg) {
    // cause a SIGSEGV here
    int* p = nullptr;
    *p = 0;
    return nullptr;
}


int main() {

    _libunwindstack_preload_init();

    std::thread t(thread_entry, nullptr);

    t.join();

    return 0;
}
