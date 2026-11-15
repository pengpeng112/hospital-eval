FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
# 本网络环境直连 repo.maven.apache.org 不稳定（握手被中断），与宿主机 .m2/settings.xml
# 同口径改走阿里云公共仓库（仅构建期使用，不含任何私有凭据）
RUN mkdir -p /root/.m2 && printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' \
    '  <mirrors>' \
    '    <mirror>' \
    '      <id>aliyunmaven</id>' \
    '      <mirrorOf>*</mirrorOf>' \
    '      <name>aliyun public</name>' \
    '      <url>https://maven.aliyun.com/repository/public</url>' \
    '    </mirror>' \
    '  </mirrors>' \
    '</settings>' > /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /app/target/hospital-eval-server.jar app.jar
EXPOSE 48080
ENTRYPOINT ["java","-Xms512m","-Xmx2g","-Dfile.encoding=UTF-8","-jar","/app/app.jar"]
