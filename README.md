# QIsabelle
QIsabelle aims to give a simple, reproducible environment for evaluating machine-learning models with the [Isabelle](https://isabelle.in.tum.de/) proof assistant.

This is a mini version of [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle),
a Python interface to the Isabelle proof assistant by Albert Qiaochu Jiang, Wenda Li, Jesse Michael Han, and Yuhuai Wu.
Both PISA and QIsabelle rely on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle) by Dominique Unruh.


## Setup
### 1. Clone the repo
```bash
git clone git@github.com:marcinwrochna/qisabelle.git
```
or (if you don't have SSH keys set up with GitHub):
```bash
git clone https://github.com/marcinwrochna/qisabelle.git
```

### 2. Download heaps
A heap is a saved memory state of the Isabelle/ML process, usually after fully executing an Isabelle [session](https://isabelle.in.tum.de/doc/system.pdf).
They are large, about 40GB decompressed, too large to be included in a docker image, so pre-built heaps of all of [AFP](https://www.isa-afp.org/) are available for download.

By default, QIsabelle uses heaps from the main 2023 (September) AFP release (`2023_01bf5fad3e59`).
(Alternatively, you can choose a different one from [this page](https://u363828-sub1:7K5XEQ7RDqvbjY8v@u363828-sub1.your-storagebox.de/)
and modify the `.env` file accordingly,
or building a heap yourself from any AFP version: see *Building your own heap* below).

```bash
    cd qisabelle
    source .env
    echo $AFP_ID
    # Download the AFP release (.thy files, including files generated during heap building).
    curl -u u363828-sub1:7K5XEQ7RDqvbjY8v https://u363828-sub1.your-storagebox.de/afp_$AFP_ID.tar.gz -O
    tar -xf afp_$AFP_ID.tar.gz
    rm afp_$AFP_ID.tar.gz
    mkdir dockerheaps
    cd dockerheaps
    # Download compressed heaps (7GB, then 40GB more for decompression).
    curl -u u363828-sub1:7K5XEQ7RDqvbjY8v https://u363828-sub1.your-storagebox.de/Isabelle2023_afp_$AFP_ID.tar.br -O
    tar --use-compress-program=brotli -xf Isabelle2023_afp_$AFP_ID.tar.br
    rm Isabelle2023_afp_$AFP_ID.tar.br
    cd ..
```
### 3. Start the server and client
On port 17000 (change `docker-compose.yaml` to change the port or to add more replicas):
```bash
    docker-compose up
```
To start the Python client, in another console, run:
```bash
    python -um client.main
```

In case of permission errors, use `chown -R 1000:1000` on heaps or `chmod -R a+rwX` on AFP.

The client by default runs 600 tests (which takes hours even on a powerful computing server) with a model that just uses [Sledgehammer](https://isabelle.in.tum.de/dist/Isabelle2023/doc/sledgehammer.pdf) at every step.

<!-- According to the paper, it should give ~154 out of 600 tests passing for just running the hammer. -->
Currently on default settings the results for this model are:
* success: 179
* timeout-soft: 118, timeout-mid: 1, timeout-hard: 283 (hammer timeouts)
* execution-timeout: 6 (proof, or theory up to lemma statement, takes too long to execute)
* failed-proof: 1 (proof given by hammer fails)
* not_found: 10, no_such_file: 1  (test mismatch with the version of AFP we have).

With larger hammer timeouts (60s) we can get to ~203 successes, so this varies with computing power.

## API
The server provides a HTTP API defined and documented in `server/src/QIsabelleServer.scala`.
It has JSON dict as inputs and outputs.
It should be easy to use from any language, see `client/proxy.py` for a Python wrapper and `client/main.py` for usage examples.

## Heaps – details
Pre-built heaps for QIsabelle are mounted read-only (for reproducibility), as system heaps (at `/home/isabelle/Isabelle/heaps/` inside the docker container),
in order to keep user heaps writable (at `/home/isabelle/.isabelle/heaps/`).

Note that heaps include absolute paths, unfortunately, so they cannot be moved around. This means:
* Heaps downloaded from here can be placed anywhere as long as you mount them as `/home/isabelle/Isabelle/heaps/` in a docker container.
* If you want to use downloaded heaps without docker, you will need to place them at `/home/isabelle/Isabelle/heaps/`.
* Heaps you built yourself (if you use Isabelle) cannot be used with QIsabelle, unless you built them at `/home/isabelle/Isabelle/heaps/`.

### Building your own heap
1. Clone the latest version of AFP (~700MB temporarily) and take just the theory files (~300MB).
You can also select a specific tag, branch, or revision (see [here](https://foss.heptapod.net/isa-afp/afp-devel/-/commits/)) using `hg clone -r Isabelle2023`.
```bash
    hg clone https://foss.heptapod.net/isa-afp/afp-devel
    cd afp-devel
    export AFP_ID=$(hg log -l 1 --template '{date|shortdate}_{node|short}\n' -r .)
    echo $AFP_ID
    hg archive -I "thys/" -I "etc/" ../afp_$AFP_ID/
    cd ..
    rm -r afp-devel
```

2. Build all of AFP as system heaps. This takes ~5h on a powerful server and produces ~40GB.
Timeout errors are normal, just repeat the command to retry failed sessions.
You can Ctrl+C and restart to continue at any time.
Note this will modify the AFP thys directory (some theories generate code);
if you mount it as read-only, a few theories will fail (which is OK).
The `-j` option specifies the number of parallel workers, more than 30 is probably waste.
```bash
    mkdir -p dockerheaps/Isabelle2023_afp_$AFP_ID
    chmod a+rwx dockerheaps/Isabelle2023_afp_$AFP_ID
    chmod -R a+rwX afp_$AFP_ID/
    docker run -it --rm \
        -v $(pwd)/afp_$AFP_ID:/afp \
        -v $(pwd)/dockerheaps/Isabelle2023_afp_$AFP_ID:/home/isabelle/Isabelle/heaps \
        qisabelle-server \
        isabelle build -b \
        -o system_heaps=true \
        -j 30 -o timeout_scale=3 \
        -D /afp/thys
```
You can use `-D /afp/thys/Hello_World` for testing (~7 min, 370MB of heaps)

3. Optionally, compress and upload the heaps (and modified theories). This takes a few hours.
```bash
tar --gzip -cf afp_$AFP_ID.tar.gz afp_$AFP_ID/
cd dockerheaps
tar -cf Isabelle2023_afp_$AFP_ID.tar Isabelle2023_afp_$AFP_ID/
brotli -q 5 --rm Isabelle2023_afp_$AFP_ID.tar
cd ..
scp afp_$AFP_ID.tar.gz dockerheaps/Isabelle2023_afp_$AFP_ID.tar.br hetzner:isabelle_heaps/
rm afp_$AFP_ID.tar.gz dockerheaps/Isabelle2023_afp_$AFP_ID.tar.br
```
<!-- Gzip is 11GB in ? ; quality 5 is 8.6GB in 25 min; quality 7 is 8.4G in 1h ; quality 9 is 8.3G in 5h30; quality 11 is ? in >36h -->

## Development
### Client requirements
The Python client only uses standard libraries with Python >=3.10.

It is recommended to use mypy and ruff (or black, isort, flake8) for development.

### Building the server docker image
```bash
    docker-compose build
```

### Scala-Isabelle development version
At the moment to work locally (without docker) you will need to install JDK 17, [SBT](https://www.scala-sbt.org/1.x/docs/Installing-sbt-on-Linux.html), and use the git version of [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle):
```bash
    cd ..
    git clone https://github.com/dominique-unruh/scala-isabelle.git
    cd scala-isabelle
    sbt publishLocal
    cd ../qisabelle
```

### VS Code
Most of the server development can easily be done with VS Code with the "Scala (Metals)" extension installed.
You may need to open `build.sc` to trigger project building and then `server/test/src/IsabelleSessionTests.scala` to make the test appear in the test explorer.

### With you own version of scala-isabelle
You can clone [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle), modify it and built it locally using `sbt publishLocal`.
Then change the version in QIsabelle's `build.sc` to `scala-isabelle:master-SNAPSHOT`.
You will also need to modify `ServerDockerfile` if you want to build it with a modified scala-isabelle.
