# Execute na raiz da APS:
# docker build -t aps-manancial:1.1.0 ./servicos/manancial

# Compila, executa os testes e separa as dependencias de execucao.
FROM maven:3.9.16-eclipse-temurin-17 AS build
WORKDIR /build

COPY pom.xml ./
RUN mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.8.1:go-offline

COPY src/ ./src/
RUN mvn -B -ntp package org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/dependencias

# A imagem final recebe somente o JRE, a aplicacao e suas bibliotecas.
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
    && mkdir -p /app/dados \
    && chown app:app /app/dados

COPY --from=build /build/target/manancial-1.0-SNAPSHOT.jar /app/manancial.jar
COPY --from=build /build/dependencias/ /app/lib/

ENV APS_HOST=0.0.0.0 APS_PORT=8081 APS_DB=/app/dados/aps.db

USER 10001:10001
EXPOSE 8081

# APS_DB define o arquivo persistente sem depender dos arquivos-fonte.
# APS_HOST permite receber conexoes pela interface de rede do container.
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-cp", "/app/manancial.jar:/app/lib/*", "br.edu.aps.manancial.Api"]
