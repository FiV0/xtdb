package xtdb.arrow

import clojure.lang.Keyword
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import xtdb.vector.extensions.KeywordType

class KeywordVector(override val inner: Utf8Vector): ExtensionVector() {
    override val arrowField: Field = Field(name, FieldType(nullable, KeywordType, null), emptyList())

    override fun getObject0(idx: Int) = Keyword.intern(inner.getObject0(idx))

    override fun writeObject0(value: Any) = when(value) {
        is Keyword -> inner.writeObject(value.sym.toString())
        else -> TODO("promotion")
    }
}