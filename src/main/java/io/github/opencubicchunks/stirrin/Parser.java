package io.github.opencubicchunks.stirrin;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.ASTParser;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.eclipse.jdt.core.dom.AST.JLS18;

public class Parser {
    private final ASTParser parser;

    private final Set<Path> dependencyClasses;
    private final Set<Path> projectClasses;

    public Parser(Set<Path> dependencyClasses, Set<Path> projectClasses) {
        this.parser = ASTParser.newParser(JLS18);

        this.dependencyClasses = dependencyClasses;
        this.projectClasses = projectClasses;
    }

    public ASTParser getParser() {
        setParserOptions(this.parser, this.dependencyClasses, this.projectClasses);
        return parser;
    }

    private static void setParserOptions(ASTParser parser, Set<Path> dependencyClasses, Set<Path> projectClasses) {
        Map<String, String> options = JavaCore.getOptions();
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_18); //or newer version
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_18);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_18);
        options.put(JavaCore.COMPILER_PB_ASSERT_IDENTIFIER, JavaCore.ERROR);
        options.put(JavaCore.COMPILER_PB_ENUM_IDENTIFIER, JavaCore.ERROR);
        options.put(JavaCore.COMPILER_CODEGEN_INLINE_JSR_BYTECODE, JavaCore.ENABLED);
        options.put(JavaCore.COMPILER_RELEASE, JavaCore.ENABLED);
        parser.setCompilerOptions(options);

        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setStatementsRecovery(true);
        parser.setIgnoreMethodBodies(true);

        parser.setEnvironment(
            dependencyClasses.stream().map(file -> file.toAbsolutePath().toString()).toArray(String[]::new),
            projectClasses.stream().map(file -> file.toAbsolutePath().toString()).toArray(String[]::new),
            null, true);
    }
}
