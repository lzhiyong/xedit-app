# piecetable：编辑器集成

> [piecetable](../piecetable/README_CN.md) 模块文档 · [English](piecetable_integration.md)

缓冲区只是数据结构，不是编辑器。下面四件事应该由持有它的那一层负责：编辑失败后的恢复、
校验来自 UI 的位置、不要每帧都复制超长行、给后台任务提供快照。一个类把四件事都做了：

```kotlin
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import x.github.module.piecetable.*
import x.github.module.piecetable.common.*

class EditorDocument(private val file: File) {

    /** 每个文档一把锁：缓冲区之间不共享状态，文档之间从不互相等待 */
    private val lock = ReentrantReadWriteLock()

    @Volatile
    var buffer: PieceTreeTextBuffer = load()
        private set

    /** 反向编辑，最新的在末尾；无论用什么撤销管理器，恢复时都必须清空 */
    private val undoStack = ArrayDeque<List<SingleEditOperation>>()

    /** 例如提示「文档已从磁盘重新加载」，并整体重绘 */
    var onRecovered: (Throwable) -> Unit = {}

    private fun load(): PieceTreeTextBuffer {
        val builder = PieceTreeTextBufferBuilder()
        file.bufferedReader().use { reader ->
            val chars = CharArray(64 * 1024)
            while (true) {
                val n = reader.read(chars)
                if (n <= 0) break
                builder.acceptChunk(String(chars, 0, n))
            }
        }
        return builder.build(EndOfLine.LF, normalizeEOL = true)
    }

    // ------------------------------------------------------------- 1. 编辑与恢复

    /** 应用 [ops]。编辑失败且文档已重建时返回 null。 */
    fun edit(ops: List<SingleEditOperation>): ApplyEditsResult? = lock.write {
        try {
            val result = buffer.applyEdits(ops, false, true)
            undoStack.addLast(result.reverseEdits!!.map { SingleEditOperation(it.range, it.text) })
            result
        } catch (e: IllegalArgumentException) {
            // 只会在第一处修改之前的校验阶段抛出（区间重叠）：调用方的 bug，缓冲区完好
            throw e
        } catch (e: Throwable) {
            // 其他异常都可能发生在树操作的中途
            recover(e)
            null
        }
    }

    // 调用时已持有写锁
    private fun recover(cause: Throwable) {
        // 失败的编辑只可能损坏这个缓冲区，其他文档不受影响
        // 1. 尽力而为：把还能读出来的内容存下来。树可能已经损坏，结果可能不对或直接失败；
        //    runCatching 也能接住损坏的树引发的 StackOverflowError
        runCatching {
            File(file.path + ".recovered").bufferedWriter().use { out ->
                buffer.readPiecesContent { out.write(it) }
            }
        }
        // 2. 从可信来源重建：最近一次保存的文件（或你的自动保存 / 日志）
        buffer = load()
        // 3. 撤销历史描述的是旧内容
        undoStack.clear()
        onRecovered(cause)
    }

    // ------------------------------------------------------------- 2. 校验位置

    /**
     * 把 (line, column) 钳制进文档，并且不让它停在代理对中间。
     * 凡是来自 UI 的位置都先过一遍：点击、恢复的光标、旧版本文档上的搜索结果。
     */
    fun validPosition(line: Int, column: Int): Position = lock.read {
        val b = buffer
        val l = line.coerceIn(1, b.getLineCount())
        var c = column.coerceIn(1, b.getLineMaxColumn(l))
        if (c in 2..b.getLineLength(l)) {
            val before = b.getLineCharCode(l, c - 2).toChar()
            val at = b.getLineCharCode(l, c - 1).toChar()
            if (before.isHighSurrogate() && at.isLowSurrogate()) c--
        }
        Position(l, c)
    }

    // ------------------------------------------------------------- 3. 超长行

    /**
     * 视图要绘制的一行：短行取整行，长行只取可见窗口。getLineContent 会复制整行，
     * 压缩过的文件可能每帧都要复制几 MB；getValueInRange 只复制请求的列。
     */
    fun renderText(line: Int, firstVisibleColumn: Int, visibleColumns: Int): String =
        lock.read {
            val b = buffer
            when {
                line !in 1..b.getLineCount() -> ""
                // 归一化的缓冲区上只是两次偏移量查找，不复制
                b.getLineLength(line) <= LONG_LINE -> b.getLineContent(line)
                else -> {
                    val maxColumn = b.getLineMaxColumn(line)
                    val start = firstVisibleColumn.coerceIn(1, maxColumn)
                    val end = (start + visibleColumns).coerceAtMost(maxColumn)
                    b.getValueInRange(Range(line, start, line, end))
                }
            }
        }

    // ------------------------------------------------------------- 4. 后台任务

    /** 给后台任务（高亮、保存）用的快照，以及它来自哪个缓冲区 */
    fun snapshot(): Pair<PieceTreeTextBuffer, PieceTreeSnapshot> =
        lock.read { buffer to buffer.createSnapshot() }

    /** 在 [source] 的快照 [snapshot] 上算出的结果，是否仍然对应当前文档 */
    fun isCurrent(source: PieceTreeTextBuffer, snapshot: PieceTreeSnapshot): Boolean =
        lock.read { buffer === source && source.getVersionId() == snapshot.versionId }

    companion object {
        const val LONG_LINE = 10_000
    }
}
```

四部分的说明：

- **恢复。** `IllegalArgumentException` 只会在任何修改发生之前抛出，缓冲区仍然完好，要修的是
  调用方。其他异常（内部缺陷、`OutOfMemoryError`）都可能让树停在更新到一半的状态：这个缓冲区
  不要再编辑。损坏只限于这个缓冲区（每棵树有自己的 sentinel），其他文档无需任何处理。
  `.recovered` 文件只是给用户的补救，不是可信数据源。
- **位置。** 传给 `applyEdits`、`getValueInRange`、`find` 的区间会被钳制，按行查询的方法遇到不存在的
  行会抛 `IndexOutOfBoundsException`。来自 UI 的位置仍建议在一个地方统一钳制，这样还能避免光标停在
  emoji 中间（缓冲区内部的区间校验不处理这一点）。
- **超长行。** `getLineLength` / `getLineMaxColumn` 只有在 EOL 已归一化的缓冲区上才便宜
  （`normalizeEOL = true`）；未归一化时它们要把整行读出来测量。
- **后台任务。** 创建快照很快，在读锁下进行；读快照不需要任何锁。恢复后的文档换了一个新缓冲区，
  版本号重新从 0 开始，所以 `isCurrent` 还要比较缓冲区是否同一个。见[边输入边高亮](piecetable_architecture_cn.md#边输入边高亮)。
