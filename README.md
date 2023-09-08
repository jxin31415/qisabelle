# QIsabelle
This is a mini version of [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle),
a Python interface to the Isabelle proof assistant by Albert Qiaochu Jiang, Wenda Li, Jesse Michael Han, and Yuhuai Wu.
Both PISA and QIsabelle rely on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle) by Dominique Unruh.

QIsabelle aims to give a simple, reproducible environment for evaluating machine-learning models.

## Usage
A heap is a saved memory state of the Isabelle/ML process, usually after fully executing
an Isabelle [session](https://isabelle.in.tum.de/doc/system.pdf).
They are too large to be included in a docker image, so pre-built heaps of all of AFP are available for download (see below for building them by yourself).
Then they are mounted read-only (for reproducibility), as system heaps (at `/home/isabelle/Isabelle/heaps/` inside the docker container), in order to to keep user heaps writable (at `/home/isabelle/.isabelle/heaps/`).

Note that heaps include absolute paths, unfortunately, so they cannot be moved around. This means:
* Heaps downloaded from here can be placed anywhere as long as you mount them as `/home/isabelle/Isabelle/heaps/` in a docker container.
* If you want to use downloaded heaps without docker, you will need to place them at `/home/isabelle/Isabelle/heaps/`.
* Heaps you built yourself (if you use Isabelle) cannot be used with QIsabelle, unless you built them at `/home/isabelle/Isabelle/heaps/`.

In case of permission errors, use `chown 1000:1000` on mounted folders.

```bash
    git clone git@github.com:marcinwrochna/qisabelle.git
    cd qisabelle
    docker build -f ServerDockerfile -t qisabelle-server .
    # Choose, download and unpack a pre-built AFP heap from https://u363828-sub1:7K5XEQ7RDqvbjY8v@u363828-sub1.your-storagebox.de/
    # Note that a 3.5GB heap.tar.br (compressed with brotli max settings) is 39GB after unpacking (11GB gzipped).
    export AFP_ID=2023-03-16_f323a37f60a5
    curl -u u363828-sub1:7K5XEQ7RDqvbjY8v https://u363828-sub1.your-storagebox.de/afp_$AFP_ID.tar.gz -O
    tar -xf afp_$AFP_ID.tar.gz
    rm afp_$AFP_ID.tar.gz
    mkdir dockerheaps
    cd dockerheaps
    curl -u u363828-sub1:7K5XEQ7RDqvbjY8v https://u363828-sub1.your-storagebox.de/Isabelle2022_afp_$AFP_ID.tar.br -O
    brotli -d Isabelle2022_afp_$AFP_ID.tar.br
    tar -xf Isabelle2022_afp_$AFP_ID.tar
    rm Isabelle2022_afp_$AFP_ID.tar Isabelle2022_afp_$AFP_ID.tar.br
    cd ..
    # Start the server:
    docker run -it --rm --name qisabelle-server \
        -p 127.0.0.1:17000:17000 \
        -v $(pwd)/afp_$AFP_ID:/afp:ro \
        -v $(pwd)/dockerheaps/Isabelle2022_afp_$AFP_ID:/home/isabelle/Isabelle/heaps:ro \
        qisabelle-server | tee server.log
    # Start the client, in another console:
    python -um client.main | tee client.log
```
According to the paper, it should give ~154 out of 600 tests passing for just running the hammer.
Currently on default settings the results are:
* success: 179
* timeout-soft: 119, timeout-mid: 1, timeout-hard: 283 (hammer timeouts)
* execution-timeout: 6
* exception: 1 (proof given by hammer fails)
* not_found: 10, no_such_file: 1  (test mismatch with the version of AFP we have).
With larger timeouts (60s) we can get to ~203 successes.


## Building your own heap
1. Clone the latest version of AFP (~700MB temporarily) and take just the theory files (~300MB).
You can also select a specific tag, branch, or revision (see [here](https://foss.heptapod.net/isa-afp/afp-devel/-/commits/)) using `hg clone -r Isabelle2022`.
```bash
    hg clone https://foss.heptapod.net/isa-afp/afp-devel
    cd afp-devel
    AFP_ID=$(hg log -l 1 --template '{date|shortdate}_{node|short}\n' -r .)
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
    mkdir dockerheaps/Isabelle2022_afp_$AFP_ID
    chmod a+wx dockerheaps/Isabelle2022_afp_$AFP_ID
    docker run -it --rm \
        -v $(pwd)/afp_$AFP_ID:/afp \
        -v $(pwd)/dockerheaps/Isabelle2022_afp_$AFP_ID:/home/isabelle/Isabelle/heaps \
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
tar -cf Isabelle2022_afp_$AFP_ID.tar Isabelle2022_afp_$AFP_ID/
brotli -j Isabelle2022_afp_$AFP_ID.tar
cd ..
scp afp_$AFP_ID.tar.gz dockerheaps/Isabelle2022_afp_$AFP_ID.tar.br hetzner:isabelle_heaps/
rm afp_$AFP_ID.tar.gz dockerheaps/Isabelle2022_afp_$AFP_ID.tar.br
```

## With you own version of scala-isabelle
You can clone scala-isabelle, modify it and built it locally using `sbt publishLocal`.
Then change the scala-isabelle version in QIsabelle's `build.sc` to `scala-isabelle:master-SNAPSHOT`.
You will also need to modify ServerDockerfile if you want to build it with a modified scala-isabelle.
