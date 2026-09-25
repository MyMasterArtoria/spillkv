# spillkv

A key-value server written in Java that speaks the Redis protocol, so you can talk to it
with `redis-cli` or any normal Redis client. The point of it is to hold more data than it
has memory for. Keys stay in a hash map in memory, but the values get appended to a log
file on disk, and only some of them are cached in memory at a time. When you ask for a key
whose value is not cached, the server reads it back from the file. I am building this as a
smaller version of an idea from my thesis, where a graph database keeps a small part of
each vertex in memory and pushes the rest to disk.

Right now it only prints a version banner. Nothing else works yet.

## What it will and will not do

It will handle the string commands (`GET`, `SET`, `DEL`, `EXPIRE`, `INCR`, and so on),
pipelining, expiry, crash recovery, and log compaction.

It will not do lists, hashes, sets, clustering, Lua scripts, pub/sub, or RESP3. Those are
a lot of work and they are not what I am trying to learn here.

## Plan

- [ ] **M0** Build setup: Maven wrapper, CI, README
- [ ] **M1** RESP protocol and an in-memory store
- [ ] **M2** Append-only log and crash recovery
- [ ] **M3** Keep values on disk with a memory budget
- [ ] **M4** Compaction and hint files
- [ ] **M5** Metrics, Docker, and benchmarks against Redis 7

## Build

You need JDK 21.

```bash
./mvnw -B verify
```
