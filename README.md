# QIsabelle
This is a mini version of [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle),
a Python interface to the Isabelle proof assistant by Albert Qiaochu Jiang, Wenda Li, Jesse Michael Han, and Yuhuai Wu,
Both PISA and QIsabelle rely on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle) by Dominique Unruh.

QIsabelle only aims to give a simple, reproducible environment for evaluating ML models (without dataset extraction).

## Usage
A heap is a saved memory state of the Isabelle/ML process, usually after fully executing
an Isabelle [session](https://isabelle.in.tum.de/doc/system.pdf).
The dockerized Isabelle looks for system heaps in `/home/isabelle/Isabelle/heaps/`
and for user heaps in `/home/isabelle/.isabelle/heaps/`.
Unfortunately heaps include absolute paths, so they cannot be moved around
(if you want to use pre-built heaps outside of docker, they'll need to be in `/home/isabelle`).
To make builds more reproducible, we mount pre-built heaps of all AFP as read-only
(they're too large to include in the docker image).
This allows to use user heaps as a writable cache (you can mount it if you want; the mounted folder needs `chown 1000:1000`).
When pre-building AFP, system heaps should be writeable, and AFP code unfortunately too (a few theories generate code, even if it's already there).

```bash
    git clone git@github.com:marcinwrochna/qisabelle.git
    cd qisabelle
    docker build -f ServerDockerfile -t qisabelle-server .
    # Choose, download and unpack a pre-built AFP heap from https://u363828-sub1:7K5XEQ7RDqvbjY8v@u363828-sub1.your-storagebox.de/
    # Note that a 3.5G heap.tar.br (compressed with brotli max settings) is 39G unpacked (11G gzipped).
    curl -u u363828-sub1:7K5XEQ7RDqvbjY8v https://u363828-sub1.your-storagebox.de/Isabelle2022_afp-2023-03-16.tar.br -O
    brotli -d Isabelle2022_afp-2023-03-16.tar.br
    tar -xf Isabelle2022_afp-2023-03-16.tar
    # Build all of AFP as a system heap. This takes ~5h on a powerful server.
    # Timeout errors are normal, just repeat the command to retry failed sessions.
    # You can Ctrl+C and restart to continue at any time.
    docker run -it --rm \
        -p 127.0.0.1:17000:17000 \
        -v /home/mwrochna/projects/play/afp-2023-03-16:/afp \
        -v /home/mwrochna/projects/play/dockerheaps/Isabelle2022_afp-2023-03-16:/home/isabelle/Isabelle/heaps \
        qisabelle-server \
        isabelle build -b \
        -o system_heaps=true \
        -j 30 -o timeout_scale=3 \
        -D /afp/thys
        # -D /afp/thys/Hello_World
    # Start the server:
    docker run -it --rm --name qisabelle-server \
        -p 127.0.0.1:17000:17000 \
        -v /home/mwrochna/projects/play/afp-2023-03-16:/afp:ro \
        -v /home/mwrochna/projects/play/dockerheaps/Isabelle2022_afp-2023-03-16:/home/isabelle/Isabelle/heaps:ro \
        qisabelle-server | tee server.log
    # Start the client, in another console:
    python -um client.main | tee client.log
```
Accordign to the paper, it should give ~154 out of 600 tests passing.
Before fixing theories, on default settings, this gave me 161 OK, 63 AssertionError (mostly 'Theory loader: undefined entry for theory'), 376 timeouts out of 600 tests.
Currently, on default settings, this gives me 189 success, 189+202 timeouts, 1 no_such_file,  and 19 exceptions (of which some are ML timeouts) out of 600 tests.
With a larger external timeout of 60s: {'timeout': 315, 'success': 203, 'timeout2': 64, 'no_such_file2': 1, 'timeout3': 6, 'exception': 11})



## With you own version of scala-isabelle
Change the scala-isabelle version in build.sc to `scala-isabelle:master-SNAPSHOT`
and run `sbt publishLocal` in your clone of the `scala-isabelle` git repo.