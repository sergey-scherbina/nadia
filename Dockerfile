# nadia, containerized — the Scala 3 implementation.
#
# That choice is not arbitrary. Of the three (SPEC.md §0) this is the one whose runtime is
# already portable: a JVM and one library, no native toolchain, no Metal, nothing that ties
# it to the machine it was written on. The Rust implementation is the reference, but its
# build pulls the whole rozum workspace, so its image belongs in that repository, next to
# the workspace it needs.
#
# The image is also the safety story on Linux. `sandbox-exec` does not exist here, so the
# jail is the container itself: a read-only root filesystem, one writable mount, no
# capabilities, and whatever network the runtime chose to hand over. nadia detects this and
# says so at startup instead of claiming a confinement it is not enforcing — see
# `Confinement` in scala/rozum/Sandbox.scala, and docs/deployment.md for the flags that make
# the claim true.
#
# No `# syntax=` directive on purpose: it makes every build pull a frontend image from Docker
# Hub before it can read this file, which turns a registry hiccup into a build that fails
# before it starts. Nothing here needs a feature the built-in frontend lacks.

ARG JAVA_VERSION=21
ARG SCALA_CLI_VERSION=1.15.0

# ---------------------------------------------------------------------------- build ------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build

ARG SCALA_CLI_VERSION
ARG TARGETARCH

RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Pinned rather than "latest": an image that builds differently tomorrow is an image whose
# failures cannot be reproduced.
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
      amd64) arch=x86_64 ;; \
      arm64) arch=aarch64 ;; \
      *) echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL "https://github.com/VirtusLab/scala-cli/releases/download/v${SCALA_CLI_VERSION}/scala-cli-${arch}-pc-linux.gz" \
      | gunzip > /usr/local/bin/scala-cli; \
    chmod +x /usr/local/bin/scala-cli

WORKDIR /src

# Resolve the dependencies against the build definition alone, before the sources exist.
# Downloading the compiler and upickle is most of the build, and it depends only on this
# file — so editing an agent source reuses this layer instead of re-fetching Scala.
COPY scala/project.scala scala/project.scala
RUN printf 'package warm\nobject Warm\n' > scala/Warm.scala \
 && scala-cli --power compile scala \
 && rm scala/Warm.scala

COPY scala/ scala/
# No preamble: with one, the artefact is a shell script that happens to contain a jar, and
# `java -jar` on it fails in a way that reads like a corrupt build.
RUN mkdir -p /out \
 && scala-cli --power package scala -o /out/nadia.jar --assembly --preamble=false --force \
 && java -jar /out/nadia.jar --help > /dev/null

# -------------------------------------------------------------------------- runtime ------
FROM eclipse-temurin:${JAVA_VERSION}-jre AS runtime

# `bash` because the tool contract is `bash -lc` and a model needs pipes and `&&`; `git`
# because a coding agent that cannot read the history of the repository it was pointed at
# is working blind. Nothing else — every extra package is reachable by a model that has a
# shell, and the toolchain for the actual project belongs in an image that extends this one
# (docs/deployment.md).
#
# The `userdel` is not tidying: this Ubuntu base already ships a user at uid 1000, and
# `useradd --uid 1000` fails on it. uid 1000 is worth insisting on rather than letting the
# system pick — every manifest here pins `runAsUser: 1000`, and a uid that drifted with the
# base image would turn a bind-mounted workspace into a permission error at run time.
RUN apt-get update \
 && apt-get install -y --no-install-recommends bash git ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && userdel --remove ubuntu 2>/dev/null || true; \
    useradd --create-home --uid 1000 --shell /bin/bash nadia \
 && mkdir -p /workspace && chown nadia:nadia /workspace

COPY --from=build /out/nadia.jar /opt/nadia/nadia.jar

# A launcher rather than ENTRYPOINT ["java", …] so the JVM flags are in one visible place
# and `docker run … nadia --help` reads like the command it is.
RUN printf '%s\n' \
      '#!/bin/sh' \
      '# Heap from the container limit, not from the host: a JVM that sizes itself against' \
      '# the machine gets OOM-killed by the cgroup the moment the limit is smaller.' \
      'exec java -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -jar /opt/nadia/nadia.jar "$@"' \
    > /usr/local/bin/nadia \
 && chmod +x /usr/local/bin/nadia

USER nadia
WORKDIR /workspace
ENV HOME=/home/nadia \
    LANG=C.UTF-8 \
    NADIA_IN_CONTAINER=1

# No credential and no network by default: the same promise the agent makes on a laptop.
# `--gateway`, `--provider` and a mounted key file are how a deployment opts out of it.
ENTRYPOINT ["/usr/local/bin/nadia"]

LABEL org.opencontainers.image.title="nadia" \
      org.opencontainers.image.description="An LLM coding agent in Scala and ScalaScript" \
      org.opencontainers.image.source="https://github.com/sergey-scherbina/nadia" \
      org.opencontainers.image.licenses="MIT"
