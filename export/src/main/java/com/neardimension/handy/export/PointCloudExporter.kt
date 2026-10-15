package com.arhand.export

import android.content.Context
import android.os.Environment
import com.arhand.util.Vec3
import java.io.File
import java.io.PrintWriter

/**
 * Exports the accumulated point cloud as ASCII PLY.
 * Useful for Phase 2 / ARCore integration debugging.
 */
object PointCloudExporter {

    fun exportPly(context: Context, points: List<Vec3>): File {
        val fileName = "handy_cloud_${System.currentTimeMillis()}.ply"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
        PrintWriter(file).use { w ->
            w.println("ply")
            w.println("format ascii 1.0")
            w.println("element vertex ${points.size}")
            w.println("property float x")
            w.println("property float y")
            w.println("property float z")
            w.println("end_header")
            for (p in points) w.println("${p.x} ${p.y} ${p.z}")
        }
        return file
    }
}
