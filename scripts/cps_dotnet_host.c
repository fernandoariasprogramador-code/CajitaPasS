#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <ucontext.h>
#include <unistd.h>

typedef int (*hostfxr_main_startupinfo_fn)(
    int argc,
    const char** argv,
    const char* host_path,
    const char* dotnet_root,
    const char* app_path
);

static volatile int sigsys_logged = 0;

static void raw_write_str(const char* s) {
    if (!s) return;
    size_t len = 0;
    while (s[len]) len++;
    if (len > 0) {
        (void)write(STDERR_FILENO, s, len);
    }
}

static void raw_write_ulong(unsigned long v) {
    char buf[32];
    int i = 31;
    buf[i] = '\0';
    if (v == 0) {
        (void)write(STDERR_FILENO, "0", 1);
        return;
    }
    while (v > 0 && i > 0) {
        buf[--i] = (char)('0' + (v % 10));
        v /= 10;
    }
    (void)write(STDERR_FILENO, &buf[i], (size_t)(31 - i));
}

/*
 * Android seccomp-bpf envía SIGSYS (código 31 -> exit 159) con TRAP_SECCOMP
 * cuando un binario Linux nativo (CoreCLR / musl) invoca una syscall opcional
 * restringida por Android (como membarrier, clone3, rseq o get_mempolicy).
 * Devolver -ENOSYS en x0 permite que .NET active su ruta alternativa de respaldo.
 */
static void sigsys_handler(int sig, siginfo_t* info, void* uctx_raw) {
    (void)sig;
    ucontext_t* uc = (ucontext_t*)uctx_raw;
    unsigned long syscall_nr = uc ? uc->uc_mcontext.regs[8] : 0;

    if (info && info->si_code == 1 /* SYS_SECCOMP */) {
        if (sigsys_logged < 25) {
            sigsys_logged++;
            raw_write_str("[CPS-Seccomp] Syscall #");
            raw_write_ulong(syscall_nr);
            raw_write_str(" bloqueada por Android -> devolviendo -ENOSYS\n");
        }
        if (uc) {
            uc->uc_mcontext.regs[0] = (unsigned long)(-(long)ENOSYS);
        }
        return;
    }

    raw_write_str("[CPS-DotNetHost] SIGSYS inesperado en syscall #");
    raw_write_ulong(syscall_nr);
    raw_write_str("\n");
    _exit(128 + SIGSYS);
}

static void install_sigsys_guard(void) {
    struct sigaction sa;
    unsigned char* p = (unsigned char*)&sa;
    for (size_t i = 0; i < sizeof(sa); i++) p[i] = 0;
    sa.sa_sigaction = sigsys_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigaction(SIGSYS, &sa, NULL);
}

int main(int argc, char** argv) {
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);

    install_sigsys_guard();

    const char* dotnet_root = getenv("CPS_DOTNET_ROOT");
    const char* host_path = getenv("CPS_DOTNET_HOST_PATH");
    const char* app_path = getenv("CPS_DOTNET_APP_PATH");
    const char* fxr_path = getenv("CPS_DOTNET_HOSTFXR_PATH");

    if (!dotnet_root || !host_path || !app_path || !fxr_path) {
        fprintf(stderr, "[CPS-DotNetHost] Missing CPS_DOTNET_ROOT / CPS_DOTNET_HOST_PATH / CPS_DOTNET_APP_PATH / CPS_DOTNET_HOSTFXR_PATH\n");
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

    int rc = main_fn(argc, (const char**)argv, host_path, dotnet_root, app_path);
    if (rc != 0) {
        fprintf(stderr, "[CPS-DotNetHost] hostfxr_main_startupinfo terminó con código %d (0x%x)\n", rc, (unsigned int)rc);
    }
    return rc;
}
