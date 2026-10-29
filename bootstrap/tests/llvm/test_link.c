#include <llvm-c/Analysis.h>
#include <llvm-c/Core.h>
#include <llvm-c/Target.h>
#include <llvm-c/TargetMachine.h>

#include <stdio.h>

/* Exercise the installed LLVM C ABI independently of the unfinished Mini backend. */
int main(void) {
    if (LLVMInitializeNativeTarget() || LLVMInitializeNativeAsmPrinter())
        return 1;

    char *triple = LLVMGetDefaultTargetTriple();
    char *error = NULL;
    LLVMTargetRef target = NULL;
    if (LLVMGetTargetFromTriple(triple, &target, &error)) {
        fprintf(stderr, "%s\n", error ? error : "native target not available");
        LLVMDisposeMessage(error);
        LLVMDisposeMessage(triple);
        return 1;
    }

    LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
        target, triple, "generic", "", LLVMCodeGenLevelNone,
        LLVMRelocDefault, LLVMCodeModelDefault
    );
    LLVMDisposeMessage(triple);
    if (!machine)
        return 1;

    LLVMContextRef context = LLVMContextCreate();
    LLVMModuleRef module = LLVMModuleCreateWithNameInContext("link-test", context);
    int invalid = LLVMVerifyModule(module, LLVMReturnStatusAction, &error);
    if (invalid)
        fprintf(stderr, "%s\n", error ? error : "LLVM module verification failed");

    LLVMDisposeMessage(error);
    LLVMDisposeModule(module);
    LLVMContextDispose(context);
    LLVMDisposeTargetMachine(machine);
    return invalid ? 1 : 0;
}
