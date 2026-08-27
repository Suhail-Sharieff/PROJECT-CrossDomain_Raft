# Stage 1: Build the application using Gradle
FROM gradle:8.5-jdk21 AS builder
WORKDIR /source
COPY --chown=gradle:gradle . /source
RUN gradle :raft-server:installDist :raft-client:installDist --no-daemon

# Stage 2: Create the final slim runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the built server and client applications from builder stage
COPY --from=builder /source/raft-server/build/install/raft-server /app/server
COPY --from=builder /source/raft-client/build/install/raft-client /app/client

# Expose gRPC port
EXPOSE 8001

# Run the server by default
ENTRYPOINT ["/app/server/bin/raft-server"]
