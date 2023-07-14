# QIsabelle
This is a mini version of [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle),
a Python interface to the Isabelle proof assistant by Albert Qiaochu Jiang, Wenda Li, Jesse Michael Han, and Yuhuai Wu,
Both PISA and QIsabelle rely on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle) by Dominique Unruh.

QIsabelle only aims to give a simple, reproducible environment for evaluating ML models (without dataset extraction).

## Usage
```bash
git clone git@github.com:marcinwrochna/qisabelle.git
cd qisabelle
docker build -f ServerDockerfile -t qisabelle-server .
# Check isabelle building:
docker run -it --rm --name qisabelle-server \
    -p 127.0.0.1:17000:80 \
    -v /home/mwrochna/projects/play/afp-2023-03-16:/afp \
    -v /home/mwrochna/projects/play/heap/:/isa \
    qisabelle-server \
    /home/isabelle/Isabelle/bin/isabelle \
    build -b -j 20 -o timeout_scale=2 -D /afp/thys/Hello_World
# Start the server:
docker run -it --rm --name qisabelle-server \
    -p 127.0.0.1:17000:80 \
    -v /home/mwrochna/projects/play/afp-2023-03-16:/afp \
    -v /home/mwrochna/projects/play/heap/:/isa \
    qisabelle-server | tee server.log
# In another tab, start the client:
python -um client.main | tee client.log
```
(Note that we mount a directory like .isabelle/Isabelle2022 to /isa, but the internal files have absolute paths in them).
On default settings, this should give 161 OK, 63 AssertionError (mostly 'Theory loader: undefined entry for theory'), 375 or 376 timeouts out of 600 tests.

## In VS Code
Because of a [bug](https://github.com/scalameta/metals/issues/5387) in Metals server 0.11.12,
at the moment you need to use the pre-release version of the Scala Metals extension and open
settings, find "metals server version" and set it to the
[latest snaphot version](https://scalameta.org/metals/docs/#latest-metals-server-versions).