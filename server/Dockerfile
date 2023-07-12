FROM  makarius/isabelle:Isabelle2022
# https://hub.docker.com/r/makarius/isabelle

ARG SCALA_VERSION
ENV SCALA_VERSION ${SCALA_VERSION:-2.13.10}

USER root

RUN apt-get update && \
    apt-get install -y vim less curl wget rsync openssh-client git rpm unzip zip \
                       apt-transport-https libfontconfig1 libgomp1 pwgen build-essential && \
    apt-get clean

# USER isabelle
# RUN curl -fsL https://isabelle.in.tum.de/dist/Isabelle2022_linux.tar.gz > Isabelle.tar.gz
# RUN tar xzf Isabelle.tar.gz && rm Isabelle.tar.gz && mv Isabelle2022 Isabelle
RUN sed -i -e 's@ISABELLE_HOME_USER=.*@ISABELLE_HOME_USER="/isa"@g' Isabelle/etc/settings
# RUN Isabelle/bin/isabelle build -b HOL
# USER root
RUN ln -sT /home/isabelle/Isabelle/bin/isabelle /usr/local/bin/isabelle


# Install Java 17 from Eclipse Adoptium.
RUN mkdir -p /etc/apt/keyrings
RUN curl -fs https://packages.adoptium.net/artifactory/api/gpg/key/public | tee /etc/apt/keyrings/adoptium.asc
RUN echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb \
    $(awk -F= '/^VERSION_CODENAME/{print$2}' /etc/os-release) main" | tee /etc/apt/sources.list.d/adoptium.list
RUN apt-get update && \
    apt-get install -y temurin-17-jdk && \
    apt-get clean

# Install Scala (and warm up cache).
RUN curl -fsL https://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.tgz | tar xfz - -C /usr/share && \
    mv /usr/share/scala-* /usr/share/scala && chown -R root:root /usr/share/scala && chmod -R 755 /usr/share/scala && \
    ln -s /usr/share/scala/bin/* /usr/local/bin && \
    echo "println(util.Properties.versionMsg)" > test.scala && scala test.scala && rm test.scala

# Install mill (alternative build system for scala, simpler than sbt; also warm up cache).
RUN curl -fsL https://github.com/com-lihaoyi/mill/releases/download/0.11.1/0.11.1 > /usr/local/bin/mill && \
    chmod ugo+x /usr/local/bin/mill && \
    touch build.sc && mill -i resolve _ && rm build.sc

RUN mkdir /qisabelle-server
WORKDIR /qisabelle-server

# Cache dependencies.
COPY build.sc .
RUN mill app.compile

# Compile
COPY app/ app/
RUN mill app.compile
RUN mill app.assembly
RUN mill show app.assembly
RUN mill app.test.compile
# RUN mill app.test

# When running inside Docker, listen to all interfaces, Docker determines real access.
ENV QISABELLE_HOST="0.0.0.0"
ENV QISABELLE_PORT=80
EXPOSE 80

ENTRYPOINT []
CMD ["java", "-jar", "out/app/assembly.dest/out.jar"]