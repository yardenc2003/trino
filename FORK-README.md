# Trino History Server Fork
This fork of [Trino](https://github.com/trinodb/trino) is based on official release and includes custom modifications. This document provides steps to build the project and create a Docker image for your own deployments.

## Extra Configurations

This History Server Web UI fork introduces two additional configuration properties:

```properties
web-ui.history-server.url=http://example-history-server:8081
web-ui.history-server.query-path=/api/v1/query/
```

## Feature Development

In the history server fork, we treat `trino-history-server-<version>` as the fork's main branch for that Trino version.

New releases from that branch are tagged as:

- `trino-history-server-<version>.1 `
- `trino-history-server-<version>.2`


### Developing a new Feature

1. Clone the fork locally:

        git clone https://github.com/yardenc2003/trino.git

2. If the fork is not yet based on the desired `trinodb/trino` version, create a new main branch from its tag:

   * Add the upstream remote:
 
           git remote add upstream https://github.com/trinodb/trino.git

   * Fetch all tags:

           git fetch upstream --tags

   * Create the fork main's branch from the tag:

           git checkout tags/<version> -b trino-history-server-<version>

3. Create a new feature branch from the fork main:

        git checkout -b feature-xyz trino-history-server-<version>

4. Develop, commit and push.

5. Open a pull request against the fork's main branch (`trino-history-server-<version>`).

6. Merge the feature branch:

        git checkout trino-history-server-<version>

        git merge feature-xyz

7. Create and push a new tag for the patch release:

        git tag trino-history-server-<version>.2

        git push origin trino-history-server-<version>.2

## Building a Forked Custom Docker image

To build a Docker image from your modified Trino fork:

1. Run the Maven build: 

    ```bash
    ./mvnw clean install -DskipTests
    ```

2. Run the custom Docker build script. You can **optionally** pass the base Trino version and a custom (forked) version:

    ```bash
    ./core/docker/forked-trino-docker.sh
    ```

   Or specify versions explicitly:

    ```bash
    ./core/docker/forked-trino-docker.sh 475 475.2
    ```

This will produce Docker images for different architectures:

* `trino-history-webui:475-<arch>` — a forked Trino Web UI image based on the core Trino server, packaged with essential plugins and history UI support
