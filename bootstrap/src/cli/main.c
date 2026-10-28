#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--help") == 0) {
        printf("Mini Bootstrap Compiler\n\n");
        printf("Usage: mini0 <command> [options]\n\n");
        printf("Commands:\n");
        printf("  build    Compile a Mini program\n");
        printf("  check    Check a Mini program\n");
        printf("  help     Show this help\n");
        return 0;
    }

    printf("Mini Bootstrap Compiler v0.1.0\n");
    return 0;
}