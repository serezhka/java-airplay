package com.github.serezhka.airplay.server;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = {
                "com.github.serezhka.airplay.protocol",
                "com.github.serezhka.airplay.server"
        },
        importOptions = ImportOption.DoNotIncludeTests.class
)
class LayeringTest {

    @ArchTest
    static final ArchRule protocolStaysFreeOfReceiverClientAndApps = noClasses()
            .that().resideInAPackage("com.github.serezhka.airplay.protocol..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.github.serezhka.airplay.server..",
                    "com.github.serezhka.airplay.client..",
                    "com.github.serezhka.airplay.app..",
                    "com.github.serezhka.airplay.player..",
                    "io.netty..",
                    "javax.jmdns.."
            );

    @ArchTest
    static final ArchRule serverDoesNotDependOnClientOrApps = noClasses()
            .that().resideInAPackage("com.github.serezhka.airplay.server..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.github.serezhka.airplay.client..",
                    "com.github.serezhka.airplay.app..",
                    "com.github.serezhka.airplay.player.."
            );

    @ArchTest
    static final ArchRule protocolAndServerStayFreeOfNativeStacks = noClasses()
            .that().resideInAnyPackage(
                    "com.github.serezhka.airplay.protocol..",
                    "com.github.serezhka.airplay.server.."
            )
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.freedesktop.gstreamer..",
                    "org.bytedeco.."
            );
}
