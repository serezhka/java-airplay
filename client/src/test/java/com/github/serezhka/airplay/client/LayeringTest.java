package com.github.serezhka.airplay.client;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.github.serezhka.airplay.client",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LayeringTest {

    @ArchTest
    static final ArchRule clientDoesNotDependOnServerOrApps = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.github.serezhka.airplay.server..",
                    "com.github.serezhka.airplay.app..",
                    "com.github.serezhka.airplay.player.."
            );

    @ArchTest
    static final ArchRule clientStaysFreeOfNativeStacks = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.freedesktop.gstreamer..",
                    "org.bytedeco.."
            );
}
