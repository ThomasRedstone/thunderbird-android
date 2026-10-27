FROM arm64v8/ubuntu@sha256:b44ffd8afd8ddbe91a08120a3aba4d4d105c17ccfadd835beb5097da5d196d65
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-21-jdk-headless binutils ca-certificates file && rm -rf /var/lib/apt/lists/*
RUN dpkg-query -W > /BUILD-PACKAGES.txt
