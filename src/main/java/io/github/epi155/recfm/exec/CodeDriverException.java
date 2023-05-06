package io.github.epi155.recfm.exec;

import org.jetbrains.annotations.NotNull;

/**
 * exception thrown when no code generator is found
 */
public class CodeDriverException extends RuntimeException {
    /**
     * constructor called when nothing is found
     */
    public CodeDriverException() {
        super("Code Provider not Found");
    }

    /**
     * constructor invoked when no code generator with the specified class name is found
     * @param codeProviderClassName code generator class name
     */
    public CodeDriverException(@NotNull String codeProviderClassName) {
        super("Code Provider <"+codeProviderClassName+"> not Found");
    }
}
