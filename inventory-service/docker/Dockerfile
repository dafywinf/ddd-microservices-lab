# docker/Dockerfile
#
# Packages the pre-built Spring Boot jar into a minimal JRE image.
#
# Build the jar first (from inventory-service/):
#   mvn package -DskipTests
#
# Then build the image (usually done automatically by app.yml):
#   docker build -f docker/Dockerfile -t inventory-service .

FROM eclipse-temurin:21-jre

WORKDIR /app

# The Spring Boot Maven plugin produces a self-contained executable jar.
# "mvn package" places it in target/ with the project version in the filename.
# The wildcard matches exactly one file.
COPY target/*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
