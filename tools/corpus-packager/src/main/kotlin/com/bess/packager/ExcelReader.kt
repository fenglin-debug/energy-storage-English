package com.bess.packager

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** Normalization algorithm frozen by TDD §6.1. */
fun normalizeTerm(raw: String): String {
    var s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
    s = s.replace('’', '\'').replace('‘', '\'')
    s = s.replace('–', '-').replace('—', '-').replace('―', '-')
    s = s.lowercase(java.util.Locale.ROOT)
    s = s.replace(Regex("\\s+"), " ").trim()
    return s
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * Read-only xlsx reader. Opens via FileInputStream with READ_ONLY semantics;
 * never writes back cells, formats or metadata (TDD §7.3 gate).
 */
object ExcelReader {

    /** Returns rows (0-based) of a sheet, each row a list of trimmed cell strings. */
    fun readSheet(path: File, sheetName: String): List<List<String>> {
        FileInputStream(path).use { fis ->
            WorkbookFactory.create(fis).use { wb ->
                val sheet = wb.getSheet(sheetName)
                    ?: error(
                        "Sheet '$sheetName' not found in ${path.name}; " +
                            "sheets=${(0 until wb.numberOfSheets).map { wb.getSheetName(it) }}",
                    )
                val formatter = DataFormatter()
                val rows = mutableListOf<List<String>>()
                for (row in sheet) {
                    rows.add(rowToStrings(row, formatter))
                }
                return rows
            }
        }
    }

    private fun rowToStrings(row: Row, formatter: DataFormatter): List<String> {
        val lastCell = row.lastCellNum.toInt().coerceAtLeast(0)
        if (lastCell == 0) return emptyList()
        val out = ArrayList<String>(lastCell)
        for (i in 0 until lastCell) {
            val cell: Cell? = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL)
            val value = if (cell == null) {
                ""
            } else if (cell.cellType == CellType.STRING) {
                cell.stringCellValue
            } else {
                formatter.formatCellValue(cell)
            }
            out.add(value.trim())
        }
        return out
    }
}
