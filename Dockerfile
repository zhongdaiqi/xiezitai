# 构建阶段
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# 运行阶段
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/xiezitai.jar app.jar
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
