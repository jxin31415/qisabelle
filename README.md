# QIsabelle
This is a mini version of [PISA](https://github.com/albertqjiang/Portal-to-ISAbelle),
a Python interface to the Isabelle proof assistant by Albert Qiaochu Jiang, Wenda Li, Jesse Michael Han, and Yuhuai Wu,
Both PISA and QIsabelle rely on [scala-isabelle](https://github.com/dominique-unruh/scala-isabelle) by Dominique Unruh.

QIsabelle only aims to give a simple, reproducible environment for evaluating ML models (without dataset extraction).

## Usage
```bash
git clone git@github.com:marcinwrochna/qisabelle.git
cd qisabelle
docker build server -t qisabelle-server
docker run -it --rm --name qisabelle-server \
    -p 127.0.0.1:17000:80 \
    -v /home/mwrochna/projects/play/afp-2023-03-16:/afp \
    -v /home/mwrochna/projects/play/heap/:/isa \
    qisabelle-server \
    /home/isabelle/Isabelle/bin/isabelle build -b -j 50 -D /afp/thys/Hello_World
```
(Note that we mount a directory like .isabelle/Isabelle2022 to /isa, but the internal files have absolute paths in them).