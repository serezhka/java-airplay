# Cloud Agent base image (Ubuntu). Cursor clones the repo separately — do not COPY the project.
# Mirrors Linux CI native deps used by playback integration tests.
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive \
    JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
    PATH="/usr/lib/jvm/java-21-openjdk-amd64/bin:${PATH}"

RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        git \
        curl \
        ca-certificates \
        unzip \
        python3 \
        ffmpeg \
        xvfb \
        pulseaudio \
        pulseaudio-utils \
        gstreamer1.0-tools \
        gstreamer1.0-plugins-base \
        gstreamer1.0-plugins-good \
        gstreamer1.0-plugins-bad \
        gstreamer1.0-libav \
        gstreamer1.0-x \
        gstreamer1.0-pulseaudio \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
