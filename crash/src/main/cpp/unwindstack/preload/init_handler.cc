//
// Created by sulfate on 2024-03-13.
//

#include "init_handler.h"

#include <cstdint>
#include <cstdlib>
#include <cstddef>
#include <vector>
#include <memory>
#include <string>
#include <cstring>
#include <unistd.h>
#include <cerrno>
#include <array>
#include <fcntl.h>

#include <base/runtime_common.h>
#include <base/logging.h>

namespace libunwindstack::preload {

struct sigaction old_action;

void HandleUnexpectedSignalAndroid(int signal_number, siginfo_t* info, void* raw_context) {
    art::HandleUnexpectedSignalCommon(signal_number,
                                      info,
                                      raw_context,
            /* handle_timeout_signal= */ false,
            /* dump_on_stderr= */ false);

    // Run the old signal handler if it exists.
    if (old_action.sa_sigaction != nullptr) {
        old_action.sa_sigaction(signal_number, info, raw_context);
    }
}

}

void _libunwindstack_preload_init() {
    static bool initialized = false;
    if (initialized) {
        return;
    }
    initialized = true;
    // get argc and argv
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd >= 0) {
        auto buf = std::make_unique<std::array<char, 1024>>();
        // we do not release the memory
        auto n = read(fd, buf->data(), buf->size());
        close(fd);
        if (n > 0) {
            std::vector<char*> argv;
            argv.push_back(buf->data());
            for (size_t i = 0; i < n; i++) {
                if ((*buf)[i] == '\0') {
                    argv.push_back(buf->data() + i + 1);
                }
            }
            // call InitLogging for cmdline
            art::InitLogging(argv.data(), android::base::DefaultAborter);
        }
    }
    art::InitPlatformSignalHandlersCommon(libunwindstack::preload::HandleUnexpectedSignalAndroid,
                                          &libunwindstack::preload::old_action,
            /* handle_timeout_signal= */ false);
}
