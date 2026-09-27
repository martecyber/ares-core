# Multi-stage build for ares-core.
#
# ares-sdk is a real cross-repo Maven dependency now (resolved from GitHub Packages, see
# pom.xml's <repositories>), so the build needs a GitHub token to authenticate the download.
# Passed via a BuildKit secret (`docker build --secret id=github_token,...` /
# docker/build-push-action's `secrets:` input) rather than an ARG/ENV, so it never lands in any
# image layer: settings.xml is written, used, and deleted within the SAME RUN instruction, so it
# never appears in the layer diff at all — and the runtime stage below never COPYs it either way.
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=secret,id=github_token \
    mkdir -p /root/.m2 && \
    printf '<settings><servers><server><id>github</id><username>x-access-token</username><password>%s</password></server></servers></settings>' \
      "$(cat /run/secrets/github_token)" > /root/.m2/settings.xml && \
    ./mvnw -q -B -ntp dependency:go-offline && \
    rm -f /root/.m2/settings.xml
COPY src src
RUN ./mvnw -q -B -ntp package -DskipTests && \
    mv target/*-exec.jar target/app.jar

# Stage 2: runtime
FROM eclipse-temurin:21-jre-alpine
# Package-building tools for the CLI / agent installer endpoints:
#   • fpm (Ruby gem) builds .deb and .rpm packages — but fpm shells out to `ar`
#     (.deb) and `rpmbuild` (.rpm), and needs GNU tar rather than the BusyBox `tar`
#     applet Alpine uses by default (BusyBox tar doesn't support the
#     --owner/--group/--numeric-owner flags fpm invokes it with). All three
#     (tar/binutils/rpm) must stay OUTSIDE .build-deps below — build-base pulls in
#     binutils transiently, which briefly hides this gap until `apk del .build-deps`
#     removes it again, at which point every .deb build starts failing with
#     "Need executable 'ar'" (confirmed against a real fpm run, not just apk docs).
#   • NSIS would build the Windows .exe installer, but the `nsis` apk only lives in
#     Alpine's `testing` repo (not main/community) and isn't reliably available.
#     CliPackageService / AgentPackageService return 501 NOT_IMPLEMENTED when
#     makensis isn't on PATH, and the platform ships a pure-Java .zip fallback
#     for Windows operators — so omitting it here degrades gracefully.
RUN apk add --no-cache git su-exec ruby libffi tar binutils rpm && \
    apk add --no-cache --virtual .build-deps ruby-dev build-base libffi-dev && \
    gem install --no-document fpm && \
    apk del .build-deps && \
    rm -rf /root/.gem/ruby/*/cache
WORKDIR /app
RUN addgroup -S ares && adduser -S ares -G ares && mkdir -p /data/kb /app/plugins
COPY --from=build /app/target/app.jar /app/app.jar
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 8888
ENV JAVA_OPTS="-XX:+UseZGC -XX:MaxRAMPercentage=75"
ENTRYPOINT ["/entrypoint.sh"]
