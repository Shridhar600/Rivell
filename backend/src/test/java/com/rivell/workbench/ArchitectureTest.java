package com.rivell.workbench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

class ArchitectureTest {

    private static final Set<String> REQUIRED_MODULES = Set.of(
            "foundation",
            "organization",
            "knowledge",
            "contracts",
            "retrieval",
            "intelligence",
            "review",
            "workflow",
            "redline",
            "audit",
            "platform");

    @Test
    void requiredModulesFormAValidSpringModulithApplication() {
        var modules = ApplicationModules.of(RivellApplication.class);

        modules.verify();

        assertThat(modules.stream()
                .map(module -> module.getIdentifier().toString())
                .collect(toSet()))
                .isEqualTo(REQUIRED_MODULES);
    }

    @Test
    void domainModulesDoNotDependOnPlatformOrFileSystemTypes() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.rivell.workbench");

        noClasses()
                .that().resideInAnyPackage(
                        "..organization..",
                        "..knowledge..",
                        "..contracts..",
                        "..retrieval..",
                        "..intelligence..",
                        "..review..",
                        "..workflow..",
                        "..redline..",
                        "..audit..")
                .should().dependOnClassesThat().resideInAnyPackage("java.nio.file..", "..platform..")
                .check(classes);
    }

    @Test
    void contractModuleDoesNotTouchSqlImplementation() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.rivell.workbench");

        noClasses()
                .that().resideInAnyPackage("..contracts..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..")
                .check(classes);
    }

    @Test
    void providerAdapterInternalsStayInsideIntelligence() {
        var classes = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.rivell.workbench");

        noClasses()
                .that().resideInAnyPackage("com.rivell.workbench..")
                .and().resideOutsideOfPackage("com.rivell.workbench.intelligence..")
                .should().dependOnClassesThat().resideInAnyPackage("..intelligence.provider..")
                .check(classes);
    }
}
