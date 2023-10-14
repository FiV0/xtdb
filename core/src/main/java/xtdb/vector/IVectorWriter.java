package xtdb.vector;

import clojure.lang.Keyword;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.types.pojo.Field;

public interface IVectorWriter extends IValueWriter, AutoCloseable {

    /**
     * <p> Maintains the next position to be written to. </p>
     *
     * <p> Automatically incremented by the various `write` methods, and any {@link IVectorWriter#rowCopier}s. </p>
     */
    IVectorPosition writerPosition();

    ValueVector getVector();

    @Deprecated
    Object getColType();
    Field getField();

    /**
     * This method calls {@link ValueVector#setValueCount} on the underlying vector, so that all of the values written
     * become visible through the Arrow Java API - we don't call this after every write because (for composite vectors, and especially unions)
     * it's not the cheapest call.
     */
    default void syncValueCount() {
        getVector().setValueCount(writerPosition().getPosition());
    }

    IRowCopier rowCopier(ValueVector srcVector);

    /**
     * won't create
     */
    @Override
    IVectorWriter structKeyWriter(String key);

    @Override
    @Deprecated
    IVectorWriter structKeyWriter(String key, Object colType);

    /**
     * will create
     */
    @Override
    IVectorWriter structKeyWriter(Field field);

    @Override
    @Deprecated
    IVectorWriter writerForType(Object colType);

    /**
     * will not create a leg if it doesn't exist
     */
    IVectorWriter writerForLeg(Keyword leg);

    @Override
    @Deprecated
    IVectorWriter writerForTypeId(byte typeId);

    /**
     * will create a leg if it doesn't exist
     */
    @Override
    IVectorWriter writerForField(Field field);

    @Override
    IVectorWriter listElementWriter();

    void clear();

    @Override
    default void close() {
        getVector().close();
    }
}
