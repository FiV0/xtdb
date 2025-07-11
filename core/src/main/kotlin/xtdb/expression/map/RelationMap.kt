package xtdb.expression.map

import xtdb.arrow.RelationReader
import java.util.List
import java.util.Map

interface RelationMap {
    fun buildFields(): Map<*, *>
    fun buildKeyColumnNames(): List<*>
    fun probeFields(): Map<*, *>
    fun probeKeyColumnNames(): List<*>
    fun buildFromRelation(inRelation: RelationReader): RelationMapBuilder
    fun probeFromRelation(inRelation: RelationReader): RelationMapProber
    fun getBuiltRelation(): RelationReader
}