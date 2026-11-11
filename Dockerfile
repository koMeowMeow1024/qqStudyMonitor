ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre

FROM ${MAVEN_IMAGE} AS build
WORKDIR /build

RUN mkdir -p /root/.m2 && printf '%s' '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/public</url></mirror></mirrors></settings>' > /root/.m2/settings.xml

COPY pom.xml ./
COPY src ./src

RUN --mount=type=cache,target=/root/.m2/repository mvn -B clean package -DskipTests

FROM ${JRE_IMAGE}
WORKDIR /app

ENV TZ=Asia/Shanghai
ENV LANG=C.UTF-8

COPY --from=build /build/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dfile.encoding=UTF-8", "-jar", "/app/app.jar"]
