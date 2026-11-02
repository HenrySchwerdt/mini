#include <llvm-c/Core.h>
#include <llvm-c/LLJIT.h>
#include <llvm-c/Orc.h>
#include <llvm-c/Support.h>
#include <llvm-c/Target.h>

#include <cstdio>

// LLVM exposes these target registration entry points but does not declare
// them in the llvm-c headers. They are the same symbols used by Mini's shim.
extern "C" {
void LLVMInitializeX86TargetInfo();
void LLVMInitializeX86Target();
void LLVMInitializeX86TargetMC();
void LLVMInitializeX86AsmPrinter();
void LLVMInitializeAArch64TargetInfo();
void LLVMInitializeAArch64Target();
void LLVMInitializeAArch64TargetMC();
void LLVMInitializeAArch64AsmPrinter();
}

static int fail(const char *message, LLVMErrorRef error = nullptr) {
    if (error) {
        char *description = LLVMGetErrorMessage(error);
        std::fprintf(stderr, "probe: %s: %s\n", message, description ? description : "unknown LLVM error");
        LLVMDisposeErrorMessage(description);
    } else {
        std::fprintf(stderr, "probe: %s\n", message);
    }
    return 1;
}

int main() {
    unsigned major = 0, minor = 0, patch = 0;
    LLVMGetVersion(&major, &minor, &patch);
    if (major != 18 || minor != 1 || patch != 8) {
        std::fprintf(stderr, "probe: expected LLVM 18.1.8; got %u.%u.%u\n", major, minor, patch);
        return 1;
    }

    LLVMContextRef context = LLVMContextCreate();
    if (!context) return fail("LLVMContextCreate returned a null context");
    LLVMContextDispose(context);

    LLVMInitializeX86TargetInfo();
    LLVMInitializeX86Target();
    LLVMInitializeX86TargetMC();
    LLVMInitializeX86AsmPrinter();
    LLVMInitializeAArch64TargetInfo();
    LLVMInitializeAArch64Target();
    LLVMInitializeAArch64TargetMC();
    LLVMInitializeAArch64AsmPrinter();

    LLVMOrcLLJITRef jit = nullptr;
    if (LLVMErrorRef error = LLVMOrcCreateLLJIT(&jit, nullptr)) {
        return fail("LLVMOrcCreateLLJIT failed", error);
    }
    if (!jit) return fail("LLVMOrcCreateLLJIT returned a null JIT");

    if (LLVMErrorRef error = LLVMOrcDisposeLLJIT(jit)) {
        return fail("LLVMOrcDisposeLLJIT failed", error);
    }

    std::printf("LLVM %u.%u.%u static C API, X86/AArch64 targets, and ORC LLJIT: OK\n", major, minor, patch);
    return 0;
}
