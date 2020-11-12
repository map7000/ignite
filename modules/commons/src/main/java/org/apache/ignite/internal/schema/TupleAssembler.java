/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.schema;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.UUID;

/**
 * Utility class to build tuples using column appending pattern. The external user of this class must consult
 * with the schema and provide the columns in strict internal column sort order during the tuple construction.
 * Additionally, the user of this class must pre-calculate the
 */
public class TupleAssembler {
    /** */
    private final SchemaDescriptor schema;

    /** The number of non-null varlen columns in values chunk. */
    private final int nonNullVarlenValCols;

    /** Target byte buffer to write to. */
    private final ExpandableByteBuf buf;

    /** Current columns chunk. */
    private Columns curCols;

    /** Current field index (the field is unset). */
    private int curCol;

    /** Index of the current varlen table entry. Incremented each time non-null varlen column is appended. */
    private int curVarlenTblEntry;

    /** Current offset for the next column to be appended. */
    private int curOff;

    /** Base offset of the current chunk */
    private int baseOff;

    /** Offset of the null map for current chunk. */
    private int nullMapOff;

    /** Offset of the varlen table for current chunk. */
    private int varlenTblOff;

    /** Charset encoder for strings. Initialized lazily. */
    private CharsetEncoder strEncoder;

    /**
     * @param nonNullVarsizeCols Number of non-null varlen columns.
     * @return Total size of the varlen table.
     */
    public static int varlenTableSize(int nonNullVarsizeCols) {
        return nonNullVarsizeCols * 2;
    }

    /**
     * This implementation is not tolerant to malformed char sequences.
     */
    public static int utf8EncodedLength(CharSequence seq) {
        int cnt = 0;

        for (int i = 0, len = seq.length(); i < len; i++) {
            char ch = seq.charAt(i);

            if (ch <= 0x7F)
                cnt++;
            else if (ch <= 0x7FF)
                cnt += 2;
            else if (Character.isHighSurrogate(ch)) {
                cnt += 4;
                ++i;
            }
            else
                cnt += 3;
        }

        return cnt;
    }

    /**
     */
    public static int tupleChunkSize(Columns cols, int nonNullVarsizeCols, int nonNullVarsizeSize) {
        int size = Tuple.TOTAL_LEN_FIELD_SIZE + Tuple.VARSIZE_TABLE_LEN_FIELD_SIZE +
            varlenTableSize(nonNullVarsizeCols) + cols.nullMapSize();

        for (int i = 0; i < cols.numberOfFixsizeColumns(); i++)
            size += cols.column(i).type().length();

        return size + nonNullVarsizeSize;
    }

    /**
     * @param schema Tuple schema.
     * @param size Target tuple size. If the tuple size is known in advance, it should be provided upfront to avoid
     *      unnccessary arrays copy.
     * @param nonNullVarsizeKeyCols Number of null varlen columns in key chunk.
     * @param nonNullVarlenValCols Number of null varlen columns in value chunk.
     */
    public TupleAssembler(
        SchemaDescriptor schema,
        int size,
        int nonNullVarsizeKeyCols,
        int nonNullVarlenValCols
    ) {
        this.schema = schema;

        this.nonNullVarlenValCols = nonNullVarlenValCols;

        buf = new ExpandableByteBuf(size);

        curCols = schema.columns(0);

        initOffsets(Tuple.SCHEMA_VERSION_FIELD_SIZE + Tuple.KEY_HASH_FIELD_SIZE, nonNullVarsizeKeyCols);

        buf.putShort(0, (short)schema.version());
    }

    /**
     */
    public static int tupleSize(
        Columns keyCols,
        int nonNullVarsizeKeyCols,
        int nonNullVarsizeKeySize,
        Columns valCols,
        int nonNullVarsizeValCols,
        int nonNullVarsizeValSize
    ) {
        return Tuple.SCHEMA_VERSION_FIELD_SIZE + Tuple.KEY_HASH_FIELD_SIZE +
            tupleChunkSize(keyCols, nonNullVarsizeKeyCols, nonNullVarsizeKeySize) +
            tupleChunkSize(valCols, nonNullVarsizeValCols, nonNullVarsizeValSize);
    }

    /**
     */
    public void appendNull() {
        Column col = curCols.column(curCol);

        if (!col.nullable())
            throw new IllegalArgumentException("Failed to set column (null was passed, but column is not nullable): " +
                col);

        setNull(curCol);

        shiftColumn(0, false);
    }

    /**
     */
    public void appendByte(byte val) {
        checkType(NativeType.BYTE);

        buf.put(curOff, val);

        shiftColumn(NativeType.BYTE);
    }

    /**
     */
    public void appendShort(short val) {
        checkType(NativeType.SHORT);

        buf.putShort(curOff, val);

        shiftColumn(NativeType.SHORT);
    }

    /**
     */
    public void appendInt(int val) {
        checkType(NativeType.INTEGER);

        buf.putInt(curOff, val);

        shiftColumn(NativeType.INTEGER);
    }

    /**
     */
    public void appendLong(long val) {
        checkType(NativeType.LONG);

        buf.putLong(curOff, val);

        shiftColumn(NativeType.LONG);
    }

