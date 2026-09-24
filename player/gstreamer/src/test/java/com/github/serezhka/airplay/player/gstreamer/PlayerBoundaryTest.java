package com.github.serezhka.airplay.player.gstreamer;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.github.serezhka.airplay.player.gstreamer",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PlayerBoundaryTest {

    @ArchTest
    static final ArchRule doesNotReachIntoServerOrOtherPlayers = noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.github.serezhka.airplay.server.internal..",
                    "com.github.serezhka.airplay.player.ffmpeg..",
                    "com.github.serezhka.airplay.player.dump.."
            );
}
