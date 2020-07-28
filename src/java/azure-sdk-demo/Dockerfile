FROM maven:3.6.3-adoptopenjdk-8 AS build
WORKDIR ./
COPY src /usr/src/app/src
COPY pom.xml /usr/src/app
COPY src/main/resources/ /usr/src/app/
RUN mvn -f /usr/src/app/pom.xml clean package

FROM gcr.io/distroless/java
COPY --from=build /usr/src/app/ /usr/src/app/
COPY --from=build /usr/src/app/target/sdkdemo.jar /usr/src/app/sdkdemo.jar
ENTRYPOINT ["java", "-jar", "/usr/src/app/sdkdemo.jar"]