    /**
     */
    public void appendFloat(float val) {
        checkType(NativeType.FLOAT);

        buf.putFloat(curOff, val);

        shiftColumn(NativeType.FLOAT);
    }

    /**
     */
    public void appendDouble(double val) {
        checkType(NativeType.DOUBLE);

        buf.putDouble(curOff, val);

        shiftColumn(NativeType.DOUBLE);
    }

    /**
     */
    public void appendUuid(UUID uuid) {
        checkType(NativeType.UUID);

        buf.putLong(curOff, uuid.getLeastSignificantBits());
        buf.putLong(curOff + 8, uuid.getMostSignificantBits());

        shiftColumn(NativeType.UUID);
    }

    /**
     */
    public void appendString(String val) {
        checkType(NativeType.STRING);

        try {
            int written = buf.putString(curOff, val, encoder());

            writeOffset(curVarlenTblEntry, curOff - baseOff);

            shiftColumn(written, true);
        }
        catch (CharacterCodingException e) {
            throw new AssemblyException("Failed to encode string", e);
        }
    }

    /**
     */
    public void appendBytes(byte[] val) {
        checkType(NativeType.BYTES);

        buf.putBytes(curOff, val);

        writeOffset(curVarlenTblEntry, curOff - baseOff);

        shiftColumn(val.length, true);
    }

    /**
     */
    public void appendBitmask(BitSet bitSet) {
        Column col = curCols.column(curCol);

        checkType(NativeTypeSpec.BITMASK);

        Bitmask maskType = (Bitmask)col.type();

        if (bitSet.length() > maskType.bits())
            throw new IllegalArgumentException("Failed to set bitmask for column '" + col.name() + "' " +
                "(mask size exceeds allocated size) [mask=" + bitSet + ", maxSize=" + maskType.bits() + "]");

        byte[] arr = bitSet.toByteArray();

        buf.putBytes(curOff, arr);

        for (int i = 0; i < maskType.length() - arr.length; i++)
            buf.put(curOff + arr.length + i, (byte)0);

        shiftColumn(maskType);
    }

    /**
     */
    public byte[] build() {
        return buf.toArray();
    }

    /**
     * @return UTF-8 string encoder.
     */
    private CharsetEncoder encoder() {
        if (strEncoder == null)
            strEncoder = StandardCharsets.UTF_8.newEncoder();

        return strEncoder;
    }

    /**
     * Writes the given offset to the varlen table entry with the given index.
     *
     * @param tblEntryIdx Varlen table entry index.
     * @param off Offset to write.
     */
    private void writeOffset(int tblEntryIdx, int off) {
        buf.putShort(varlenTblOff + 2 * tblEntryIdx, (short)off);
    }

    /**
     * Checks that the type being appended matches the column type.
     *
     * @param type Type spec that is attempted to be appended.
     */
    private void checkType(NativeTypeSpec type) {
        Column col = curCols.column(curCol);

        if (col.type().spec() != type)
            throw new IllegalArgumentException("Failed to set column (int was passed, but column is of different " +
                "type): " + col);
    }

    /**
     * Checks that the type being appended matches the column type.
     *
     * @param type Type that is attempted to be appended.
     */
    private void checkType(NativeType type) {
        checkType(type.spec());
    }

    /**
     * Sets null flag in the null map for the given column.
     * @param colIdx Column index.
     */
    private void setNull(int colIdx) {
        int byteInMap = colIdx / 8;
        int bitInByte = colIdx % 8;

        buf.put(nullMapOff + byteInMap, (byte)(buf.get(nullMapOff + byteInMap) | (1 << bitInByte)));
    }

    /**
     * Must be called after an append of fixlen column.
     * @param type Type of the appended column.
     */
    private void shiftColumn(NativeType type) {
        assert type.spec().fixedLength() : "Varlen types should provide field length to shift column: " + type;

        shiftColumn(type.length(), false);
    }

    /**
     * Shifts current offsets and column indexes as necessary, also changes the chunk base offsets when
     * moving from key to value columns.
     *
     * @param size Size of the appended column.
     * @param varlen {@code true} if appended column was varlen.
     */
    private void shiftColumn(int size, boolean varlen) {
        curCol++;
        curOff += size;

        if (varlen)
            curVarlenTblEntry++;

        if (curCol == curCols.length()) {
            Columns cols = schema.columns(curCol);

            int keyLen = curOff - baseOff;

            buf.putShort(baseOff, (short)keyLen);

            if (cols == curCols)
                return;

            curCols = cols;

            initOffsets(baseOff + keyLen, nonNullVarlenValCols);
        }
    }

    /**
     * @param base Chunk base offset.
     * @param nonNullVarlenCols Number of non-null varlen columns.
     */
    private void initOffsets(int base, int nonNullVarlenCols) {
        baseOff = base;

        curCol = 0;
        curVarlenTblEntry = 0;

        buf.putShort(baseOff + Tuple.TOTAL_LEN_FIELD_SIZE, (short)nonNullVarlenCols);

        varlenTblOff = baseOff + Tuple.TOTAL_LEN_FIELD_SIZE + Tuple.VARSIZE_TABLE_LEN_FIELD_SIZE;
        nullMapOff = varlenTblOff + varlenTableSize(nonNullVarlenCols);
        curOff = nullMapOff + curCols.nullMapSize();
    }
}