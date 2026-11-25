#define _GNU_SOURCE
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#if !defined(__aarch64__)
#error "cps_elf_host.c only supports ARM64 (aarch64)"
#endif

#ifndef MAP_FIXED_NOREPLACE
#define MAP_FIXED_NOREPLACE 0x100000
#endif

#ifndef MAP_STACK
#define MAP_STACK 0x20000
#endif

#ifndef R_AARCH64_RELATIVE
#define R_AARCH64_RELATIVE 1027
#endif

#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif

#ifndef AT_SYSINFO_EHDR
#define AT_SYSINFO_EHDR 33
#endif

extern char **environ;

static volatile int sigsys_logged = 0;

static void raw_write_str(const char *s) {
  if (!s)
    return;
  size_t len = 0;
  while (s[len])
    len++;
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

static void sigsys_handler(int sig, siginfo_t *info, void *uctx_raw) {
  (void)sig;
  ucontext_t *uc = (ucontext_t *)uctx_raw;
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

  raw_write_str("[CPS-GoHost] SIGSYS inesperado en syscall #");
  raw_write_ulong(syscall_nr);
  raw_write_str("\n");
  _exit(128 + SIGSYS);
}

static void install_sigsys_guard(void) {
  struct sigaction sa;
  memset(&sa, 0, sizeof(sa));
  sa.sa_sigaction = sigsys_handler;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = SA_SIGINFO | SA_RESTART;
  sigaction(SIGSYS, &sa, NULL);
}

static int pread_exact(int fd, void *buf, size_t len, off_t offset) {
  uint8_t *dst = (uint8_t *)buf;
  size_t done = 0;
  while (done < len) {
    ssize_t r = pread(fd, dst + done, len - done, offset + (off_t)done);
    if (r < 0) {
      if (errno == EINTR)
        continue;
      return -1;
    }
    if (r == 0)
      return -1;
    done += (size_t)r;
  }
  return 0;
}

typedef struct {
  Elf64_Phdr ph;
  uintptr_t map_start;
  uintptr_t map_end;
  int prot;
} LoadedSeg;

static int cmp_seg_by_vaddr(const void *a, const void *b) {
  const LoadedSeg *sa = (const LoadedSeg *)a;
  const LoadedSeg *sb = (const LoadedSeg *)b;
  if (sa->ph.p_vaddr < sb->ph.p_vaddr)
    return -1;
  if (sa->ph.p_vaddr > sb->ph.p_vaddr)
    return 1;
  return 0;
}

static void patch_go_dns_in_region(uint8_t *buf, size_t len) {
  static const char old_ns[12] = "127.0.0.1:53";
  static const char new_ns[12] = "1.1.1.1:0053";
  if (len < 12)
    return;
  size_t limit = len - 12;
  for (size_t i = 0; i <= limit; i++) {
    if (buf[i] == '1' && memcmp(buf + i, old_ns, 12) == 0) {
      if (i + 12 < len && buf[i + 12] >= '0' && buf[i + 12] <= '9') {
        continue;
      }
      memcpy(buf + i, new_ns, 12);
    }
  }
}

static void fill_random_bytes(uint8_t *out, size_t len) {
#ifdef SYS_getrandom
  ssize_t r = syscall(SYS_getrandom, out, len, 0);
  if (r == (ssize_t)len)
    return;
#endif
  int urnd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
  if (urnd >= 0) {
    ssize_t rr = read(urnd, out, len);
    close(urnd);
    if (rr == (ssize_t)len)
      return;
  }
  uint64_t seed = ((uint64_t)getpid() << 32) ^ (uintptr_t)out ^ 0x9e3779b97f4a7c15ULL;
  for (size_t i = 0; i < len; i++) {
    seed ^= seed >> 12;
    seed ^= seed << 25;
    seed ^= seed >> 27;
    out[i] = (uint8_t)(seed * 0x2545F4914F6CDD1DULL);
  }
}

int main(int argc, char **argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);

  install_sigsys_guard();

  const char *env_app_path = getenv("CPS_ELF_APP_PATH");
  const char *app_path = NULL;
  int guest_argc = 0;
  char **guest_argv_src = NULL;
  int use_app_path_as_argv0 = 0;

  if (env_app_path && env_app_path[0] != '\0') {
    app_path = env_app_path;
    guest_argc = argc > 0 ? argc : 1;
    guest_argv_src = argv;
    use_app_path_as_argv0 = 1;
  } else if (argc >= 2) {
    app_path = argv[1];
    guest_argc = argc - 1;
    guest_argv_src = &argv[1];
    use_app_path_as_argv0 = 0;
  } else {
    fprintf(stderr, "[CPS-GoHost] Error: falta CPS_ELF_APP_PATH o ruta del binario ELF ARM64.\n");
    return 126;
  }

  int fd = open(app_path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    fprintf(stderr, "[CPS-GoHost] No se pudo abrir '%s': %s\n", app_path, strerror(errno));
    return 126;
  }

  Elf64_Ehdr ehdr;
  if (pread_exact(fd, &ehdr, sizeof(ehdr), 0) != 0) {
    fprintf(stderr, "[CPS-GoHost] No se pudo leer cabecera ELF de '%s'.\n", app_path);
    close(fd);
    return 126;
  }

  if (ehdr.e_ident[0] == 'M' && ehdr.e_ident[1] == 'Z') {
    fprintf(stderr,
            "[CPS-GoHost] Error: '%s' es un ejecutable Windows (.exe). Compilá para Android ARM64 con: GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -tags \"purego,nodynamic\"\n",
            app_path);
    close(fd);
    return 126;
  }

  if (memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0) {
    fprintf(stderr, "[CPS-GoHost] Error: '%s' no es un binario ELF válido.\n", app_path);
    close(fd);
    return 126;
  }

  if (ehdr.e_ident[EI_CLASS] != ELFCLASS64) {
    fprintf(stderr, "[CPS-GoHost] Error: '%s' no es ELF 64-bit (ARM64 requiere ELF64).\n", app_path);
    close(fd);
    return 126;
  }

  if (ehdr.e_machine != EM_AARCH64) {
    fprintf(stderr,
            "[CPS-GoHost] Error: '%s' es de arquitectura incompatible (e_machine=%u; x86_64=62). Este dispositivo requiere ARM64 (e_machine=183). Compilá con GOOS=linux GOARCH=arm64 CGO_ENABLED=0.\n",
            app_path, (unsigned)ehdr.e_machine);
    close(fd);
    return 126;
  }

  if (ehdr.e_type != ET_EXEC && ehdr.e_type != ET_DYN) {
    fprintf(stderr, "[CPS-GoHost] Error: tipo ELF no ejecutable (e_type=%u).\n", (unsigned)ehdr.e_type);
    close(fd);
    return 126;
  }

  if (ehdr.e_phnum == 0 || ehdr.e_phnum > 64 || ehdr.e_phentsize != sizeof(Elf64_Phdr)) {
    fprintf(stderr, "[CPS-GoHost] Error: tabla PHDR inválida en '%s'.\n", app_path);
    close(fd);
    return 126;
  }

  Elf64_Phdr phdrs[64];
  if (pread_exact(fd, phdrs, (size_t)ehdr.e_phnum * sizeof(Elf64_Phdr), (off_t)ehdr.e_phoff) != 0) {
    fprintf(stderr, "[CPS-GoHost] Error leyendo PHDRs de '%s'.\n", app_path);
    close(fd);
    return 126;
  }

  LoadedSeg segs[32];
  int seg_count = 0;
  uintptr_t min_va = UINTPTR_MAX;
  uintptr_t max_va = 0;
  uintptr_t pt_phdr_va = 0;
  uintptr_t pt_dyn_va = 0;
  size_t pt_dyn_sz = 0;

  for (int i = 0; i < ehdr.e_phnum; i++) {
    Elf64_Phdr *p = &phdrs[i];
    if (p->p_type == PT_INTERP) {
      char interp[256];
      memset(interp, 0, sizeof(interp));
      size_t rlen = p->p_filesz < sizeof(interp) - 1 ? (size_t)p->p_filesz : sizeof(interp) - 1;
      if (rlen > 0) {
        (void)pread_exact(fd, interp, rlen, (off_t)p->p_offset);
      }
      fprintf(stderr,
              "[CPS-GoHost] Error: el binario requiere el enlazador dinámico '%s' que no existe en Android sin Root.\n"
              "[CPS-GoHost] Solución: compilá tu app Go de forma estática con CGO_ENABLED=0 GOOS=linux GOARCH=arm64 (y si usás purego/gen2brain/webp añadí -tags \"purego,nodynamic\").\n",
              interp[0] ? interp : "PT_INTERP");
      close(fd);
      return 126;
    } else if (p->p_type == PT_PHDR) {
      pt_phdr_va = (uintptr_t)p->p_vaddr;
    } else if (p->p_type == PT_DYNAMIC) {
      pt_dyn_va = (uintptr_t)p->p_vaddr;
      pt_dyn_sz = (size_t)p->p_memsz;
    } else if (p->p_type == PT_LOAD && p->p_memsz > 0) {
      if (seg_count >= 32) {
        fprintf(stderr, "[CPS-GoHost] Demasiados segmentos PT_LOAD (>32).\n");
        close(fd);
        return 126;
      }
      segs[seg_count].ph = *p;
      segs[seg_count].prot = 0;
      if (p->p_flags & PF_R)
        segs[seg_count].prot |= PROT_READ;
      if (p->p_flags & PF_W)
        segs[seg_count].prot |= PROT_WRITE;
      if (p->p_flags & PF_X)
        segs[seg_count].prot |= PROT_EXEC;
      seg_count++;
      if ((uintptr_t)p->p_vaddr < min_va)
        min_va = (uintptr_t)p->p_vaddr;
      if ((uintptr_t)(p->p_vaddr + p->p_memsz) > max_va)
        max_va = (uintptr_t)(p->p_vaddr + p->p_memsz);
    }
  }

  if (seg_count == 0 || min_va >= max_va) {
    fprintf(stderr, "[CPS-GoHost] No se encontraron segmentos PT_LOAD ejecutables.\n");
    close(fd);
    return 126;
  }

  qsort(segs, (size_t)seg_count, sizeof(LoadedSeg), cmp_seg_by_vaddr);

  long ps_raw = sysconf(_SC_PAGESIZE);
  uintptr_t page_size = (ps_raw > 0) ? (uintptr_t)ps_raw : 4096;

  uintptr_t load_bias = 0;
  if (ehdr.e_type == ET_DYN) {
    uintptr_t span_start = min_va & ~(page_size - 1);
    uintptr_t span_end = (max_va + page_size - 1) & ~(page_size - 1);
    void *base = mmap(NULL, span_end - span_start, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (base == MAP_FAILED) {
      fprintf(stderr, "[CPS-GoHost] Error reservando memoria PIE (%zu bytes): %s\n",
              (size_t)(span_end - span_start), strerror(errno));
      close(fd);
      return 126;
    }
    load_bias = (uintptr_t)base - span_start;
  }

  uintptr_t highest_mapped_end = 0;
  uintptr_t at_phdr = 0;

  for (int i = 0; i < seg_count; i++) {
    uintptr_t vaddr = (uintptr_t)segs[i].ph.p_vaddr + load_bias;
    uintptr_t mstart = vaddr & ~(page_size - 1);
    uintptr_t mend = (vaddr + (uintptr_t)segs[i].ph.p_memsz + page_size - 1) & ~(page_size - 1);
    segs[i].map_start = mstart;
    segs[i].map_end = mend;

    uintptr_t alloc_start = (mstart < highest_mapped_end) ? highest_mapped_end : mstart;
    if (alloc_start < mend) {
      int mflags = MAP_PRIVATE | MAP_ANONYMOUS |
                   ((ehdr.e_type == ET_DYN) ? MAP_FIXED : MAP_FIXED_NOREPLACE);
      void *mapped = mmap((void *)alloc_start, mend - alloc_start, PROT_READ | PROT_WRITE, mflags, -1, 0);
      if (mapped == MAP_FAILED) {
        fprintf(stderr,
                "[CPS-GoHost] Error mapeando segmento ELF 0x%lx..0x%lx (errno=%d: %s).\n",
                (unsigned long)alloc_start, (unsigned long)mend, errno, strerror(errno));
        close(fd);
        return 126;
      }
      highest_mapped_end = mend;
    }

    if (segs[i].ph.p_filesz > 0) {
      if (pread_exact(fd, (void *)vaddr, (size_t)segs[i].ph.p_filesz, (off_t)segs[i].ph.p_offset) != 0) {
        fprintf(stderr, "[CPS-GoHost] Error leyendo datos del segmento %d.\n", i);
        close(fd);
        return 126;
      }
    }

    if (at_phdr == 0 && segs[i].ph.p_offset <= ehdr.e_phoff &&
        ehdr.e_phoff + (uint64_t)ehdr.e_phnum * sizeof(Elf64_Phdr) <=
            segs[i].ph.p_offset + segs[i].ph.p_filesz) {
      at_phdr = vaddr + (uintptr_t)(ehdr.e_phoff - segs[i].ph.p_offset);
    }
  }

  close(fd);

  if (pt_phdr_va != 0) {
    at_phdr = pt_phdr_va + load_bias;
  } else if (at_phdr == 0) {
    size_t ph_bytes = (size_t)ehdr.e_phnum * sizeof(Elf64_Phdr);
    void *ph_page = mmap(NULL, page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ph_page != MAP_FAILED) {
      memcpy(ph_page, phdrs, ph_bytes);
      mprotect(ph_page, page_size, PROT_READ);
      at_phdr = (uintptr_t)ph_page;
    }
  }

  if (ehdr.e_type == ET_DYN && pt_dyn_sz >= sizeof(Elf64_Dyn)) {
    Elf64_Dyn *dyn = (Elf64_Dyn *)(pt_dyn_va + load_bias);
    size_t max_dyn = pt_dyn_sz / sizeof(Elf64_Dyn);
    uintptr_t rela_addr = 0;
    size_t rela_sz = 0;
    size_t rela_ent = sizeof(Elf64_Rela);
    for (size_t d = 0; d < max_dyn && dyn[d].d_tag != DT_NULL; d++) {
      if (dyn[d].d_tag == DT_RELA)
        rela_addr = (uintptr_t)dyn[d].d_un.d_ptr;
      else if (dyn[d].d_tag == DT_RELASZ)
        rela_sz = (size_t)dyn[d].d_un.d_val;
      else if (dyn[d].d_tag == DT_RELAENT)
        rela_ent = (size_t)dyn[d].d_un.d_val;
    }
    if (rela_addr != 0 && rela_sz > 0 && rela_ent >= sizeof(Elf64_Rela)) {
      uintptr_t rela_base = rela_addr + load_bias;
      size_t count = rela_sz / rela_ent;
      for (size_t k = 0; k < count; k++) {
        Elf64_Rela *r = (Elf64_Rela *)(rela_base + k * rela_ent);
        if (ELF64_R_TYPE(r->r_info) == R_AARCH64_RELATIVE) {
          uint64_t *target = (uint64_t *)(load_bias + (uintptr_t)r->r_offset);
          *target = (uint64_t)(load_bias + (uintptr_t)r->r_addend);
        }
      }
    }
  }

  const char *patch_dns = getenv("CPS_GO_PATCH_DNS");
  if (patch_dns && strcmp(patch_dns, "1") == 0 && access("/etc/resolv.conf", F_OK) != 0) {
    for (int i = 0; i < seg_count; i++) {
      if ((segs[i].ph.p_flags & PF_R) && !(segs[i].ph.p_flags & PF_X) && segs[i].ph.p_filesz > 0) {
        patch_go_dns_in_region((uint8_t *)(segs[i].ph.p_vaddr + load_bias),
                               (size_t)segs[i].ph.p_filesz);
      }
    }
  }

  uintptr_t prev_prot_end = 0;
  for (int i = 0; i < seg_count; i++) {
    uintptr_t pstart = segs[i].map_start;
    uintptr_t pend = segs[i].map_end;
    int prot = segs[i].prot ? segs[i].prot : PROT_READ;

    if (pstart < prev_prot_end) {
      uintptr_t overlap_end = (pend < prev_prot_end) ? pend : prev_prot_end;
      int merged_prot = (segs[i - 1].prot | prot);
      if ((merged_prot & PROT_WRITE) && (merged_prot & PROT_EXEC)) {
        merged_prot = PROT_READ | PROT_EXEC;
      }
      mprotect((void *)pstart, overlap_end - pstart, merged_prot);
      if (merged_prot & PROT_EXEC) {
        __builtin___clear_cache((char *)pstart, (char *)overlap_end);
      }
      pstart = overlap_end;
    }

    if (pstart < pend) {
      if ((prot & PROT_WRITE) && (prot & PROT_EXEC)) {
        prot &= ~PROT_WRITE;
      }
      if (mprotect((void *)pstart, pend - pstart, prot) != 0) {
        fprintf(stderr, "[CPS-GoHost] Error aplicando mprotect en segmento %d: %s\n", i, strerror(errno));
        return 126;
      }
      if (prot & PROT_EXEC) {
        __builtin___clear_cache((char *)pstart, (char *)pend);
      }
    }
    if (pend > prev_prot_end) {
      prev_prot_end = pend;
    }
  }

  const size_t stack_size = 8U * 1024U * 1024U;
  uint8_t *stack_mem = (uint8_t *)mmap(NULL, stack_size, PROT_READ | PROT_WRITE,
                                       MAP_PRIVATE | MAP_ANONYMOUS | MAP_STACK, -1, 0);
  if (stack_mem == MAP_FAILED) {
    fprintf(stderr, "[CPS-GoHost] Error asignando stack de ejecución.\n");
    return 126;
  }

  uint8_t *sp_bytes = stack_mem + stack_size;

  /* 16 bytes aleatorios para AT_RANDOM */
  sp_bytes -= 16;
  uint64_t at_random_ptr = (uint64_t)(uintptr_t)sp_bytes;
  fill_random_bytes(sp_bytes, 16);

  /* Copia execfn en el tope del stack */
  size_t execfn_len = strlen(app_path) + 1;
  sp_bytes -= execfn_len;
  memcpy(sp_bytes, app_path, execfn_len);
  uint64_t at_execfn_ptr = (uint64_t)(uintptr_t)sp_bytes;

  /* Copia argv[0] como app_path para que os.Args[0] refleje el binario Go */
  uint64_t guest_argv0_ptr = at_execfn_ptr;
  if (!use_app_path_as_argv0 && guest_argv_src && guest_argv_src[0]) {
    guest_argv0_ptr = (uint64_t)(uintptr_t)guest_argv_src[0];
  }

  int envc = 0;
  if (environ) {
    while (environ[envc])
      envc++;
  }

  typedef struct {
    uint64_t key;
    uint64_t val;
  } AuxPair;

  AuxPair auxv[24];
  int aux_count = 0;
  auxv[aux_count++] = (AuxPair){AT_PHDR, (uint64_t)at_phdr};
  auxv[aux_count++] = (AuxPair){AT_PHENT, (uint64_t)ehdr.e_phentsize};
  auxv[aux_count++] = (AuxPair){AT_PHNUM, (uint64_t)ehdr.e_phnum};
  auxv[aux_count++] = (AuxPair){AT_PAGESZ, (uint64_t)page_size};
  auxv[aux_count++] = (AuxPair){AT_BASE, 0};
  auxv[aux_count++] = (AuxPair){AT_FLAGS, 0};
  auxv[aux_count++] = (AuxPair){AT_ENTRY, (uint64_t)(ehdr.e_entry + load_bias)};
  auxv[aux_count++] = (AuxPair){AT_UID, (uint64_t)getuid()};
  auxv[aux_count++] = (AuxPair){AT_EUID, (uint64_t)geteuid()};
  auxv[aux_count++] = (AuxPair){AT_GID, (uint64_t)getgid()};
  auxv[aux_count++] = (AuxPair){AT_EGID, (uint64_t)getegid()};
  auxv[aux_count++] = (AuxPair){AT_SECURE, 0};
  long clktck = sysconf(_SC_CLK_TCK);
  auxv[aux_count++] = (AuxPair){AT_CLKTCK, (uint64_t)(clktck > 0 ? clktck : 100)};
  auxv[aux_count++] = (AuxPair){AT_HWCAP, (uint64_t)getauxval(AT_HWCAP)};
  auxv[aux_count++] = (AuxPair){AT_HWCAP2, (uint64_t)getauxval(AT_HWCAP2)};
  auxv[aux_count++] = (AuxPair){AT_RANDOM, at_random_ptr};
  auxv[aux_count++] = (AuxPair){AT_EXECFN, at_execfn_ptr};
  unsigned long vdso = getauxval(AT_SYSINFO_EHDR);
  if (vdso != 0) {
    auxv[aux_count++] = (AuxPair){AT_SYSINFO_EHDR, (uint64_t)vdso};
  }
  auxv[aux_count++] = (AuxPair){AT_NULL, 0};

  /* Total uint64_t entries: argc (1) + argv + NULL + envp + NULL + auxv (2 * aux_count) */
  size_t total_qwords = 1 + (size_t)guest_argc + 1 + (size_t)envc + 1 + (size_t)aux_count * 2;
  uintptr_t sp_aligned = ((uintptr_t)sp_bytes) & ~((uintptr_t)15);
  sp_aligned -= total_qwords * sizeof(uint64_t);
  sp_aligned &= ~((uintptr_t)15);

  uint64_t *sp = (uint64_t *)sp_aligned;
  size_t idx = 0;
  sp[idx++] = (uint64_t)guest_argc;
  sp[idx++] = guest_argv0_ptr;
  for (int i = 1; i < guest_argc; i++) {
    sp[idx++] = (uint64_t)(uintptr_t)guest_argv_src[i];
  }
  sp[idx++] = 0;
  for (int i = 0; i < envc; i++) {
    sp[idx++] = (uint64_t)(uintptr_t)environ[i];
  }
  sp[idx++] = 0;
  for (int i = 0; i < aux_count; i++) {
    sp[idx++] = auxv[i].key;
    sp[idx++] = auxv[i].val;
  }

  fflush(NULL);

  uintptr_t entry_addr = (uintptr_t)ehdr.e_entry + load_bias;
  __asm__ volatile(
      "mov sp, %0\n"
      "mov x0, #0\n"
      "mov x1, #0\n"
      "mov x2, #0\n"
      "br %1\n"
      :
      : "r"(sp_aligned), "r"(entry_addr)
      : "x0", "x1", "x2", "memory");

  return 126;
}
