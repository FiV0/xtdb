package xtdb.cache

interface Stats {
    val pinnedBytes: Long
    val evictableBytes: Long
    val freeBytes: Long
}