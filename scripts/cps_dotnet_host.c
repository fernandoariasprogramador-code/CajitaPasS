#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>

typedef int (*hostfxr_main_startupinfo_fn)(
    int argc,
    const char** argv,
    const char* host_path,
    const char* dotnet_root,
    const char* app_path
);

int main(int argc, char** argv) {
    const char* dotnet_root = getenv("CPS_DOTNET_ROOT");
    const char* host_path = getenv("CPS_DOTNET_HOST_PATH");
    const char* fxr_path = getenv("CPS_DOTNET_HOSTFXR_PATH");

    if (!dotnet_root || !host_path || !fxr_path) {
        fprintf(stderr, "[CPS-DotNetHost] Missing CPS_DOTNET_ROOT / CPS_DOTNET_HOST_PATH / CPS_DOTNET_HOSTFXR_PATH\n");
        return 1;
    }

    void* lib = dlopen(fxr_path, RTLD_LAZY | RTLD_GLOBAL);
    if (!lib) {
        fprintf(stderr, "[CPS-DotNetHost] dlopen(%s) failed: %s\n", fxr_path, dlerror());
        return 1;
    }

    hostfxr_main_startupinfo_fn main_fn =
        (hostfxr_main_startupinfo_fn)dlsym(lib, "hostfxr_main_startupinfo");
    if (!main_fn) {
        fprintf(stderr, "[CPS-DotNetHost] dlsym(hostfxr_main_startupinfo) failed: %s\n", dlerror());
        return 1;
    }

    /* app_path = NULL makes hostfxr behave identically to `dotnet` CLI */
    return main_fn(argc, (const char**)argv, host_path, dotnet_root, NULL);
}
