# EC2 Plugin for Jenkins (Percona fork) - local build/test
# Patched: ComputerRetentionWork NPE/timer-death guards + EKS IRSA support
# (WebIdentityTokenFileCredentialsProvider, AWS SDK v2).
# Releases are tag-driven: push a v*.percona.* tag and .github/workflows/release.yml
# builds the .hpi and publishes it to a GitHub Release.

version := "5.24.percona.4"
image := "maven:3.9-eclipse-temurin-17"
container := "ec2-build"
m2_volume := "ec2-m2-cache"

# Build plugin .hpi (skipping tests for speed)
build:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean package -DskipTests -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker cp {{container}}:/plugin/target/ec2.hpi ./ec2-{{version}}.hpi
    docker rm {{container}}
    ls -lh ./ec2-{{version}}.hpi

# Build and run tests
test:
    #!/usr/bin/env bash
    set -euo pipefail
    docker volume create {{m2_volume}} >/dev/null 2>&1 || true
    docker rm -f {{container}} 2>/dev/null || true
    docker create --name {{container}} -w /plugin \
        -v {{m2_volume}}:/root/.m2/repository \
        {{image}} \
        mvn clean verify -Dchangelist={{version}}
    docker cp "$(pwd)/." {{container}}:/plugin
    docker start -a {{container}}
    docker rm {{container}}

# Clean build artifacts and cache
clean:
    rm -f ec2-*.hpi
    docker rm -f {{container}} 2>/dev/null || true

# Nuke Maven cache volume (forces full re-download)
clean-cache:
    docker volume rm {{m2_volume}} 2>/dev/null || true
