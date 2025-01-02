package xtdb

import java.nio.file.Path

interface IEvictBufferTest {
    fun evictCachedBuffer(k: Path)
}