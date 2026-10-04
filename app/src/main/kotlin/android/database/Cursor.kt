package android.database

import java.io.Closeable

/** The read side of Android's Cursor that the ported code uses. */
interface Cursor : Closeable {
    val count: Int
    val position: Int
    val columnNames: Array<String>
    fun moveToFirst(): Boolean
    fun moveToNext(): Boolean
    fun moveToPosition(position: Int): Boolean
    fun getColumnIndex(columnName: String): Int
    fun getColumnIndexOrThrow(columnName: String): Int
    fun getString(columnIndex: Int): String?
    fun getLong(columnIndex: Int): Long
    fun getInt(columnIndex: Int): Int
    fun isNull(columnIndex: Int): Boolean
}

/** An in-memory cursor, as Android's MatrixCursor. */
class MatrixCursor(override val columnNames: Array<String>) : Cursor {
    private val rows = ArrayList<List<Any?>>()
    override var position: Int = -1
        private set

    fun addRow(values: List<Any?>) {
        require(values.size == columnNames.size) { "row has ${values.size} values for ${columnNames.size} columns" }
        rows += values
    }

    fun addRow(values: Array<Any?>) = addRow(values.toList())

    override val count: Int get() = rows.size
    override fun moveToFirst(): Boolean = moveToPosition(0)
    override fun moveToNext(): Boolean = moveToPosition(position + 1)
    override fun moveToPosition(position: Int): Boolean {
        this.position = position.coerceIn(-1, rows.size)
        return position in rows.indices
    }
    override fun getColumnIndex(columnName: String): Int = columnNames.indexOf(columnName)
    override fun getColumnIndexOrThrow(columnName: String): Int =
        getColumnIndex(columnName).also { require(it >= 0) { "column '$columnName' does not exist" } }
    private fun value(columnIndex: Int): Any? = rows[position][columnIndex]
    override fun getString(columnIndex: Int): String? = value(columnIndex)?.toString()
    override fun getLong(columnIndex: Int): Long = (value(columnIndex) as? Number)?.toLong() ?: value(columnIndex)?.toString()?.toLongOrNull() ?: 0L
    override fun getInt(columnIndex: Int): Int = getLong(columnIndex).toInt()
    override fun isNull(columnIndex: Int): Boolean = value(columnIndex) == null
    override fun close() = Unit
}
