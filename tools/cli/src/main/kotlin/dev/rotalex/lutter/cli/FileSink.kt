package dev.rotalex.lutter.cli

import dev.rotalex.lutter.codegen.GeneratedFiles
import java.io.File

/** Codegen never touches the filesystem, so this adapter does: paths out, files written. */
public object FileSink {
    /** Writes every file under [outDir], creating directories. Returns the files written. */
    public fun write(outDir: File, files: GeneratedFiles): List<File> {
        val written: MutableList<File> = mutableListOf()
        for (generated in files.files) {
            val target = File(outDir, generated.path)
            check(!target.exists() || target.isFile) { "Not a file: '${target.path}'" }
            target.parentFile?.mkdirs()
            target.writeText(generated.content)
            written += target
        }
        return written
    }
}
