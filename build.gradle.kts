tasks.wrapper {
    gradleVersion = "9.8.0"
    distributionType = Wrapper.DistributionType.ALL
}

gradle.taskGraph.whenReady {
    val bootRuns = allTasks.map { it.path }.filter { it.endsWith(":bootRun") }
    if (bootRuns.size > 1) {
        throw GradleException(
            "Start one receiver: :app:gstreamer:bootRun or :app:ffmpeg:bootRun"
        )
    }
}
