FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-noble AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends openssh-client curl fontconfig fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 observer && useradd --uid 10001 --gid observer --create-home observer
WORKDIR /app
COPY --from=build --chown=observer:observer /workspace/target/wook-observer-1.1.0.jar /app/observer.jar
COPY --chmod=755 scripts/ssh-askpass.sh /app/scripts/ssh-askpass.sh
USER observer
ENV SERVER_ADDRESS=0.0.0.0 JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.awt.headless=true"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/observer.jar"]
